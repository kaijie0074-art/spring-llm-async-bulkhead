#!/usr/bin/env python3
"""离线研究用假 LLM 端点（stdlib）：复刻测试源集 FakeLlmServer 的控制协议，
供真实 jar（fixed 模式）做熔断/池参数扫描——确定性、零 API 配额。

端点：
  POST /v1/chat/completions —— 延迟 delay_ms 后 200（fail 模式下立即 429）
  GET  /__ctl/fail429 | /__ctl/ok | /__ctl/delay?ms=N —— 运行中切换剧本
  GET  /__ctl/stats         —— 累计请求计数（ok/429）
用法：python3 scripts/research/fake_llm.py --port 18081 --delay-ms 26000
"""
from __future__ import annotations

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"delay_ms": 26000, "fail": False, "ok_count": 0, "r429_count": 0}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静默访问日志
        pass

    def _json(self, code: int, obj: dict) -> None:
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self) -> None:
        if self.path.endswith("/chat/completions"):
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)
            with lock:
                if state["fail"]:
                    state["r429_count"] += 1
                    self._json(429, {"error": {"code": "1302", "message": "并发上限"}})
                    return
            import time
            time.sleep(state["delay_ms"] / 1000.0)  # 复现慢推理（可运行中调整）
            with lock:
                state["ok_count"] += 1
            self._json(200, {"choices": [{"message": {"content": "optimized"}, "finish_reason": "stop"}],
                             "usage": {"prompt_tokens": 10, "completion_tokens": 20}})
        else:
            self._json(404, {})

    def do_GET(self) -> None:
        if self.path.startswith("/__ctl/fail429"):
            with lock:
                state["fail"] = True
            self._json(200, {"fail": True})
        elif self.path.startswith("/__ctl/ok"):
            with lock:
                state["fail"] = False
            self._json(200, {"fail": False})
        elif self.path.startswith("/__ctl/delay"):
            ms = self.path.split("ms=")[-1].split("&")[0]
            with lock:
                state["delay_ms"] = int(ms)
            self._json(200, {"delay_ms": state["delay_ms"]})
        elif self.path.startswith("/__ctl/stats"):
            with lock:
                snap = dict(state)
            self._json(200, snap)
        else:
            self._json(404, {})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18081)
    ap.add_argument("--delay-ms", type=int, default=26000)
    args = ap.parse_args()
    state["delay_ms"] = args.delay_ms
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
