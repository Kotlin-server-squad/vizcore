---
title: POST /api/scenarios/custom returns 500 on a missing/malformed request body (should be 400)
area: backend
severity: low
status: fixed
fixed: 2026-06-22
found: 2026-06-22
phase: 3
requirement: API robustness / "Build Custom Scenario"
discovered_during: Phase-3 browser UAT deep-dive (coverage probe of edge endpoints)
ledger_id: F12
---

## Symptom
`POST /api/scenarios/custom` with an empty/invalid JSON body (`{}`) returned **HTTP 500**. A valid
config body works fine, so this only affects malformed input — but a client error should be 400.

## Root cause
`ScenarioRunnerRoutes.kt` did `call.receive<ScenarioConfigRequest>()`; a missing required field fails
deserialization, which Ktor surfaces as `BadRequestException`. That fell through to the generic
`catch (e: Exception)` → `InternalServerError` (500).

## ✅ RESOLUTION (2026-06-22)
Added a `catch (e: BadRequestException)` clause before the generic handler returning
`HttpStatusCode.BadRequest` with a "Malformed request body" message. `IllegalArgumentException`
(semantic validation) still maps to 400 as before; genuine internal errors still 500.

## Follow-up
- Consider a small contract test: malformed custom-scenario body → 400 (not 500).
