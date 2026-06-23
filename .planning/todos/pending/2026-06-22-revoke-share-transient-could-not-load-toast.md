---
title: Revoking a share shows a transient "Could not load share links" error toast + leaves the stale row
area: frontend
severity: low
status: fixed
fixed: 2026-06-23
found: 2026-06-22
phase: 3
requirement: SHAR-02 / D-13 / ADR-019
discovered_during: Phase-3 browser UAT deep-dive (revoke share)
resolution: root cause was fetchJson calling response.json() on the 204 No Content; now returns undefined for 204
---

## Symptom (reproduced)
In Manage shares, clicking Revoke → "Revoke link" succeeds on the backend, but the UI:
- shows an error toast **"Could not load share links"**, and
- leaves the just-revoked row still displayed in the table.

Reopening the dialog shows the correct **"No active share links"** empty state, so it self-corrects.

## Backend is correct
- `GET /api/shared/<token>` → 404 after revoke (token dead, per ADR-019). ✅
- `GET /api/sessions/<id>/shares` → 200 `[]` (list is empty). ✅

So this is purely a frontend post-mutation refresh glitch: the shares-list refetch fired immediately
after the DELETE and errored (likely a race against the delete transaction, or the revoke mutation's
onSuccess refetch hitting a transient state / not invalidating the query cleanly). The user sees
"revoke failed" feedback even though the revoke succeeded — confusing for a destructive action.

## Candidate fixes
- On revoke success, optimistically remove the row / invalidate+refetch the
  `sessions/{id}/shares` query rather than firing a refetch that can race the delete.
- Swallow/replace the transient error toast when the mutation itself returned success.
- Add a component test: revoke → list shows empty state, no error toast.

## Update (2026-06-22) — stronger evidence: the DELETE double-fires
During the user-driven walkthrough, the revoke's network call was observed returning **404** in
DevTools, while the backend log shows exactly ONE successful revoke
(`ShareService:214 - Revoked share <token> ... by alice`). So the revoke UI issues the DELETE
twice (or re-fires after success): the first succeeds (204, deletes the row), the second hits the
already-deleted row → 404, which surfaces as the "Could not load share links" error toast. The
revoke itself is correct (share gone, public link 404s); the bug is the duplicate DELETE + the
false-failure UX. Fix should make revoke a single idempotent call and treat 404-after-known-success
as success (or dedupe the mutation).
