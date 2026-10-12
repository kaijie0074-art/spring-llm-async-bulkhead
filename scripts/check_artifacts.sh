#!/usr/bin/env bash
# 工件自检：两份压测报告存在且含 P99 数字、无 TODO 残留
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0

for f in evidence/loadtest-baseline.md evidence/loadtest-fixed.md; do
  if [ ! -f "$f" ]; then echo "缺 $f"; fail=1; continue; fi
  if ! grep -q "P99" "$f"; then echo "$f 缺 P99 数据"; fail=1; fi
  if ! grep -q "api/login" "$f"; then echo "$f 缺 login 数据"; fail=1; fi
done

if grep -rn "TODO\|FIXME" src/main/java src/test/java --include="*.java"; then
  echo "存在 TODO/FIXME 残留"; fail=1
fi

[ "$fail" = 0 ] && echo "check_artifacts: PASS"
exit $fail
