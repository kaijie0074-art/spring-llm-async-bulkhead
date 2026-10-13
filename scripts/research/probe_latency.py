#!/usr/bin/env python3
"""研究探针：直连真实 LLM API 测单调用延迟分布 / max_tokens 扫描 / 并发 429 行为。

只用标准库。串行模式（默认）：每次调用间隔 --sleep 秒（配额节流纪律）。
并发模式（--concurrency N，N<=10）：单轮同时发 N 个请求，仅用于端点并发容量探测。
遇 429：sleep 60s 重试，最多 3 次（对外表现为该样本 latency 含重试等待，并记 retries）。

输出：JSONL（每调用一行，含 usage/finish_reason）+ stdout 摘要。
用法示例：
  KEY=... python3 scripts/research/probe_latency.py --n 12 --label q1-default \
      --out evidence/latency-q1-default.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 绕过代理 env


def post_chat(base_url: str, api_key: str, model: str, prompt: str,
              max_tokens: int | None, stream: bool, timeout: float) -> dict:
    """单次 chat 调用；返回计时与解析结果。429 时 sleep 60 重试（<=3 次）。"""
    body: dict = {"model": model, "messages": [{"role": "user", "content": prompt}]}
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    if stream:
        body["stream"] = True
    data = json.dumps(body).encode()
    retries = 0
    ttfb_ms = None
    while True:
        req = urllib.request.Request(
            base_url.rstrip("/") + "/chat/completions", data=data,
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + api_key},
            method="POST")
        t0 = time.monotonic()
        try:
            resp = _OPENER.open(req, timeout=timeout)
            if not stream:
                raw = resp.read()
                latency_ms = (time.monotonic() - t0) * 1000
                j = json.loads(raw)
                ch = (j.get("choices") or [{}])[0]
                return {"code": resp.status, "latency_ms": round(latency_ms, 1),
                        "prompt_tokens": (j.get("usage") or {}).get("prompt_tokens"),
                        "completion_tokens": (j.get("usage") or {}).get("completion_tokens"),
                        "total_tokens": (j.get("usage") or {}).get("total_tokens"),
                        "finish_reason": ch.get("finish_reason"),
                        "content_len": len((ch.get("message") or {}).get("content") or ""),
                        "retries": retries, "ttfb_ms": None}
            # 流式：测 TTFT（首个 data: chunk）与总时长/分块数
            chunks = 0
            content_chars = 0
            finish_reason = None
            for line in resp:
                now = time.monotonic()
                if ttfb_ms is None:
                    ttfb_ms = round((now - t0) * 1000, 1)
                s = line.decode("utf-8", "replace").strip()
                if not s.startswith("data:"):
                    continue
                payload = s[5:].strip()
                if payload == "[DONE]":
                    continue
                chunks += 1
                try:
                    j = json.loads(payload)
                    delta = (j.get("choices") or [{}])[0].get("delta") or {}
                    content_chars += len(delta.get("content") or "")
                    fr = (j.get("choices") or [{}])[0].get("finish_reason")
                    if fr:
                        finish_reason = fr
                except json.JSONDecodeError:
                    pass
            latency_ms = (time.monotonic() - t0) * 1000
            return {"code": resp.status, "latency_ms": round(latency_ms, 1),
                    "prompt_tokens": None, "completion_tokens": None, "total_tokens": None,
                    "finish_reason": finish_reason, "content_len": content_chars,
                    "retries": retries, "ttfb_ms": ttfb_ms, "sse_chunks": chunks}
        except urllib.error.HTTPError as e:
            latency_ms = (time.monotonic() - t0) * 1000
            if e.code == 429 and retries < 3:
                retries += 1
                print(f"[429] 第 {retries} 次重试，sleep 60s ...", file=sys.stderr)
                time.sleep(60)
                continue
            return {"code": e.code, "latency_ms": round(latency_ms, 1),
                    "error": e.read().decode("utf-8", "replace")[:200],
                    "prompt_tokens": None, "completion_tokens": None, "total_tokens": None,
                    "finish_reason": None, "content_len": 0, "retries": retries, "ttfb_ms": None}
        except Exception as e:  # 连接错误等
            return {"code": -1, "latency_ms": round((time.monotonic() - t0) * 1000, 1),
                    "error": repr(e)[:200], "prompt_tokens": None, "completion_tokens": None,
                    "total_tokens": None, "finish_reason": None, "content_len": 0,
                    "retries": retries, "ttfb_ms": None}


def pctl(xs: list[float], p: float) -> float:
    if not xs:
        return float("nan")
    s = sorted(xs)
    return s[min(len(s) - 1, int(len(s) * p))]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--sleep", type=float, default=2.0, help="串行模式调用间隔（节流纪律）")
    ap.add_argument("--concurrency", type=int, default=1)
    ap.add_argument("--max-tokens", type=int, default=None)
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "glm-4-flash"))
    ap.add_argument("--prompt", default="请优化简历 r-1")
    ap.add_argument("--stream", action="store_true")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--label", default="probe")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    base = os.environ.get("LLM_BASE_URL", "https://open.bigmodel.cn/api/coding/paas/v4")
    key = os.environ.get("LLM_API_KEY", "")
    if not key:
        sys.exit("缺 LLM_API_KEY 环境变量")
    if args.concurrency > 10:
        sys.exit("研究纪律：并发压测 <=10")
    if args.concurrency > 1 and args.n != args.concurrency:
        # 并发模式只做单轮 burst：n 强制等于 concurrency
        print(f"[warn] 并发模式下 n 强制对齐 concurrency={args.concurrency}")
        args.n = args.concurrency

    rows: list[dict] = []
    if args.concurrency == 1:
        for i in range(args.n):
            r = post_chat(base, key, args.model, args.prompt, args.max_tokens, args.stream, args.timeout)
            r.update({"i": i, "label": args.label, "ts": time.strftime("%H:%M:%S"),
                      "model": args.model, "max_tokens": args.max_tokens, "stream": args.stream})
            rows.append(r)
            print(f"[{i+1}/{args.n}] {r['code']} {r['latency_ms']}ms "
                  f"ptok={r['prompt_tokens']} ctok={r['completion_tokens']} "
                  f"finish={r['finish_reason']} ttfb={r['ttfb_ms']}")
            if i < args.n - 1:
                time.sleep(args.sleep)
    else:
        results: list[dict | None] = [None] * args.n
        def work(i: int) -> None:
            results[i] = post_chat(base, key, args.model, args.prompt,
                                   args.max_tokens, args.stream, args.timeout)
        threads = [threading.Thread(target=work, args=(i,)) for i in range(args.n)]
        t0 = time.monotonic()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        wall = (time.monotonic() - t0) * 1000
        for i, r in enumerate(results):
            assert r is not None
            r.update({"i": i, "label": args.label, "ts": time.strftime("%H:%M:%S"),
                      "model": args.model, "max_tokens": args.max_tokens, "stream": args.stream})
            rows.append(r)
            print(f"[{i+1}/{args.n}] {r['code']} {r['latency_ms']}ms ctok={r['completion_tokens']}")
        print(f"burst wall={wall:.0f}ms")

    if args.out:
        with open(args.out, "a", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")

    ok = [r["latency_ms"] for r in rows if r["code"] == 200]
    bad = [r for r in rows if r["code"] != 200]
    ctoks = [r["completion_tokens"] for r in rows if r["completion_tokens"]]
    rates = [round(r["completion_tokens"] / (r["latency_ms"] / 1000), 2)
             for r in rows if r["completion_tokens"] and r["code"] == 200]
    print(f"--- 摘要 {args.label} ---")
    print(f"ok={len(ok)}/{len(rows)} 429/err={len(bad)}")
    if ok:
        print(f"latency ms: P50={pctl(ok,0.5):.0f} P95={pctl(ok,0.95):.0f} "
              f"min={min(ok):.0f} max={max(ok):.0f} mean={sum(ok)/len(ok):.0f}")
    if ctoks:
        print(f"completion_tokens: min={min(ctoks)} max={max(ctoks)} mean={sum(ctoks)/len(ctoks):.0f}")
    if rates:
        print(f"tok/s: min={min(rates)} max={max(rates)} mean={sum(rates)/len(rates):.1f}")


if __name__ == "__main__":
    main()
