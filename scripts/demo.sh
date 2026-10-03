#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
cd "$(dirname "$0")/.."

echo '== 1) publish v1 (expectedVersion 0) =='
curl -sS -X POST "$BASE/v1/versions" -H 'Content-Type: application/json' \
  --data-binary @scripts/v1.json; echo

echo '== 2) republish with stale expectedVersion -> 409, no version consumed =='
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/v1/versions" \
  -H 'Content-Type: application/json' --data-binary @scripts/v1.json

echo '== 3) render: zh-Hant missing key falls back to zh (sourceLang in reply) =='
curl -sS -X POST "$BASE/v1/render" -H 'Content-Type: application/json' \
  --data-binary @scripts/render-zhHant.json; echo

echo '== 4) render: english exact/offset branches =='
curl -sS -X POST "$BASE/v1/render" -H 'Content-Type: application/json' \
  --data-binary @scripts/render-en.json; echo

echo '== 5) render with missing parameter -> 400 =='
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/v1/render" \
  -H 'Content-Type: application/json' --data-binary @scripts/bad-missing-param.json

echo '== 6) download fallback-resolved zh-Hant pack (templates + sources) =='
curl -sS "$BASE/v1/versions/latest/languages/zh-Hant/pack"; echo

echo '== 7) publish v2 with expectedVersion 1 =='
curl -sS -X POST "$BASE/v1/versions" -H 'Content-Type: application/json' \
  --data-binary @scripts/v2-hello.json; echo

echo '== 8) v1 remains immutable =='
curl -sS "$BASE/v1/versions/1/languages/en/pack" | head -c 300; echo
