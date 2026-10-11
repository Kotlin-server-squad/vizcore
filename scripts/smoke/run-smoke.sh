#!/usr/bin/env bash
# Boot the backend fat jar in memory mode, run the golden-path smoke test against it, and
# always take the server back down. Exits non-zero on any failure and leaves no listener on
# the port, so CI (and the plan's Check) can rely on both. Usage:
#
#   scripts/smoke/run-smoke.sh backend/build/libs/<name>-all.jar
#
# SMOKE_PORT (default 18080) picks the port; it must be free, and a busy port is a hard
# failure rather than something this script clears — never kill a process you did not start.
set -euo pipefail

usage() {
  echo "usage: $(basename "$0") <fat-jar>" >&2
  echo "  boots the jar on \${SMOKE_PORT:-18080} in memory mode and runs the smoke test" >&2
}

if [ "$#" -ne 1 ]; then
  usage
  exit 2
fi
case "$1" in
  -h | --help)
    usage
    exit 0
    ;;
esac

jar="$1"
if [ ! -f "$jar" ]; then
  echo "run-smoke.sh: no such jar: $jar" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
port="${SMOKE_PORT:-18080}"
base="http://127.0.0.1:${port}"
ready_timeout_s=90
log="$(mktemp -t vizcore-smoke-server.XXXXXX.log)"
pid=""

cleanup() {
  local code=$?
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "--- stopping server (pid $pid)"
    kill "$pid" 2>/dev/null || true
    # Give the JVM a moment to exit on SIGTERM, then insist, so the port is free on return.
    for _ in $(seq 1 50); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.2
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
  fi
  if [ "$code" -ne 0 ]; then
    echo "--- server log (last 60 lines of $log)" >&2
    tail -n 60 "$log" >&2 || true
  else
    rm -f "$log"
  fi
  return "$code"
}
trap cleanup EXIT

if curl -sS -o /dev/null --max-time 5 "${base}/api/ready" 2>/dev/null; then
  echo "run-smoke.sh: something is already serving ${base}; set SMOKE_PORT to a free port" >&2
  exit 2
fi

echo "--- booting $jar on port $port (memory mode), log: $log"
PORT="$port" STORAGE_TYPE=memory java -jar "$jar" >"$log" 2>&1 &
pid=$!

echo "--- waiting up to ${ready_timeout_s}s for ${base}/api/ready"
deadline=$((SECONDS + ready_timeout_s))
until curl -fsS -o /dev/null --max-time 5 "${base}/api/ready" 2>/dev/null; do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "run-smoke.sh: server exited before becoming ready" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "run-smoke.sh: ${base}/api/ready never returned 2xx within ${ready_timeout_s}s" >&2
    exit 1
  fi
  sleep 1
done
echo "--- server ready, running golden_path.py"

rc=0
SMOKE_PORT="$port" python3 "${script_dir}/golden_path.py" || rc=$?
if [ "$rc" -ne 0 ]; then
  echo "run-smoke.sh: golden_path.py failed (exit $rc)" >&2
fi
exit "$rc"
