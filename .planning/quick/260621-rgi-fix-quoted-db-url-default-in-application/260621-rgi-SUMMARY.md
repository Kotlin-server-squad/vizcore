---
phase: quick
plan: 260621-rgi
subsystem: backend-persistence
tags: [config, h2, hikari, boot-fix]
requires:
  - storage.database.url config property
provides:
  - Unquoted DB_URL default for storage.database.url
affects:
  - backend boot under STORAGE_TYPE=database with no DB_URL override
tech-stack:
  added: []
  patterns:
    - "Ktor ${VAR:default} substitution preserves literal quote chars in the default — do not wrap default values in double-quotes unless the consumer trims them"
key-files:
  created: []
  modified:
    - backend/src/main/resources/application.yaml
decisions:
  - "Removed literal double-quotes from storage.database.url default; DatabaseFactory passes the raw string to HikariConfig.jdbcUrl with no .trim('\"'), so the quoting bug was fatal there (unlike CORS consumers in HTTP.kt which defensively trim)."
metrics:
  duration: ~5 min
  completed: 2026-06-21
  tasks: 2
  files: 1
---

# Quick Task 260621-rgi: Fix Quoted DB_URL Default in application.yaml Summary

Removed the literal surrounding double-quotes from the `storage.database.url` default in
`application.yaml` so the resolved JDBC URL is a bare `jdbc:h2:file:...` string, fixing the
`org.h2.Driver claims to not accept jdbcUrl` boot crash under `STORAGE_TYPE=database` with no
`DB_URL` override.

## What Was Done

### Task 1 — Remove literal double-quotes from the DB_URL default
- **File:** `backend/src/main/resources/application.yaml` (line 48)
- **Change:** `url: ${DB_URL:"jdbc:h2:file:...;..."}` → `url: ${DB_URL:jdbc:h2:file:...;...}`
- The H2 URL's semicolons live inside the `${...}` substitution braces, so YAML treats the whole
  `${DB_URL:...}` as a single scalar; no YAML quoting was required.
- **Verification:** grep confirmed the `${DB_URL:...}` default has no surrounding `"` characters
  and no `DB_URL:"` pattern remains → `OK`.
- **Commit:** `a23496c` — `fix(quick-260621-rgi): remove literal quotes from DB_URL default in application.yaml`

### Task 2 — Boot backend with STORAGE_TYPE=database and confirm persistence initializes
- Freed port 8080 (killed a stray instance from this session) before booting fresh.
- Booted with `STORAGE_TYPE=database ./gradlew run` (background) and **no** `DB_URL` override.
- Verified readiness via `GET /health` → **HTTP 200** (`{"status":"UP",...}`).
- Boot log confirmed (in `/tmp/vizcore-dbboot.log`):
  - `Persistence enabled (storage.type=database)` (Application.kt:99)
  - `Database persistence initialized (url=jdbc:h2:file:./data/coroutineviz...)` (DatabaseFactory.kt) — resolved URL is a bare `jdbc:h2:file:...` with no leading quote
  - Flyway connected to `jdbc:h2:file:./data/coroutineviz (H2 2.3)` and successfully validated + applied the migration
  - **No** `claims to not accept jdbcUrl` exception
- Stopped the test backend afterward; port 8080 confirmed free (`PORT_8080_FREE`).

## Deviations from Plan

None — plan executed exactly as written.

## Authentication Gates

None.

## Known Stubs

None.

## Out-of-Scope / Follow-up Notes

1. **Latent identical quoting in CORS defaults (intentionally left untouched).**
   `cors.allowedOrigins` (line 27) and `cors.allowedMethods` (line 28) use the same
   `${VAR:"..."}` quoted-default pattern and carry the same latent bug. They currently survive
   only because their consumer `HTTP.kt:30,32` defensively calls `.trim('"')`. Per the plan's
   scope note, these were left unchanged — only the `url` default was confirmed broken
   (DatabaseFactory does no quote-stripping). Recommended follow-up: drop the quotes from the
   CORS defaults too and/or rely consistently on the `.trim('"')` defense.

2. **`backend/data/` H2 file directory is NOT gitignored.**
   The default URL (`jdbc:h2:file:./data/coroutineviz`) creates a `backend/data/` directory on
   first database-backed boot. This appeared as untracked (`?? backend/data/`) after verification
   and is **not** covered by `.gitignore`. Per task constraints it was **left untracked and NOT
   committed**. Follow-up: add `backend/data/` (or `data/`) to `.gitignore` so local H2 files are
   never accidentally committed.

## Self-Check: PASSED

- Modified file exists: `backend/src/main/resources/application.yaml` — FOUND
- Commit exists: `a23496c` — FOUND
- Verification markers present in boot log; no acceptsURL crash; `/health` returned 200.
