---
phase: 01-foundation-production-readiness
plan: 02
subsystem: ui
tags: [react, typescript, heroui, framer-motion, vitest, validation, sse]

# Dependency graph
requires:
  - phase: 01-foundation-production-readiness
    provides: "01-SPEC.md / 01-UI-SPEC.md / 01-PATTERNS.md locked backend contract for FIX-02"
provides:
  - ValidationResponse / ValidationRuleResult / BackendTimingReport types in api.ts matching the real backend
  - ValidationPanel rendering real results array filtered by type without crashing
  - TimingReportView rendering backend coroutineDurations/suspensionDurations/totalDuration shape
  - ValidationPanel.test.tsx feeding the real backend response fixture — full suite green
affects:
  - Any future frontend plan that imports from @/types/api (validation types)
  - Any plan that touches ValidationPanel, TimingReportView, or use-validation.ts

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Filter results array by r.type === 'Fail'/'Pass' — backend sealed-class discriminator pattern"
    - "Compact pass list (FiCheckCircle rows) instead of individual cards for 21-rule output"
    - "formatMs helper: >= 1000 → X.XXs, else Xms (backend timing values in milliseconds)"

key-files:
  created: []
  modified:
    - frontend/src/types/api.ts
    - frontend/src/hooks/use-validation.ts
    - frontend/src/lib/api-client.ts
    - frontend/src/components/validation/ValidationPanel.tsx
    - frontend/src/components/validation/ValidationResultCard.tsx
    - frontend/src/components/validation/TimingReportView.tsx
    - frontend/src/components/validation/ValidationPanel.test.tsx

key-decisions:
  - "Renamed ValidationResult to ValidationResponse (not kept as alias) so all consumers must update — cleaner break"
  - "ValidationWarningCard left in ValidationResultCard.tsx (unused) with local LegacyValidationWarning type to avoid importing the deleted type"
  - "ValidationPassCard moved to failCount/totalCount (not valid boolean) so component is self-consistent without caller computing derived state"
  - "Test assertions for fail count use regex /N Rule.*Failed/ because JSX renders count as separate text node"

patterns-established:
  - "Type rename: delete old interface, add new — no aliasing (keeps api.ts clean)"
  - "Compact list pattern for multi-item pass results (>20 rules): FiCheckCircle + text-xs row, no card per item"

requirements-completed: [FIX-02]

# Metrics
duration: 15min
completed: 2026-06-11
---

# Phase 01 Plan 02: Validation Frontend Type Reconciliation Summary

**Frontend validation panel stops crashing: ValidationResponse/ValidationRuleResult/BackendTimingReport types wired from api.ts through all validation components, with Failures/Passes/Timing sections and green test suite**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-06-11T13:10:00Z
- **Completed:** 2026-06-11T13:28:20Z
- **Tasks:** 3
- **Files modified:** 7

## Accomplishments

- Replaced the stale `ValidationResult`/`ValidationError`/`ValidationWarning`/`TimingReport`/`LatencyBucket` interfaces in `api.ts` with the real backend contract: `ValidationResponse`, `ValidationRuleResult`, `BackendTimingReport`
- Adapted `ValidationPanel` to filter `data.results` by `type`, rendering a Failures section (danger), compact Passes list (success inline rows), and real timing view — all `data.valid`/`data.errors`/`data.warnings` references removed
- Rewrote `TimingReportView` to render `coroutineDurations` as a horizontal bar chart, `suspensionDurations` as count+max rows, and `totalDuration` as the primary stat card
- Updated `ValidationPanel.test.tsx` fixture to the real backend shape; full frontend suite 225/225 green

## Task Commits

1. **Task 1: Reconcile api.ts validation types to the backend contract** - `2846dd7` (feat)
2. **Task 2: Adapt validation components to real backend response shape** - `fb5dad1` (feat)
3. **Task 3: Update ValidationPanel test fixture to real backend response shape** - `fda84f3` (test)

## Files Created/Modified

- `frontend/src/types/api.ts` - Replaced 5 interfaces with 3 backend-matching types (ValidationResponse, ValidationRuleResult, BackendTimingReport)
- `frontend/src/hooks/use-validation.ts` - Updated return type annotation to ValidationResponse
- `frontend/src/lib/api-client.ts` - Updated import and return type annotation to ValidationResponse
- `frontend/src/components/validation/ValidationPanel.tsx` - Adapted to filter results by type; removed all old field references; added compact passes list
- `frontend/src/components/validation/ValidationResultCard.tsx` - ValidationErrorCard props changed to ValidationRuleResult; ValidationPassCard changed to failCount/totalCount; ValidationWarningCard left with local type (unused)
- `frontend/src/components/validation/TimingReportView.tsx` - Full rewrite to render BackendTimingReport shape with formatMs helper and timing-coroutine-row testid
- `frontend/src/components/validation/ValidationPanel.test.tsx` - Fixture updated to ValidationResponse shape; removed ValidationWarningCard test; TimingReportView test updated

## Decisions Made

- Renamed `ValidationResult` to `ValidationResponse` outright (no alias) so all consumers update explicitly — cleaner than an alias that could be forgotten
- `ValidationWarningCard` intentionally left in `ValidationResultCard.tsx` with a local `LegacyValidationWarning` interface to avoid importing the deleted type while keeping the component available per the UI-SPEC "leave in file, remove references only" requirement
- `ValidationPassCard` props moved from `valid/errorCount/warningCount` to `failCount/totalCount` (caller does no derivation, component derives `valid = failCount === 0`)
- `api-client.ts` updated as a Rule 3 auto-fix (it imported the deleted `ValidationResult` type — blocking compilation)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated api-client.ts to use renamed ValidationResponse**
- **Found during:** Task 1 (reconcile api.ts types)
- **Issue:** `api-client.ts` imported `ValidationResult` which was renamed to `ValidationResponse`; leaving it would prevent TypeScript compilation
- **Fix:** Updated import and two type annotations in `api-client.ts` to `ValidationResponse`
- **Files modified:** `frontend/src/lib/api-client.ts`
- **Verification:** `pnpm exec tsc --noEmit` clean after fix
- **Committed in:** `2846dd7` (Task 1 commit)

**2. [Rule 1 - Bug] Test assertion regex for split text node**
- **Found during:** Task 3 (update test fixture)
- **Issue:** `screen.getByText('1 Rules Failed')` failed because JSX renders `{failCount} Rule{...} Failed` as three separate DOM text nodes; exact string match fails
- **Fix:** Changed assertion to `screen.getByText(/1 Rule.*Failed/)` and `screen.getByText(/3 Rule.*Failed/)`
- **Files modified:** `frontend/src/components/validation/ValidationPanel.test.tsx`
- **Verification:** ValidationPanel tests 12/12 pass
- **Committed in:** `fda84f3` (Task 3 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking Rule 3, 1 bug Rule 1)
**Impact on plan:** Both fixes required for compilation and test correctness. No scope creep.

## Issues Encountered

None beyond the two auto-fixed deviations above.

## Next Phase Readiness

- FIX-02 complete: the validation page no longer crashes when Run Validation is clicked; renders 21-rule output with Failures/Passes/Timing sections
- Remaining Phase 01 plans (backend FIX/FND work) are independent of these frontend changes
- The `ValidationResponse` type contract is locked; future plans should import from `@/types/api`

## Self-Check: PASSED

- All 7 modified files confirmed present on disk
- All 3 task commits verified in git log (2846dd7, fb5dad1, fda84f3)
- TypeScript: clean (0 errors)
- Tests: 225/225 passed

---
*Phase: 01-foundation-production-readiness*
*Completed: 2026-06-11*
