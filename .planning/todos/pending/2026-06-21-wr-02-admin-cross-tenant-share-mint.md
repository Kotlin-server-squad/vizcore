---
created: 2026-06-21T17:51:56.788Z
title: WR-02 — ADMIN can mint tenant-invisible/unrevocable public share
area: auth
status: fixed
fixed: 2026-06-23
files:
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/Tenancy.kt
resolution: chose option (a) — mint now uses a strict ownership tenant (no ADMIN Op.TRUE bypass); e2e regression added
---

## Problem

Surfaced as WR-02 in `.planning/phases/03-persistence-auth-sharing/03-REVIEW.md` (code review of the 03-07 gap-closure). Share **mint** authorization is "can the caller *see* the session" (a scoped `getSession` existence check), but **list/revoke** are scoped by `created_by`. Because an ADMIN principal's `getSession` returns ANY session via the `Op.TRUE` tenant-filter bypass (`Tenancy.kt`), an ADMIN can mint a durable public share link on another tenant's session — and that tenant can neither see it (`GET /shares` filters by their own `created_by`) nor revoke it (`revoke/3` requires a `created_by` match). Result: a tenant-invisible, tenant-unrevocable public link created by a privileged actor.

Not covered by any decision record. Not a phase-goal blocker (the core cross-tenant read/mint isolation for non-admins holds, verified by TenantIsolationE2ETest), but a real privilege-scope gap worth closing.

## Solution

TBD. Options: (a) on mint, require the caller to be the session *owner* (tenant match) rather than merely able to see it — i.e. don't let the ADMIN `Op.TRUE` bypass authorize minting; or (b) make admin-minted shares visible/revocable by the owning tenant (admin shares surface in the owner's `/shares` list). Decide whether ADMIN minting on others' sessions is ever intended; if yes, it must be owner-visible + owner-revocable. Add an e2e case to TenantIsolationE2ETest for the admin-mint path.
