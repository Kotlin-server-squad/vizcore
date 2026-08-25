#!/usr/bin/env bash
#
# agent-attach-repro.sh — exploded-classpath agent-attach regression harness (15-13).
#
# Reproduces exactly what an ordinary IntelliJ run config does: launch the demo app with its
# FULL runtime classpath spread over `-cp` (exploded, not a boot fat jar) plus
# `-javaagent:coroutine-viz-agent-*-all.jar`. Before 15-13 this path failed soft (or crashed
# non-deterministically) because the agent's un-relocated ktor/serialization stack resolved from
# the app's `-cp` — a mixed stack mis-read Content-Length keep-alive responses into an empty
# body. The 15-13 child-first AgentClassLoader isolates the agent's stack from the app classpath.
#
# The corruption was TIMING-DEPENDENT (one launch once passed while siblings crashed), so this
# script requires 2/2 consecutive launches to attach, survive, and STREAM events (eventCount>0).
#
# Usage:  scripts/agent-attach-repro.sh
# Env:    JAVA_HOME (default azul-21), BACKEND_PORT (default 8090 — 8080 is taken by kubectl here)
set -euo pipefail

# --- Paths & config ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
DEMO_DIR="$REPO_ROOT/examples/spring-vizcore-demo"

export JAVA_HOME="${JAVA_HOME:-/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home}"
BACKEND_PORT="${BACKEND_PORT:-8090}"

AGENT_JAR="$BACKEND_DIR/coroutine-viz-agent/build/libs/coroutine-viz-agent-0.1.0-all.jar"
BACKEND_JAR="$BACKEND_DIR/build/libs/backend-all.jar"
DEMO_MAIN="com.jh.vizcore.demo.SpringVizcoreDemoApplicationKt"
SESSIONS_URL="http://localhost:$BACKEND_PORT/api/sessions"
HEALTH_URL="http://localhost:$BACKEND_PORT/api/health"

WORK_DIR="$(mktemp -d)"
BACKEND_PID=""
DEMO_PID=""

