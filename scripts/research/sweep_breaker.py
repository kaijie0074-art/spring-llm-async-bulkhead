#!/usr/bin/env python3
"""Q4 熔断参数扫描（离线、确定性、零 API 配额）。

剧本：上游从 t=0 起 429 故障，t=RECOVER_S 恢复；固定节奏单发探测 /api/resume/optimize。
对每组 (waitDurationInOpenState, permittedHalfOpen)：
  - 启 fake_llm(26s 延迟, fail429) + 真实 jar(fixed, base-url=fake)
  - 记录每请求 (t, code, latency) CSV
  - 指标：恢复时延（/__ctl/ok 后首个 200 的时间）、故障期浪费的探测调用数（fake 429 计数）、
    恢复后仍被降级的请求数
用法：python3 scripts/research/sweep_breaker.py --jar target/xxx.jar --out evidence/breaker-sweep-20261014
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:18080"
FAKE = "http://127.0.0.1:18081"
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(url: str, timeout: float = 90) -> tuple[int, float]:
    t0 = time.monotonic()
    try:
        with _OPENER.open(url, timeout=timeout) as r:
            r.read()
            return r.status, (time.monotonic() - t0) * 1000
    except urllib.error.HTTPError as e:
        return e.code, (time.monotonic() - t0) * 1000


def post_optimize(tag: str, t_start: float, rows: list) -> None:
    t0 = time.monotonic()
    req = urllib.request.Request(BASE + "/api/resume/optimize?resumeId=" + tag,
                                 data=b"", method="POST")
    try:
        with _OPENER.open(req, timeout=90) as r:
            r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        code = e.code
    rows.append({"t": round(time.monotonic() - t_start, 2), "code": code,
                 "ms": round((time.monotonic() - t0) * 1000, 0)})


def wait_port(url: str, deadline: float = 60) -> bool:
    t0 = time.time()
    while time.time() - t0 < deadline:
        try:
            with _OPENER.open(url, timeout=2) as r:
                r.read()
                return True
        except Exception:
            time.sleep(0.5)
    return False


def run_config(jar: str, wait_ms: int, half_open: int, recover_s: float,
               total_s: float, out_prefix: str) -> dict:
    fake = subprocess.Popen([sys.executable, "scripts/research/fake_llm.py",
                             "--port", "18081", "--delay-ms", "26000"])
    svc = None
    rows: list[dict] = []
    try:
        assert wait_port(FAKE + "/__ctl/stats"), "fake llm 未就绪"
        get(FAKE + "/__ctl/fail429")
        svc = subprocess.Popen(["java", "-jar", jar,
                                "--ai.mode=fixed",
                                "--ai.llm.base-url=" + FAKE,
                                "--ai.llm.read-timeout-ms=30000",
                                "--ai.llm.deferred-result-timeout-ms=40000",
                                "--ai.breaker.wait-duration-in-open-state-ms=%d" % wait_ms,
                                "--ai.breaker.permitted-number-of-calls-in-half-open-state=%d" % half_open,
                                "--server.port=18080"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        assert wait_port(BASE + "/actuator/health"), "服务未就绪"
        t_start = time.monotonic()
        stop = threading.Event()

        recovered = threading.Event()

        def recovery_timer() -> None:
            while not stop.is_set() and time.monotonic() - t_start < recover_s:
                time.sleep(0.25)
            get(FAKE + "/__ctl/ok")  # 上游故障结束
            recovered.set()

        def probe_loop() -> None:
            i = 0
            while time.monotonic() - t_start < total_s:
                post_optimize(f"sw-{i}", t_start, rows)
                i += 1
                time.sleep(3)  # 固定 3s 探测节奏
            stop.set()

        threading.Thread(target=recovery_timer, daemon=True).start()
        th = threading.Thread(target=probe_loop)
        th.start()
        th.join(total_s + 120)
        stats = json.loads(get(FAKE + "/__ctl/stats")[0] == 200 and
                           _OPENER.open(FAKE + "/__ctl/stats").read() or b"{}")
        rec_t = next((r["t"] for r in rows if r["code"] == 200 and r["t"] >= recover_s), None)
        result = {
            "wait_ms": wait_ms, "half_open": half_open, "recover_s": recover_s,
            "requests": len(rows),
            "first_200_after_recover_s": (round(rec_t - recover_s, 1) if rec_t else None),
            "degraded_after_recover": sum(1 for r in rows if r["t"] >= recover_s and r["code"] != 200),
            "wasted_upstream_calls_during_outage": stats.get("r429_count", -1),
        }
        with open(out_prefix + ".jsonl", "w") as f:
            for r in rows:
                f.write(json.dumps(r) + "\n")
        return result
    finally:
        for p in (svc, fake):
            if p:
                p.send_signal(signal.SIGTERM)
        subprocess.run(["pkill", "-f", "spring-llm-async-bulkhead"], capture_output=True)
        subprocess.run(["pkill", "-f", "fake_llm.py"], capture_output=True)
        time.sleep(2)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", default="evidence/breaker-sweep-20261014")
    ap.add_argument("--recover-s", type=float, default=50)
    ap.add_argument("--total-s", type=float, default=140)
    args = ap.parse_args()
    configs = [(2000, 2), (10000, 2), (30000, 2), (10000, 1), (10000, 5)]
    summary = []
    for wait_ms, half_open in configs:
        print(f"=== wait={wait_ms}ms half_open={half_open} ===", flush=True)
        r = run_config(args.jar, wait_ms, half_open, args.recover_s, args.total_s,
                       f"{args.out}-w{wait_ms}-h{half_open}")
        summary.append(r)
        print(json.dumps(r, ensure_ascii=False), flush=True)
        time.sleep(3)
    with open(args.out + "-summary.json", "w") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print("done", args.out + "-summary.json")


if __name__ == "__main__":
    main()
