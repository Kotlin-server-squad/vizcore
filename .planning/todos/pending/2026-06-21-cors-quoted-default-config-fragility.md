---
created: 2026-06-21T17:51:56.788Z
title: De-quote cors.* defaults in application.yaml (same latent bug as DB_URL)
area: general
files:
  - backend/src/main/resources/application.yaml
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt
---

## Problem

Found while fixing the DB_URL boot crash (quick task 260621-rgi). `cors.allowedOrigins` and `cors.allowedMethods` in `backend/src/main/resources/application.yaml` use the same `${VAR:"..."}` literal-quoted-default pattern that made `storage.database.url` resolve to a string with a leading `"`. They don't crash today only because their consumer `HTTP.kt` defensively calls `.trim('"')` on the resolved values. This is fragile: the masking is incidental, and any new consumer of these keys (or a refactor that drops the trim) would reintroduce the bug.

## Solution

TBD (low priority, consistency). Remove the surrounding double-quotes from the `cors.allowedOrigins` and `cors.allowedMethods` defaults so the resolved values are bare strings, matching the DB_URL fix. Optionally then drop the now-unnecessary `.trim('"')` in HTTP.kt, or keep it as belt-and-suspenders. Audit the rest of application.yaml for other `${VAR:"..."}` quoted defaults.