log()  { printf '\n\033[1;36m[repro]\033[0m %s\n' "$*"; }
fail() { printf '\n\033[1;31m[repro FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  [ -n "$DEMO_PID" ] && kill "$DEMO_PID" 2>/dev/null || true
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  # Give children a moment, then hard-kill any stragglers we own.
  sleep 1
  [ -n "$DEMO_PID" ] && kill -9 "$DEMO_PID" 2>/dev/null || true
  [ -n "$BACKEND_PID" ] && kill -9 "$BACKEND_PID" 2>/dev/null || true
  rm -rf "$WORK_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- 1. Build artifacts ------------------------------------------------------------------------
log "Building agent fat jar, vizcore client/core jars, and backend fat jar (JDK 21)"
( cd "$BACKEND_DIR" && ./gradlew --console=plain -q \
    :coroutine-viz-agent:shadowJar :coroutine-viz-client:jar :coroutine-viz-core:jar :buildFatJar )

log "Compiling demo classes (exploded runtime)"
( cd "$DEMO_DIR" && ./gradlew --console=plain -q classes )

[ -f "$AGENT_JAR" ]   || fail "agent jar missing: $AGENT_JAR"
[ -f "$BACKEND_JAR" ] || fail "backend fat jar missing: $BACKEND_JAR"

# --- 2. Start the backend ----------------------------------------------------------------------
log "Starting backend on :$BACKEND_PORT"
PORT="$BACKEND_PORT" java -jar "$BACKEND_JAR" > "$WORK_DIR/backend.out" 2> "$WORK_DIR/backend.err" &
BACKEND_PID=$!

log "Waiting for backend health (max 60s)"
up=""
for _ in $(seq 1 60); do
  if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then up=1; break; fi
  kill -0 "$BACKEND_PID" 2>/dev/null || fail "backend process died during startup; tail:
$(tail -n 20 "$WORK_DIR/backend.err")"
  sleep 1
done
[ -n "$up" ] || fail "backend did not become healthy within 60s; tail:
$(tail -n 20 "$WORK_DIR/backend.err")"
log "Backend healthy"

# --- 3. Extract the demo's exploded runtime classpath ------------------------------------------
log "Resolving demo runtime classpath (exploded, exactly what an IDE run config uses)"
cat > "$WORK_DIR/printcp.gradle" <<'GRADLE'
// Init script: register a task on the demo project that prints its full runtime classpath so we
// can launch the app EXPLODED (-cp) rather than as a boot fat jar. Accessed lazily (execution
// time) so the java/kotlin plugins are guaranteed applied.
allprojects {
    tasks.register('printcp') {
        doLast { println sourceSets.main.runtimeClasspath.asPath }
    }
}
GRADLE

DEMO_CP="$( cd "$DEMO_DIR" && ./gradlew --console=plain -I "$WORK_DIR/printcp.gradle" -q printcp 2>/dev/null \
             | grep -E '\.jar' | tail -1 )"
[ -n "$DEMO_CP" ] || fail "could not resolve demo runtime classpath"
# Belt-and-braces: append the demo's own output dirs (already in runtimeClasspath, harmless dup).
DEMO_CP="$DEMO_CP:$DEMO_DIR/build/classes/kotlin/main:$DEMO_DIR/build/resources/main"
log "Classpath resolved (${#DEMO_CP} chars)"

# --- 4. Launch helper: one exploded attach, assert survive + stream ----------------------------
run_exploded_launch() {
  local attempt="$1"
  local app_name="repro-exploded-$attempt"
  local out="$WORK_DIR/demo-$attempt.out"
  local err="$WORK_DIR/demo-$attempt.err"

  log "Launch #$attempt — exploded -cp + -javaagent (app=$app_name)"
  java -cp "$DEMO_CP" \
    "-javaagent:$AGENT_JAR=app=$app_name,backend=http://localhost:$BACKEND_PORT" \
    "$DEMO_MAIN" --vizcore.embedded-client=false \
    > "$out" 2> "$err" &
  DEMO_PID=$!

  # Poll up to 25s: fail fast on a dead JVM or a DISABLED fail-soft line; succeed when a session
  # named repro-exploded-$attempt is STREAMING (eventCount>0), not merely created.
  local streamed=""
  for _ in $(seq 1 25); do
    if grep -q "coroutine-viz-agent] DISABLED" "$err" 2>/dev/null; then
      fail "launch #$attempt: agent failed soft (DISABLED) — isolation regression. stderr:
$(tail -n 30 "$err")"
    fi
    kill -0 "$DEMO_PID" 2>/dev/null || fail "launch #$attempt: demo JVM aborted. stderr:
$(tail -n 30 "$err")"
    if curl -sf "$SESSIONS_URL" 2>/dev/null \
         | jq -e --arg p "$app_name" 'any(.[]; (.sessionId|startswith($p)) and (.eventCount>0))' \
         > /dev/null 2>&1; then
      streamed=1; break
    fi
    sleep 1
  done

  [ -n "$streamed" ] || fail "launch #$attempt: no streaming session ($app_name, eventCount>0) within 25s. stderr:
$(tail -n 30 "$err")"

  local count
  count="$(curl -sf "$SESSIONS_URL" | jq -r --arg p "$app_name" \
            '[.[] | select(.sessionId|startswith($p)) | .eventCount] | max')"
  log "Launch #$attempt PASS — session streaming, eventCount=$count"

  kill "$DEMO_PID" 2>/dev/null || true
  sleep 1
  kill -9 "$DEMO_PID" 2>/dev/null || true
  DEMO_PID=""
}

# --- 5. Require 2/2 consecutive launches (corruption was timing-dependent) ----------------------
run_exploded_launch 1
run_exploded_launch 2

log "SUCCESS — 2/2 exploded-classpath launches attached, survived, and streamed events."
