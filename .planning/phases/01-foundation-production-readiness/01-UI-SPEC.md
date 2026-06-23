---
phase: 1
slug: foundation-production-readiness
status: draft
shadcn_initialized: false
preset: none
created: 2026-06-11
---

# Phase 1 — UI Design Contract

> Visual and interaction contract for the FIX-02 frontend surface only.
> Phase 1 is overwhelmingly backend work. The single frontend deliverable is adapting the
> existing ValidationPanel feature (frontend/src/components/validation/) to consume the
> REAL backend response shape instead of the shape it currently expects.
> No new pages, routes, or session-view redesigns are in scope.

---

## Scope of Frontend Change

FIX-02 only. Three files change; the surrounding session view is untouched.

| File | Change type |
|------|------------|
| `frontend/src/types/api.ts` lines 616-654 | Type replacement — `ValidationResult`, `TimingReport`, drop `ValidationError`/`ValidationWarning`/`LatencyBucket` |
| `frontend/src/components/validation/ValidationPanel.tsx` | Logic adaptation — read `data.results`, filter by `type`, drop `data.valid`/`data.errors`/`data.warnings` references |
| `frontend/src/components/validation/TimingReportView.tsx` | Shape fix — replace frontend `TimingReport` fields with real backend fields (`coroutineDurations`, `suspensionDurations`, `totalDuration`) |

The `use-validation.ts` hook changes only if the return type annotation references `ValidationResult` — the runtime logic is unchanged.

---

## Design System

| Property | Value | Source |
|----------|-------|--------|
| Tool | none (HeroUI, not shadcn) | `tailwind.config.js` — `heroui()` plugin detected |
| Preset | not applicable | No `components.json`; HeroUI v2.6.8 governs |
| Component library | HeroUI v2.6.8 (`@heroui/react`) | `package.json` — `"@heroui/react": "^2.6.8"` |
| Icon library | react-icons/fi (Feather icons) | Existing `ValidationResultCard.tsx` imports |
| Font | System default (HeroUI theme default) | No custom `fontFamily` token in `tailwind.config.js` |

**shadcn gate result:** Not applicable. Project uses HeroUI, not shadcn. No `components.json` present and no React/Next.js shadcn stack applies here.

---

## Spacing Scale

All values are HeroUI/Tailwind defaults. FIX-02 MUST use the same spacing as the existing validation components — no new spacing tokens.

| Token | Value | Usage in validation components |
|-------|-------|-------------------------------|
| xs | 4px | Icon gaps (`gap-1`), tight inline padding |
| sm | 8px | Card body row gaps (`gap-2`), chip spacing |
| md | 16px | Default section spacing (`space-y-4`) |
| lg | 24px | Not used in validation; session-level layout |
| xl | 32px | Not used in FIX-02 scope |
| 2xl | 48px | Empty-state vertical padding (`py-8` = 32px exception — see below) |
| 3xl | 64px | Not used in FIX-02 scope |

**Exceptions:**
- Empty state uses `py-8` (32px vertical) — matches existing `ValidationPanel.tsx:133`. Do not change.
- Result cards use `gap-3` (12px) for icon-to-text alignment — this is an established HeroUI CardBody pattern in the existing code. Do not change.

---

## Typography

All sizes are Tailwind utility classes resolved by HeroUI theme. FIX-02 must match the exact classes already used in `ValidationResultCard.tsx` and `ValidationPanel.tsx`.

| Role | Tailwind class | Effective size | Weight | Line Height | Usage |
|------|---------------|---------------|--------|-------------|-------|
| Body | `text-sm` | 14px | 400 (normal) | 1.25 (HeroUI default) | Rule messages, card descriptions |
| Label | `text-xs` | 12px | 400 (normal) | 1.33 | Metadata (coroutineId, timing labels, rule counts) |
| Section heading | `text-sm font-semibold` | 14px | 600 (semibold) | 1.25 | "Failures (N)", "Passes (N)", "Timing Report" |
| Card heading | `font-semibold` | 16px (inherits CardBody) | 600 (semibold) | 1.5 | "Session Validation" header card |
| Timing stat value | `text-lg font-semibold` | 18px | 600 (semibold) | 1.75 | Per-coroutine duration stat values |
| Mono | `font-mono` | 14px | 400 (normal) | 1.25 | Coroutine IDs, duration values, timing keys |

**Declared weights:** exactly 2 — 400 (normal) and 600 (semibold). Weight 700 (bold) is not used in this phase.

**Rule:** No new type sizes may be introduced. The spec declares exactly these roles; the executor must not add sizes outside this set.

---

## Color

