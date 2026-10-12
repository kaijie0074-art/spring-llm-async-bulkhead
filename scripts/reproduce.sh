#!/usr/bin/env bash
# 复现：集成测试（隔离/饱和/超时/熔断）+ 工件自检
# 完整压测（220 并发实压 baseline vs fixed）见 docs/reproductions/README.md
set -euo pipefail
cd "$(dirname "$0")/.."

echo "### 全量集成测试"
mvn -B test

echo
echo "### 工件自检（压测报告在库）"
bash scripts/check_artifacts.sh

echo
echo "### 压测复现指引（约 2 分钟）"
grep -A8 "本机压测" docs/reproductions/README.md | head -12
