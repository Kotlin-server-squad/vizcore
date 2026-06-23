---
phase: quick
plan: 260621-rgi
type: execute
wave: 1
depends_on: []
files_modified:
  - backend/src/main/resources/application.yaml
autonomous: true
requirements: []
must_haves:
  truths:
    - "Backend boots with STORAGE_TYPE=database and no DB_URL override (no acceptsURL crash)"
    - "Resolved storage.database.url is a bare jdbc:h2:file:... string with no leading literal double-quote"
    - "Flyway migrates and 'Database persistence initialized' is logged on boot"
  artifacts:
    - path: "backend/src/main/resources/application.yaml"
      provides: "Unquoted DB_URL default for storage.database.url"
      contains: "url: ${DB_URL:jdbc:h2:file:./data/coroutineviz"
  key_links:
    - from: "backend/src/main/resources/application.yaml"
      to: "DatabaseFactory.buildDataSource"
      via: "storage.database.url config property -> HikariConfig.jdbcUrl"
      pattern: "storage.database.url"
---

<objective>
Fix the backend boot crash when running with `STORAGE_TYPE=database` and no `DB_URL` override.

Purpose: In `backend/src/main/resources/application.yaml`, the `storage.database.url` default
is wrapped in LITERAL double-quotes: `${DB_URL:"jdbc:h2:file:...;..."}`. When `DB_URL` is unset,
Ktor config substitution keeps the surrounding quote characters in the resolved value, so the
JDBC URL begins with a literal `"`. `org.h2.Driver.acceptsURL()` only accepts strings starting
with `jdbc:h2:`, so it rejects it — crashing at `DatabaseFactory.buildDataSource` (DatabaseFactory.kt:71)
with `Driver org.h2.Driver claims to not accept jdbcUrl`.

Unlike the CORS consumers (HTTP.kt:30,32) which defensively call `.trim('"')`, DatabaseFactory
passes the raw string straight to `HikariConfig.jdbcUrl`, so the quoting bug is fatal there.

Output: One-line YAML edit removing the surrounding double-quotes from the `storage.database.url`
default, plus a verified boot under `STORAGE_TYPE=database`.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@backend/src/main/resources/application.yaml
@backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DatabaseFactory.kt
</context>

<tasks>

<task type="auto">
  <name>Task 1: Remove literal double-quotes from the DB_URL default</name>
  <files>backend/src/main/resources/application.yaml</files>
  <action>
On line 48 (`storage.database.url`), remove the literal surrounding double-quotes from the
default value inside the `${DB_URL:...}` substitution. Change:
`url: ${DB_URL:"jdbc:h2:file:./data/coroutineviz;AUTO_SERVER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"}`
to:
`url: ${DB_URL:jdbc:h2:file:./data/coroutineviz;AUTO_SERVER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE}`
The resolved value must be a bare `jdbc:h2:file:...` string with no leading/trailing `"`.

Do NOT quote the whole value in YAML either — the H2 URL contains semicolons but they are inside
the `${...}` substitution braces, so YAML treats the entire `${DB_URL:...}` as a single scalar;
no YAML quoting is required.

Scope note (DO NOT change in this plan): `cors.allowedOrigins` (line 27) and `cors.allowedMethods`
(line 28) use the same `${VAR:"..."}` quoted-default pattern and have the same latent bug. They
currently survive only because their consumer (HTTP.kt:30,32) defensively calls `.trim('"')`.
Record this in the SUMMARY as a known latent issue, but leave them untouched — only the `url`
default is confirmed broken and DatabaseFactory does no quote-stripping.
  </action>
  <verify>
    <automated>cd backend &amp;&amp; grep -n 'url: ${DB_URL:' src/main/resources/application.yaml | grep -v '"' &amp;&amp; ! grep -q 'DB_URL:"' src/main/resources/application.yaml &amp;&amp; echo OK</automated>
  </verify>
  <done>The `storage.database.url` default no longer contains literal double-quotes; the grep confirms the `${DB_URL:...}` default has no surrounding `"` characters.</done>
