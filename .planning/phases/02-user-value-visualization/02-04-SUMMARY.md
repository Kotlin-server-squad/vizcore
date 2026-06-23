---
phase: 02-user-value-visualization
plan: 04
subsystem: frontend-toast
tags: [heroui, toast, react-aria, dependency-upgrade, adr-018]
requires:
  - "@heroui/react 2.6.x baseline + HeroUIProvider mounted (main.tsx)"
provides:
  - "ToastProvider mounted app-wide (placement=bottom-right) above the router"
  - "toastSuccess/toastError helpers with the ADR-018 copy + color contract"
  - "@heroui/react upgraded to the 2.7 line (React Aria bump) verified non-regressive"
affects:
  - "02-05 (PNG/SVG/JSON export — toast feedback)"
  - "02-08 (WebM recording — toast feedback)"
tech-stack:
  added:
    - "@heroui/react ~2.7.11 (Toast system: addToast + ToastProvider)"
  patterns:
    - "Single-entry toast helpers (toastSuccess/toastError) so copy/color stay consistent across export + recording"
    - "ToastProvider mounted above the router to avoid issue #5086 ordering footgun"
key-files:
  created:
    - frontend/src/lib/toast.ts
    - frontend/src/lib/toast.test.ts
  modified:
    - frontend/package.json
    - frontend/pnpm-lock.yaml
    - frontend/src/main.tsx
decisions:
  - "Pinned @heroui/react to ~2.7.11 (tilde, not caret) to keep this an isolated 2.7-line bump"
metrics:
  duration: "~25 min (incl. human smoke checkpoint)"
  tasks_completed: 3
  files_changed: 5
  completed_date: "2026-06-14"
---

# Phase 02 Plan 04: HeroUI 2.7 Toast Upgrade Summary

Upgraded `@heroui/react` to the 2.7 line (pinned `~2.7.11`) to obtain the official Toast system, mounted `ToastProvider` at the app root above the router, and exposed `toastSuccess`/`toastError` helpers carrying the ADR-018 copy contract — verified non-regressive by the full 301-test frontend suite plus a human smoke of the React Aria primitives.

## What Was Built

- **HeroUI 2.7 bump** (`frontend/package.json`, `frontend/pnpm-lock.yaml`): `@heroui/react` pinned to `~2.7.11`, bringing in the `Toast` system (`addToast` + `ToastProvider`) that did not previously exist in the codebase. `framer-motion ^11.14.4` already satisfied the 2.7 peer range, so no other dependency was touched.
- **ToastProvider mount** (`frontend/src/main.tsx`): `<ToastProvider placement="bottom-right" />` mounted inside `HeroUIProvider` and ABOVE `<RouterProvider />`, satisfying the issue #5086 ordering requirement (provider must exist before any `addToast` fires). Existing `HeroUIProvider` wrapper preserved.
- **Toast helper** (`frontend/src/lib/toast.ts`): `toastSuccess(title)` → `addToast({ title, color: 'success' })`, `toastError(title)` → `addToast({ title, color: 'danger' })`. These are the single entry points downstream export (02-05) and recording (02-08) will call, keeping the copy/color contract consistent.
- **Unit test** (`frontend/src/lib/toast.test.ts`): mocks `@heroui/react`'s `addToast` and asserts the success/danger color mapping.

## Tasks Completed

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Bump HeroUI to 2.7, mount ToastProvider, add toast helper | a877c5d |
| 2 | Run full frontend suite as React Aria regression guard (verification-only; 301 tests green) | n/a |
| 3 | Human smoke of HeroUI 2.7 primitives (React Aria regression gate) | approved (human-verify, no commit) |

## Verification

- `pnpm test toast --run` — green (toast color mapping unit test).
- `pnpm test --run` — full suite green (301 tests; the React Aria regression guard for Tabs/Select/Slider/Dropdown/Table API drift). No tests skipped or weakened.
- `pnpm tsc --noEmit` — clean after the bump.
- Human smoke checkpoint — **approved**: developer confirmed Tabs switch, Coroutines view-mode toggle, speed Dropdown, Slider drag, and Select all behave with no HeroUI/React Aria console errors on load.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Pinned to `~2.7.11` instead of `^2.7` to keep the bump isolated**
- **Found during:** Task 1
- **Issue:** The plan instructed `@heroui/react: ^2.7`. However, the working lockfile had already drifted to 2.8.5, and a `^2.7` caret resolves to the latest 2.x **and** would allow forward drift; left as a caret it defeats the plan's explicit "bump in isolation" gating intent (the whole reason this React Aria upgrade is quarantined behind a full-suite + human-smoke gate is to control exactly which version downstream plans build on).
- **Fix:** Pinned to `~2.7.11` (tilde). The tilde constrains resolution to the 2.7.x line only, so this stays an isolated, reproducible 2.7-line bump that the human smoke checkpoint actually validated — rather than an open caret that could silently resolve to 2.8.x/3.x on a future `pnpm install` and bypass the gate.
- **Files modified:** frontend/package.json, frontend/pnpm-lock.yaml
- **Commit:** a877c5d

The plan's `must_haves` truth ("upgraded to ^2.7.x and the full existing frontend suite still passes") is satisfied: `~2.7.11` is within the 2.7.x line, and the full suite passes. The narrower pin is strictly more conservative than the plan's range and better serves the plan's stated isolation/gating purpose.

## Authentication Gates

None.

## Notes

- Downstream plans 02-05 (export) and 02-08 (recording) can now safely import `toastSuccess`/`toastError` from `frontend/src/lib/toast.ts`; the ToastProvider ordering and React Aria non-regression are both verified.
- The supply-chain threat (`T-02-06` / `T-02-SC`) was dispositioned `mitigate` and is satisfied: the bump is the legitimacy-audit-APPROVED package (same publisher as 2.6.8), applied in isolation, gated behind the full suite + human smoke before any downstream plan builds on it. No new publisher trust surface; no blocking-human legitimacy checkpoint required.

## Self-Check: PASSED

All created/modified files exist on disk; Task 1 commit `a877c5d` confirmed present in git history.