HeroUI semantic tokens govern all color. FIX-02 must use the same token set as the existing components.

| Role | HeroUI token | Hex (light theme) | Usage |
|------|-------------|------------------|-------|
| Dominant (60%) | `bg-default` / `bg-background` | `#ffffff` (light) / `#000000` (dark) | Page background, card surfaces |
| Secondary (30%) | `bg-default-100` | `#f4f4f5` (light) | Card interiors, bar chart track (`bg-default-100`) |
| Accent — primary (10%) | `text-primary` / `bg-primary` | `#6366f1` (light) / `#818cf8` (dark) | Bar fill in TimingReportView, primary button |
| Semantic — success | `text-success` / `border-success/30` | `#17c964` | Validation Passed state, FiCheckCircle icon |
| Semantic — danger | `text-danger` / `border-danger/30` | `#f31260` | Failures section heading, FiXCircle, Fail result cards |
| Semantic — warning | `text-warning` | `#f5a524` | Reserved for future use only — NO Warning section in FIX-02 (backend has no Warning type) |
| Muted text | `text-default-400` / `text-default-500` / `text-default-600` | Grays | Metadata, subtitles, section headings (non-semantic) |
| Body text | `text-default-700` | Near-black | Rule messages, card content |

**Accent reserved for:** primary CTA button ("Run Validation"), progress bar fill in timing duration bars, primary chip highlights. NOT for headings or section labels.

**Warning token usage:** The existing `ValidationWarningCard` component (HeroUI `color="warning"`) is rendered DEAD by FIX-02 — the backend emits no Warning type. The `ValidationWarningCard` export stays in the file (no deletion) but `ValidationPanel.tsx` removes all references to it and the Warnings section. `text-warning` must NOT appear in the adapted ValidationPanel.

---

## Backend Contract (locked — FIX-02 renders this)

The following shapes are the canonical backend response. The frontend MUST adapt to these; backend does NOT change.

### ValidationResponse (from `POST /api/validate/session/{id}`)

```typescript
interface ValidationResponse {
  sessionId: string
  results: Array<ValidationRuleResult>
  timing: BackendTimingReport
}

interface ValidationRuleResult {
  type: 'Pass' | 'Fail'   // sealed class discriminator — NO 'Warning' variant exists
  ruleName: string
  message: string
  details?: string         // present on Fail only
}

interface BackendTimingReport {
  coroutineDurations: Record<string, number>   // Map<coroutineId, durationMs>
  suspensionDurations: Record<string, number[]> // Map<coroutineId, List<durationMs>>
  totalDuration: number    // milliseconds
}
```

**What changes in `api.ts`:**
- Replace `ValidationResult` interface with `ValidationResponse` (or rename in-place, keeping `sessionId`)
- Replace `ValidationError` / `ValidationWarning` / `LatencyBucket` / `TimingReport` with the three interfaces above
- Type exported as `ValidationResponse` (rename from `ValidationResult`) so `use-validation.ts` import updates accordingly

---

## Component Adaptation Contract

**Primary visual anchor:** the summary card (`data-testid="validation-summary"`) — established by spring-scale animation and glow pulse, renders before all other result sections.

### ValidationPanel.tsx — adapted logic

1. **Summary card** (`data-testid="validation-summary"`): derive `failCount = data.results.filter(r => r.type === 'Fail').length`. Summary is "All rules passed" when `failCount === 0`, "N rule(s) failed" otherwise.
2. **Failures section** (replaces "Errors" section): render `data.results.filter(r => r.type === 'Fail')`. Section heading: `text-sm font-semibold text-danger` — "Failures (N)". Use existing `ValidationErrorCard` for each item, passing `ruleName` as the displayed code and `message` as the displayed message. Drop `error.code`, `error.eventSeq`, `error.coroutineId` references (those fields no longer exist).
3. **Passes section** (replaces "Warnings" section): render `data.results.filter(r => r.type === 'Pass')`. Section heading: `text-sm font-semibold text-success` — "Passes (N)". Use existing `ValidationPassCard` for each item (or a simpler inline list — see §Passes rendering below). Drop `ValidationWarningCard` usage entirely.
4. **Warnings section**: REMOVED. The backend has no Warning type. Do not render a warnings section.
5. **Timing section**: retained, passes `data.timing` (the real `BackendTimingReport`) to `TimingReportView`.

**Passes rendering decision:** Rendering each individual Pass as a `ValidationPassCard` produces 21 cards for a full session (one per rule). Use a collapsed list instead:
- If `passCount === 0`, omit the passes section entirely.
- If `passCount > 0`, render a single compact list with `text-xs text-default-500` rows, each showing `ruleName` prefixed by `FiCheckCircle className="text-success"` (size 12). No individual cards per pass.
- Collapsed into `space-y-1` list, no animated glow per row.

