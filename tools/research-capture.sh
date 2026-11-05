#!/usr/bin/env bash
# research-capture.sh — разовый сбор реальных ответов cheburcheck.ru
# Нужен curl (pkg install curl). jq — по желанию.
# Запуск: bash research-capture.sh [цель: IP/домен] [папка]
# Нагрузка минимальная: 7 обычных запросов и один SSE-поток (до 150 с).
set -u
BASE="https://cheburcheck.ru"
TARGET="${1:-1.1.1.1}"
OUT="${2:-capture}"
UA="cheburcheck-android-research/0.1 (manual capture)"
SSE_MAX=150

command -v curl >/dev/null 2>&1 || { echo "Нужен curl: pkg install curl"; exit 1; }
mkdir -p "$OUT"; : > "$OUT/log.txt"

say() { printf '%s\n' "$*" | tee -a "$OUT/log.txt"; }

get() {
  local name="$1" url="$2"; shift 2
  local res
  res=$(curl -sS -m 30 -H "User-Agent: $UA" -D "$OUT/$name.headers" -o "$OUT/$name.body" \
        -w 'HTTP %{http_code}, %{time_total}s' "$@" "$url" 2>&1) || true
  say "$name: $res"
}

chk() { get "$1" "$BASE/api/v1/check" -G --data-urlencode "target=$2" -H 'Accept: application/json'; }

say "== $(date -u +%FT%TZ) старт, цель: $TARGET"
get status "$BASE/api/v1/status" -H 'Accept: application/json'; sleep 1
get healthcheck "$BASE/api/v1/healthcheck"; sleep 1
chk check_target "$TARGET"; sleep 2
chk check_target_again "$TARGET"; sleep 2
chk check_private "192.168.1.1"; sleep 2
chk check_cidr "1.1.1.0/24"; sleep 2
chk check_invalid "999.1.1.1"

ID=""
if command -v jq >/dev/null 2>&1; then ID=$(jq -r '.id // empty' "$OUT/check_target.body" 2>/dev/null); fi
[ -z "$ID" ] && ID=$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\{0,1\}\([^",}]*\).*/\1/p' "$OUT/check_target.body" 2>/dev/null | head -n1)

: > "$OUT/probe_sse.log"; : > "$OUT/probe.headers"
if [ -n "$ID" ]; then
  say "SSE id=$ID, жду до ${SSE_MAX}s"
  s=$(date +%s)
  curl -sSN -m "$SSE_MAX" -H 'Accept: text/event-stream' -H "User-Agent: $UA" \
    -D "$OUT/probe.headers" "$BASE/api/v1/probe/$ID" 2>>"$OUT/log.txt" \
    | while IFS= read -r line; do printf '%s %s\n' "$(date +%T.%N)" "$line"; done > "$OUT/probe_sse.log"
  rc=${PIPESTATUS[0]}
  say "SSE закрыт за $(( $(date +%s) - s ))s, код curl=$rc (28 = упёрлись в таймаут без закрытия потока сервером)"
else
  say "В ответе check_target нет id — SSE пропущен (см. $OUT/check_target.body)"
fi

{
  echo "### цель $TARGET, $(date -u +%FT%TZ)"
  for f in status healthcheck check_target check_target_again check_private check_cidr check_invalid; do
    echo; echo "#### $f"; cat "$OUT/$f.headers" 2>/dev/null; echo; head -c 12000 "$OUT/$f.body" 2>/dev/null; echo
  done
  echo; echo "#### probe headers"; cat "$OUT/probe.headers" 2>/dev/null
  echo; echo "#### probe timeline (строки event:)"; grep -n 'event:' "$OUT/probe_sse.log" 2>/dev/null
  echo; echo "#### probe sse (первые 15000 байт)"; head -c 15000 "$OUT/probe_sse.log" 2>/dev/null
} > "$OUT/ALL.txt"

say "Готово. Пришли файл: $OUT/ALL.txt ($(wc -c < "$OUT/ALL.txt") байт)"