</task>

<task type="auto">
  <name>Task 2: Boot backend with STORAGE_TYPE=database and confirm persistence initializes</name>
  <files>backend/src/main/resources/application.yaml</files>
  <action>
Build and boot the backend with `STORAGE_TYPE=database` and NO `DB_URL` override to prove the
default URL now resolves to a valid H2 file URL and persistence comes up without the acceptsURL crash.

Steps:
1. Build: `cd backend &amp;&amp; ./gradlew build -x test` (or `./gradlew installDist` if faster) to confirm compilation.
2. Boot in the background with the database storage type and no DB_URL:
   `cd backend &amp;&amp; STORAGE_TYPE=database ./gradlew run` — run via the Bash tool's run_in_background
   so it can be stopped afterward, and capture stdout/stderr to a log.
3. Wait until the log contains BOTH:
   - `Persistence enabled (storage.type=database)` (Application.kt:99)
   - `Database persistence initialized` (DatabaseFactory.kt:40)
   and does NOT contain `claims to not accept jdbcUrl`.
4. Sanity-check the HTTP server is up: `curl -fsS http://localhost:8080/health` returns 200
   (the health endpoint is GET /health, NOT /api/health).
5. STOP the backend process after verifying (kill the background job / gradle daemon run).

The default URL is an H2 file DB (`jdbc:h2:file:./data/coroutineviz`) which creates a `./data`
directory under `backend/` — this is expected and acceptable.

If boot fails for an unrelated reason (e.g. a port already in use), surface the actual error;
do not mask it.
  </action>
  <verify>
    <automated>cd backend &amp;&amp; STORAGE_TYPE=database timeout 180 ./gradlew run > /tmp/vizcore-dbboot.log 2>&amp;1 &amp; GRADLE_PID=$!; for i in $(seq 1 60); do grep -q 'Database persistence initialized' /tmp/vizcore-dbboot.log &amp;&amp; break; sleep 3; done; grep -q 'Persistence enabled (storage.type=database)' /tmp/vizcore-dbboot.log &amp;&amp; grep -q 'Database persistence initialized' /tmp/vizcore-dbboot.log &amp;&amp; ! grep -q 'claims to not accept jdbcUrl' /tmp/vizcore-dbboot.log &amp;&amp; curl -fsS http://localhost:8080/health > /dev/null &amp;&amp; echo BOOT_OK; kill $GRADLE_PID 2>/dev/null; pkill -f 'coroutineviz.ApplicationKt' 2>/dev/null; true</automated>
  </verify>
  <done>Boot log shows "Persistence enabled (storage.type=database)" and "Database persistence initialized", contains no "claims to not accept jdbcUrl" error, GET /health returns 200, and the test backend is stopped afterward.</done>
</task>

</tasks>

<verification>
- YAML default for `storage.database.url` has no literal surrounding double-quotes.
- Backend boots under `STORAGE_TYPE=database` with no `DB_URL` override and reaches
  "Database persistence initialized" without the H2 acceptsURL crash.
- GET /health returns 200 while the database-backed instance is running.
- The verification backend process is stopped; no lingering process holds port 8080.
</verification>

<success_criteria>
- `backend/src/main/resources/application.yaml` line 48 resolves to a bare `jdbc:h2:file:...` URL.
- A clean boot with `STORAGE_TYPE=database` (no `DB_URL`) succeeds end-to-end (Flyway migrate +
  persistence init) with no `Driver org.h2.Driver claims to not accept jdbcUrl` exception.
- SUMMARY notes the latent identical quoting in `cors.allowedOrigins`/`cors.allowedMethods`
  (defended by HTTP.kt `.trim('"')`) as out-of-scope follow-up.
</success_criteria>

<output>
Create `.planning/quick/260621-rgi-fix-quoted-db-url-default-in-application/260621-rgi-SUMMARY.md` when done
</output>