### ValidationResultCard.tsx — field mapping

`ValidationErrorCard` is reused for Fail results with field remapping:

| Old prop field | New prop field | Display |
|----------------|----------------|---------|
| `error.code` | `result.ruleName` | Chip `color="danger"` |
| `error.message` | `result.message` | `text-sm text-default-700` |
| `error.details` | `result.details` | `text-xs text-default-500` (only when present) |
| `error.eventSeq` | (removed) | Do not render |
| `error.coroutineId` | (removed) | Do not render |

The `ValidationErrorCard` interface changes from `ValidationError` to the new `ValidationRuleResult` (Fail variant only). The `ValidationWarningCard` interface is not updated (component becomes unused but stays in file).

The animated pulse (`boxShadow` keyframe) on `ValidationErrorCard` is retained unchanged.

### ValidationPassCard.tsx — summary card adaptation

`ValidationPassCard` is adapted to derive state from `failCount` rather than `valid`:

| Old prop | New prop | Derivation |
|----------|----------|-----------|
| `valid: boolean` | `failCount: number` | `valid = failCount === 0` |
| `errorCount: number` | `failCount: number` | direct |
| `warningCount: number` | (removed) | Drop from interface |

Display text:
- All passed: "All Rules Passed" (replaces "Validation Passed")
- Some failed: "N Rule(s) Failed" (replaces "Validation Failed" + "N errors")
- Sub-label when all passed: `text-xs text-default-500` — "N rules checked"
- Sub-label when failed: `text-xs text-default-500` — "N of M rules failed"

### TimingReportView.tsx — shape fix

Replace the four-stat grid (`totalDurationNanos`, `eventCount`, `coroutineCount`, `maxGapNanos`) with the real backend shape:

**New layout — two sections:**

1. **Total Duration card** — single `Card shadow="sm"` with:
   - `text-lg font-semibold text-primary` — formatted `totalDuration` value
   - `text-xs text-default-500` — "Total Duration"
   - `formatMs(ms)` helper: `>= 1000` → `X.XXs`, else `Xms`

2. **Per-coroutine durations table** — if `coroutineDurations` is non-empty:
   - Section heading: `text-sm font-semibold text-default-600` — "Coroutine Durations"
   - One row per coroutine ID entry in `Object.entries(coroutineDurations)`, sorted descending by duration
   - Row: `font-mono text-xs text-default-700` for coroutine ID + `text-xs text-primary` for duration value
   - Rendered as a horizontal bar chart: same `bg-default-100` track + `bg-primary` fill pattern as existing `suspendResumeLatencies` chart (reuse the existing bar rendering logic)

3. **Per-coroutine suspension durations** — if `suspensionDurations` is non-empty:
   - Section heading: `text-sm font-semibold text-default-600` — "Suspension Durations"
   - One row per coroutine in `suspensionDurations` showing count of suspensions + max suspension value
   - Row: `font-mono text-xs text-default-700` for coroutine ID + `text-xs text-default-500` — "N suspension(s), max: Xms"

Remove: all references to `totalDurationNanos`, `eventCount`, `coroutineCount`, `avgEventIntervalNanos`, `maxGapNanos`, `suspendResumeLatencies`, `LatencyBucket`.

The `formatNanos()` helper is replaced by `formatMs(ms: number): string` since `BackendTimingReport` values are in milliseconds, not nanoseconds. Function signature: returns `>= 1000` → `"X.XXs"`, else `"Xms"`.

---

## Copywriting Contract

| Element | Copy | Source |
|---------|------|--------|
| Primary CTA | "Run Validation" | Existing `ValidationPanel.tsx:44` — do NOT change |
| Loading state label | "Running..." | Existing `ValidationPanel.tsx:44` — do NOT change |
| Empty state | "Click \"Run Validation\" to analyze this session for issues." | Existing `ValidationPanel.tsx:134` — do NOT change |
| Summary — all passed | "All Rules Passed" | New (replaces "Validation Passed") |
| Summary sub — all passed | "N rules checked" | New |
| Summary — failures | "N Rule(s) Failed" | New (replaces "Validation Failed") |
| Summary sub — failures | "N of M rules failed" | New |
| Failures section heading | "Failures (N)" | New (replaces "Errors (N)") |
| Passes section heading | "Passes (N)" | New (replaces "Warnings (N)") |
| Timing section heading | "Timing Report" | Existing `ValidationPanel.tsx:116` — do NOT change |
| Timing sub-section: coroutines | "Coroutine Durations" | New |
| Timing sub-section: suspensions | "Suspension Durations" | New |
| Error state | "Validation failed: {error.message or 'Unknown error'}" | Existing `ValidationPanel.tsx:62` — do NOT change |
| Timing total label | "Total Duration" | New |

