---
title: AUTH-03 config-seeded users unusable via application.yaml (Ktor YAML `$`-prefix env-swap collision)
area: backend
severity: high
status: fixed
fixed: 2026-06-22
found: 2026-06-22
phase: 3
requirement: AUTH-03 / D-01 / D-02
discovered_during: Phase-3 browser UAT deep-dive (auth setup)
---

## Symptom
Setting `auth.users` in `application.yaml` with an Argon2id `passwordHash` crashes startup:

```
io.ktor.server.config.ApplicationConfigurationException:
Required environment variable "argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>" not found and no default value is present
  at io.ktor.server.config.yaml.YamlConfigKt.swapEnvironmentVariables(...)
  at com.jh.proj.coroutineviz.auth.UserStore$Companion.fromConfig(UserStore.kt:27)
```

## Root cause
Ktor 3.3.2 `ktor-server-config-yaml` `swapEnvironmentVariables` treats **any scalar that
startsWith("$") as an env-var reference** (both `${VAR}` and bare `$VAR` forms) and has **no
escape syntax** (verified by decompiling `YamlConfigKt`: it checks `startsWith("$")` /
`startsWith("${")` / `endsWith("}")`, no `$$` or `\$` handling). Every Argon2id PHC hash begins
with `$argon2id`, so the value is parsed as an env-var name and rejected.

Injecting via `${SEED_HASH}` does NOT help: `ApplicationConfig.getString()` re-applies the swap on
the *resolved* value at access time (`UserStore.fromConfig` → `propertyOrNull("passwordHash").getString()`),
so the resolved `$argon2id...` is re-scanned and fails identically.

## Why tests didn't catch it
`AuthTest`/`JwtAuthTest` seed users via `MapApplicationConfig.put("auth.users.0.passwordHash", hash)`
programmatically, which bypasses YAML env-swapping entirely. So the documented YAML path
(`application.yaml` comment: "pre-computed via `Password.hash(plain).withArgon2().result`") has
zero coverage and does not actually work.

## Impact
The AUTH-03 / D-01 "config-seeded JWT users" capability cannot be configured by an operator using
the documented mechanism. JWT login (`POST /api/auth/token`) therefore can't be exercised in a real
deployment without code changes. API-key auth (`auth.apiKey`, `auth.keys`) is unaffected (plain hex,
no leading `$`).

## Candidate fixes
- Decode `passwordHash` from a `$`-safe encoding (e.g. base64 of the PHC string) in `UserStore.fromConfig`,
  and document that encoding; OR
- Read seeded-user hashes from a side file / dedicated env var that `UserStore` resolves itself
  (not through Ktor's YAML getString); OR
- Store the Argon2 parameters + salt + hash as separate non-`$` fields and reconstruct the PHC string.
- Add a YAML-config integration test that actually boots with a seeded Argon2id user.


## ✅ RESOLUTION (2026-06-22)
`UserStore.fromConfig` now accepts `passwordHashB64` — the Base64 of the Argon2id PHC string — and
decodes it back before `Password.check(...).withArgon2()`. Base64 contains no `$`, so it survives
Ktor's YAML env-swap (and the `.getString()` re-scan). Raw `passwordHash` still works for
programmatic/test config (non-breaking). `application.yaml` ships an env-driven, inert-by-default
seeded-user template (`SEED_USER`/`SEED_HASH_B64`/`SEED_ROLE`).

Caveat surfaced & handled: the seed hash MUST be produced by password4j (the backend's hasher), not
argon2-cffi — password4j's `Password.check(...).withArgon2()` verifies against its own default Argon2
params (m=15360,t=2,p=1), so an argon2-cffi hash (m=65536,t=3,p=4) fails to verify.

Tests: `UserStoreTest` (base64 decode + password4j verify/reject, raw-path unchanged, inert default).
Verified live: `POST /api/auth/token {alice/vizcore123}` → 200 token; wrong password → 401.
