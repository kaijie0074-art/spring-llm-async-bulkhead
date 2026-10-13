#!/usr/bin/env python3
"""Q5 有界池容量扫描（离线、确定性、零 API 配额）。

上游固定 26s 延迟（Q1 实测 P50=20.1s/P95=47.5s 的代表值），optimize 并发 10、login 并发 4、
持续 100s；对每组 (pool.max, queue) 记录：login P99（隔离是否成立）、optimize 200/503/504 分布
（容量只改变 AI 好吞吐与拒绝形态，不应触碰 login SLO）。
用法：python3 scripts/research/sweep_pool.py --jar target/xxx.jar --out evidence/pool-sweep-20261014
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:18080"
FAKE = "http://127.0.0.1:18081"
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


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


def parse_report(path: str) -> dict:
    txt = open(path).read()
    out: dict = {"file": os.path.basename(path)}
    m = re.search(r"\| /api/login \| (\d+) \| ([\d.]+) \| ([\d.]+) \| ([\d.]+) \|", txt)
    if m:
        out["login_count"] = int(m.group(1))
        out["login_p50_ms"] = float(m.group(2))
        out["login_p99_ms"] = float(m.group(4))
    m = re.search(r"\| /api/resume/optimize \| (\d+) \| ([\d.]+) \| ([\d.]+) \| ([\d.]+) \| (.+?) \|", txt)
    if m:
        out["optimize_count"] = int(m.group(1))
        out["optimize_p50_ms"] = float(m.group(2))
        out["optimize_p99_ms"] = float(m.group(4))
        dist: dict[str, int] = {}
        for code, n in re.findall(r"(\d{3})×(\d+)", m.group(5)):
            dist[code] = int(n)
        out["optimize_dist"] = dist
    return out
def run_config(jar: str, pmax: int, queue: int, duration: int, out_md: str) -> dict:
    fake = subprocess.Popen([sys.executable, "scripts/research/fake_llm.py",
                             "--port", "18081", "--delay-ms", "26000"])
    svc = None
    try:
        assert wait_port(FAKE + "/__ctl/stats")
        svc = subprocess.Popen(["java", "-jar", jar,
                                "--ai.mode=fixed",
                                "--ai.llm.base-url=" + FAKE,
                                "--ai.llm.read-timeout-ms=30000",
                                "--ai.llm.deferred-result-timeout-ms=35000",
                                "--ai.pool.core=%d" % pmax,
                                "--ai.pool.max=%d" % pmax,
                                "--ai.pool.queue-capacity=%d" % queue,
                                "--ai.breaker.minimum-number-of-calls=1000",  # 扫池不扫熔断：抬打开门槛
                                "--server.port=18080"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        assert wait_port(BASE + "/actuator/health")
        subprocess.run([sys.executable, "loadtest/run.py", "--base", BASE,
                        "--optimize", "10", "--login", "4",
                        "--duration", str(duration), "--label", f"pool{pmax}-q{queue}",
                        "--out", out_md], check=True)
        return parse_report(out_md)
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
    ap.add_argument("--out", default="evidence/pool-sweep-20261014")
    ap.add_argument("--duration", type=int, default=100)
    args = ap.parse_args()
    configs = [(2, 0), (4, 0), (8, 0), (16, 0), (8, 50)]
    summary = []
    for pmax, queue in configs:
        print(f"=== pool.max={pmax} queue={queue} ===", flush=True)
        md = f"{args.out}-p{pmax}-q{queue}.md"
        r = run_config(args.jar, pmax, queue, args.duration, md)
        r.update({"pool_max": pmax, "queue": queue})
        summary.append(r)
        print(json.dumps(r, ensure_ascii=False), flush=True)
        time.sleep(3)
    with open(args.out + "-summary.json", "w") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print("done", args.out + "-summary.json")


if __name__ == "__main__":
    main()