**Rule:** Copy strings that are identical to the existing file are preserved verbatim. New copy strings are introduced only where the old field (`errors`, `warnings`, `valid`) is being replaced.

**Destructive actions in FIX-02 scope:** None. "Run Validation" is an idempotent read-like action. No confirmation dialogs are needed.

---

## Interaction Contract

### States to implement

| State | Trigger | Rendered by |
|-------|---------|------------|
| Idle (no data) | Initial mount | `ValidationPanel` empty block (`data-testid="validation-empty"`) |
| Loading | `isLoading === true` | Button `isLoading` prop + "Running..." label |
| Error | `isError === true` | Animated `Card border border-danger/30` with `data-testid="validation-error"` |
| Results — all passed | `failCount === 0` | Summary card (success glow) + compact passes list + timing |
| Results — some failed | `failCount > 0` | Summary card (danger glow) + failures section + passes list + timing |

### Animation contract

All existing `framer-motion` animations are RETAINED without modification:
- `motion.div` enter/exit on the overall panel (`opacity: 0 → 1`)
- `AnimatePresence mode="wait"` on the results block
- Spring scale on summary card (`stiffness: 300, damping: 25`)
- Staggered slide-in on failure cards (`delay: index * 0.05`)
- Animated glow pulse on summary card and failure cards (`boxShadow` keyframe, `duration: 2, repeat: Infinity`)
- Bar fill animation on timing chart (`duration: 0.8, ease: 'easeOut'`)

New elements (compact passes list, timing table rows) use `opacity: 0 → 1` with `duration: 0.3` — consistent with the existing fade pattern.

### Test contract

`ValidationPanel.test.tsx` mock fixture must be updated to the real backend shape:

```typescript
// New fixture shape (replaces old `ValidationResult` mock)
const result: ValidationResponse = {
  sessionId: 'session-1',
  results: [
    { type: 'Fail', ruleName: 'NO_ORPHAN_EVENTS', message: 'Event 5 has no parent' },
    { type: 'Pass', ruleName: 'LIFECYCLE_ORDER', message: 'All lifecycle events in order' },
  ],
  timing: {
    coroutineDurations: { 'main-coroutine': 150, 'child-1': 80 },
    suspensionDurations: { 'main-coroutine': [10, 25, 5] },
    totalDuration: 230,
  },
}
```

Existing `data-testid` anchors that must be preserved (checker verifies these):
- `data-testid="validation-panel"` — root container
- `data-testid="run-validation-btn"` — CTA button
- `data-testid="validation-empty"` — idle state
- `data-testid="validation-summary"` — summary card
- `data-testid="validation-error"` — network/API error
- `data-testid="validation-error-card"` — individual Fail result card
- `data-testid="timing-report"` — timing section root

Removed test anchors (delete from tests):
- `data-testid="validation-warning-card"` — Warnings removed
- `data-testid="latency-bucket"` — `suspendResumeLatencies` removed from TimingReport shape

New test anchor required:
- `data-testid="timing-coroutine-row"` — one per entry in `coroutineDurations`

---

## Registry Safety

No shadcn registry in use. HeroUI v2.6.8 components are sourced from `@heroui/react` npm package.

| Registry | Blocks Used | Safety Gate |
|----------|-------------|-------------|
| npm / @heroui/react | Card, CardBody, Button, Chip, Divider | npm package — no shadcn registry vetting applicable |
| npm / react-icons | FiPlay, FiCheckCircle, FiXCircle, FiAlertTriangle | npm package — no shadcn registry vetting applicable |
| shadcn official | none | not applicable |

No third-party shadcn registries are introduced by this phase.

---

## Out of Scope (explicit)

- New pages or routes
- Session view layout changes
- `ValidationDashboard` / `validation-dashboard/` components (different subsystem, untouched)
- `ValidationWarningCard` component deletion (leave in file, remove references only)
- Dark mode overrides (HeroUI `darkMode: "class"` governs automatically — no per-component dark mode work needed)
- Accessibility annotations beyond existing HeroUI defaults

---

## Checker Sign-Off

- [ ] Dimension 1 Copywriting: PASS
- [ ] Dimension 2 Visuals: PASS
- [ ] Dimension 3 Color: PASS
- [ ] Dimension 4 Typography: PASS
- [ ] Dimension 5 Spacing: PASS
- [ ] Dimension 6 Registry Safety: PASS

**Approval:** pending
