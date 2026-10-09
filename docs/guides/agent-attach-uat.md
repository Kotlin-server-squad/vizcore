# Agent-Attach UAT Harness

How to manually verify the `-javaagent` capture path — the "Run with Coroutine Visualizer"
feature — against a live backend. There are two launch shapes; **both must work**.

Alt ports on this machine: **8080 and 3000 are occupied by kubectl**, so the backend runs on
**8090** and the frontend (if used) on an alternate port — per the live-UAT harness convention.

---

## 1. Boot fat jar path (reference-stable)

The historically-verified path (verified working live 2026-07-02). Use this when you want a
known-good baseline that does not depend on classloader isolation.

1. Build the demo boot jar:
   ```bash
   cd examples/spring-vizcore-demo && ./gradlew bootJar
   ```
2. Build the agent fat jar:
   ```bash
   cd backend && ./gradlew :coroutine-viz-agent:shadowJar
   ```
3. Start the backend on 8090:
   ```bash
   cd backend && PORT=8090 ./gradlew :run          # or: PORT=8090 java -jar build/libs/backend-all.jar
   ```
4. Create an IntelliJ **"JAR Application"** run config pointing at the demo boot jar, with:
   - **Program argument:** `--vizcore.embedded-client=false`
     (so the demo does NOT self-instrument — the agent is the sole instrumenter, avoiding the
     double-client hazard).
   - **VM option:**
     `-javaagent:<abs path>/backend/coroutine-viz-agent/build/libs/coroutine-viz-agent-0.1.0-all.jar=app=<name>,backend=http://localhost:8090`
5. Run it. Within a few seconds a session named `<name>-<timestamp>` appears at
   `http://localhost:8090/api/sessions` with `eventCount > 0`.

Because a Spring Boot executable jar loads the `-javaagent` jar on the SYSTEM (parent-first)
classloader, the agent's bundled deps are self-consistent here regardless of the 15-13 loader —
this path is the reference that isolates variables when debugging the exploded path.

---

## 2. Exploded classpath path (isolated as of 15-13)

This is what an **ordinary IDE run config** does: the app's full runtime classpath is spread
over `-cp`, and the `-javaagent` jar is appended AFTER it. Before 15-13 the agent's
un-relocated ktor/serialization stack resolved from the app's `-cp` (which precedes the agent
jar), and the resulting mixed stack mis-read Content-Length keep-alive HTTP responses into an
empty body — a **timing-dependent** corruption that failed soft (or crashed) non-deterministically.

**Fix (15-13):** `boot.VizcoreAgentPremain` (the Java `Premain-Class` shim) builds a child-first
`boot.AgentClassLoader` over the agent fat jar and reflectively invokes `AgentBootstrap.run`
through it. The agent's HTTP/serialization/io stack now loads **child-first from the fat jar**
regardless of `-cp` ordering, while `kotlin.` / `kotlinx.coroutines.` stay **shared** with the
host so DebugProbes still instruments the host's coroutines.

To verify manually:

1. Use an ordinary run config for the demo (compiled classes, not a boot jar) with the same
   `-javaagent:...=app=<name>,backend=http://localhost:8090` VM option and the
   `--vizcore.embedded-client=false` program argument.
2. Or use the plugin's **"Run with Coroutine Visualizer"** on any ordinary run config.

**To re-verify automatically after ANY agent / client / core change**, run the committed harness:

```bash
BACKEND_PORT=8090 scripts/agent-attach-repro.sh
```

It builds everything, starts the backend, launches the demo EXPLODED twice, and asserts each
launch survives, emits no `DISABLED` line, and **streams** events (`eventCount > 0`). It requires
**2/2** consecutive passes because the historical corruption was timing-dependent (one exploded
launch once passed while siblings crashed).

---

## 3. Process guard — rebuild before jump-to-source UAT

**ALWAYS rebuild the demo jar/classes from the editor's current tree immediately before a
jump-to-source UAT.** A stale build silently offsets every reported `file:line` by the drift
between the compiled and on-disk source (the `+4` line drift that misdirected test 6). Rebuild
first, every time:

```bash
cd examples/spring-vizcore-demo && ./gradlew classes    # (or bootJar for the fat-jar path)
```

---

## 4. Troubleshooting

- **`[coroutine-viz-agent] DISABLED — bootstrap failed: ...`** — the agent bootstrap threw and
  was absorbed by the fail-soft shim (the host runs uninstrumented; it never aborts). As of
  15-13 this is NO LONGER the expected exploded-classpath outcome. **Check backend reachability
  first** (is the backend up on the port in the `backend=` arg? is the port right — 8090, not
  8080?). The message text after `failed:` names the underlying cause.
- **No session appears** — confirm `--vizcore.embedded-client=false` is set (otherwise the demo
  self-instruments under a DIFFERENT session name) and that the `backend=` URL matches the
  running backend.
- **Run-config names with spaces** — session ids are slugified (15-07), so a run-config name
  like `demo boot jar` becomes a safe id (`demo-boot-jar-<timestamp>`); spaces are fine.
