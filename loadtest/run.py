#!/usr/bin/env python3
"""并发压测：同时打 /api/resume/optimize（慢路径）与 /api/login（业务对照），
输出 P50/P95/P99 + 错误码分布的 markdown 报告。

仅用标准库（urllib + threading），CI/本机零第三方依赖。
用法：python3 loadtest/run.py --base http://127.0.0.1:18080 --optimize 16 --login 4 --duration 20 --out evidence/loadtest-xxx.md
"""
from __future__ import annotations

import argparse
import random
import threading
import time
import urllib.error
import urllib.request
from collections import Counter


def http_get(url: str, timeout: float = 90) -> tuple[int, float]:
    t0 = time.monotonic()
    code = 0
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            resp.read()
            code = resp.status
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception:
        code = -1
    return code, (time.monotonic() - t0) * 1000


# 压测目标恒为本机服务：显式绕过任何代理环境变量（HTTP_PROXY 等会劫持 127.0.0.1 请求）
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
urllib.request.install_opener(_OPENER)


def http_post(url: str, timeout: float = 90) -> tuple[int, float]:
    t0 = time.monotonic()
    code = 0
    req = urllib.request.Request(url, data=b"{}", method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            code = resp.status
    except urllib.error.HTTPError as e:
        e.read()
        code = e.code
    except Exception:
        code = -1
    return code, (time.monotonic() - t0) * 1000


def percentile(xs: list[float], p: float) -> float:
    if not xs:
        return 0.0
    s = sorted(xs)
    return s[min(len(s) - 1, int(len(s) * p))]


def hammer(url: str, method: str, duration: float, stop: threading.Event,
           samples: list, codes: Counter, lock: threading.Lock) -> None:
    while not stop.is_set():
        code, ms = (http_post(url) if method == "POST" else http_get(url))
        with lock:
            samples.append(ms)
            codes[code] += 1


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:18080")
    ap.add_argument("--optimize", type=int, default=16)
    ap.add_argument("--login", type=int, default=4)
    ap.add_argument("--duration", type=float, default=20)
    ap.add_argument("--label", default="")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    stop = threading.Event()
    lock = threading.Lock()
    opt_s, opt_c = [], Counter()
    log_s, log_c = [], Counter()

    threads = []
    for _ in range(args.optimize):
        threads.append(threading.Thread(target=hammer, daemon=True,
                       args=(f"{args.base}/api/resume/optimize?resumeId=lt-{random.randint(0,9999)}",
                             "POST", args.duration, stop, opt_s, opt_c, lock)))
    for _ in range(args.login):
        threads.append(threading.Thread(target=hammer, daemon=True,
                       args=(f"{args.base}/api/login", "GET", args.duration, stop, log_s, log_c, lock)))
    for t in threads:
        t.start()
    time.sleep(args.duration)
    stop.set()
    for t in threads:
        t.join(timeout=95)

    lines = [f"# 压测报告 {args.label}", "",
             f"- 参数：optimize 并发={args.optimize}，login 并发={args.login}，持续={args.duration:.0f}s",
             f"- 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}", "",
             "| 接口 | 请求数 | P50 (ms) | P95 (ms) | P99 (ms) | 状态码分布 |",
             "|---|---|---|---|---|---|"]
    for name, s, c in (("/api/login", log_s, log_c), ("/api/resume/optimize", opt_s, opt_c)):
        codes = ", ".join(f"{k}×{v}" for k, v in sorted(c.items()))
        lines.append(f"| {name} | {sum(c.values())} | {percentile(s,0.5):.0f} | "
                     f"{percentile(s,0.95):.0f} | {percentile(s,0.99):.0f} | {codes} |")
    report = "\n".join(lines) + "\n"
    print(report)
    if args.out:
        from pathlib import Path
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(report, encoding="utf-8")
        print("已写入", args.out)


if __name__ == "__main__":
    main()
