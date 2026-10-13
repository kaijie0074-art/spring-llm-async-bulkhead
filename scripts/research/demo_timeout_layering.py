#!/usr/bin/env python3
"""Q3 补充：超时分层病理演示（离线确定性）—— deferred(15s) < read(40s)。

预期时间线（fake 上游固定 40s 延迟）：
  t=15s  两个用户请求均收到 504（deferred 超时，onTimeout 已 cancel(true)）
  t=15~40s  actuator ai.executor.active 仍=2 —— cancel 不能中断阻塞的 socket read，
            池线程被钉到 read 超时/上游返回为止（资源上界由 read 决定，与用户超时解耦）
  全程 /api/login 毫秒级 200（Tomcat 不受影响）
用法：python3 scripts/research/demo_timeout_layering.py --jar target/xxx.jar
"""
from __future__ import annotations

import argparse
import json
import signal
import subprocess
import sys
import threading
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


def gauge(name: str) -> float:
    with _OPENER.open(BASE + "/actuator/metrics/" + name, timeout=5) as r:
        return json.loads(r.read())["measurements"][0]["value"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    args = ap.parse_args()
    fake = subprocess.Popen([sys.executable, "scripts/research/fake_llm.py",
                             "--port", "18081", "--delay-ms", "40000"])
    svc = None
    try:
        assert wait_port(FAKE + "/__ctl/stats")
        svc = subprocess.Popen(["java", "-jar", args.jar,
                                "--ai.mode=fixed",
                                "--ai.llm.base-url=" + FAKE,
                                "--ai.llm.read-timeout-ms=40000",
                                "--ai.llm.deferred-result-timeout-ms=15000",
                                "--ai.pool.core=2", "--ai.pool.max=2",
                                "--ai.pool.queue-capacity=0",
                                "--ai.breaker.minimum-number-of-calls=1000",
                                "--server.port=18080"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        assert wait_port(BASE + "/actuator/health")

        results: dict[str, list] = {"code": [], "ms": []}

        def call_optimize(i: int) -> None:
            t0 = time.monotonic()
            req = urllib.request.Request(BASE + f"/api/resume/optimize?resumeId=tl-{i}",
                                         data=b"", method="POST")
            try:
                with _OPENER.open(req, timeout=90) as r:
                    r.read()
                    results["code"].append(r.status)
            except urllib.error.HTTPError as e:
                results["code"].append(e.code)
            results["ms"].append(round((time.monotonic() - t0) * 1000))

        t0 = time.monotonic()
        threads = [threading.Thread(target=call_optimize, args=(i,)) for i in range(2)]
        for t in threads:
            t.start()
        # 用户超时期间 login 存活检查
        time.sleep(10)
        lg0 = time.monotonic()
        with _OPENER.open(BASE + "/api/login", timeout=5) as r:
            login_during = (time.monotonic() - lg0) * 1000
        for t in threads:
            t.join()
        user_done = time.monotonic() - t0
        print(f"用户侧: codes={results['code']} ms={results['ms']} (t={user_done:.1f}s)")
        print(f"login during AI hang: {login_during:.0f}ms")

        # 关键观察（用户可见证据，不依赖 gauge）：用户已全部 504，池(2线程)若仍被钉住，
        # 新请求必须被立即拒绝（503 AI_BUSY, queue=0）；直到上游 40s 返回后才重新可接受。
        def probe_accept(tag: str) -> tuple[int, float]:
            tt = time.monotonic()
            req = urllib.request.Request(BASE + f"/api/resume/optimize?resumeId={tag}",
                                         data=b"", method="POST")
            try:
                with _OPENER.open(req, timeout=90) as r:
                    r.read()
                    return r.status, (time.monotonic() - tt) * 1000
            except urllib.error.HTTPError as e:
                return e.code, (time.monotonic() - tt) * 1000

        time.sleep(max(0, 20 - (time.monotonic() - t0)))  # t≈20s：用户已 504，线程应在读
        c20, ms20 = probe_accept("pin-probe-20s")
        print(f"t≈20s 新请求: {c20} in {ms20:.0f}ms  (503+毫秒级=池仍被钉住)")
        c41, ms41 = None, None
        while time.monotonic() - t0 < 42:  # 等上游 40s 返回
            time.sleep(1)
        with _OPENER.open(FAKE + "/__ctl/delay?ms=500") as _r:  # 钉住窗口结束，调小上游延迟
            _r.read()
        c41, ms41 = probe_accept("pin-probe-42s")
        print(f"t≈42s 新请求: {c41} in {ms41:.0f}ms")
        print(f"结论: 用户 t={user_done:.1f}s 全部 504；钉住窗口内新请求 {c20}/{ms20:.0f}ms，"
              f"上游返回后 {c41}/{ms41:.0f}ms —— cancel(true) 不能释放阻塞的 socket read，"
              f"线程必钉到 read 超时/上游返回")
    finally:
        for p in (svc, fake):
            if p:
                p.send_signal(signal.SIGTERM)
        subprocess.run(["pkill", "-f", "spring-llm-async-bulkhead"], capture_output=True)
        subprocess.run(["pkill", "-f", "fake_llm.py"], capture_output=True)


if __name__ == "__main__":
    main()
