# SPA Redesign — Handoff

**Read this first on a cold start.** Updated after every completed plan.

**Last updated:** 2026-08-24, after plan 3 (fidelity rung + locked panels).

---

## What this work is

A five-sub-project redesign of the vizcore frontend, driven by one finding: the
product has two identities (a coroutine teaching lab and a real-app debugger)
and the SPA's navigation served the wrong one. The spine is a **fidelity
ladder** — demo / attached / instrumented — and the rung is set by how much of
their own code the developer changed.

- **Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — read this before anything else. Eight decisions (D-1..D-8), each with the rejected alternative.
- **Plans:** `docs/superpowers/plans/2026-08-2*.md`, one per executed step.

## Branch stack (bottom to top)

| Branch | Contains | Pushed? |
|---|---|---|
| `main` | — | — |
| `feat/spa-design-tokens` | sub-project 2: token layer | **PR #102**, open, unreviewed |
| `feat/spa-shell-ia` | plans 1–3: IA, state bar, rung + locked panels | local only |
| `feat/intellij-plugin-native-redesign` | the user's Phase-15 work, plus one redesign commit `fd479d0` | local only |

A review that changes the token layer means rebasing the branches above it.

## Done

1. **Token layer** — `src/styles/palette.ts` is the single source of colour, feeding `tokens.css`, the HeroUI theme in `tailwind.config.ts`. Dark-first actually on (the `dark` class was configured since day one and never set). Inter + JetBrains Mono self-hosted via `@fontsource`.
2. **Shell & IA** — sessions list is the root route, navbar is brand-only, `/scenarios` and `/gallery` redirect, demo-creation and Compare re-hosted into the sessions home as modals.
3. **State bar** — `src/lib/state-counts.ts` + `StateBar.tsx`. Chips show per-state counts and filter the canvas. Includes the potential-leak chip.
4. **Fidelity rung + locked panels** — `src/lib/fidelity-rung.ts` + `LockedPanel.tsx`. Every session badges its rung; a wrapper-only capability absent on a real session renders an invitation naming the unlock.

## Next: plan 4 — decompose `SessionDetails` and migrate the tabs

`SessionDetails.tsx` is ~990 lines and still owns eight tabs. The spec calls
this the highest-risk change in the redesign.

Suggested order: **port the tests first**, decompose behind the existing tabs,
verify nothing moved, *then* migrate the tabs. There are ~35 `SessionDetails`
tests to carry across.

Target from the spec: `SessionWorkspace` (layout + selection only),
`SessionHeader`, `MetricTiles`, `StateBar` (done), `CoroutineCanvas`,
`Inspector/` cards, `EvidencePanels/`, `LockedPanel` (done).

Tab destinations: Events → selection-scoped drawer; Threads → tiles (aggregate)
+ inspector "runs on" (per-coroutine); Channels/Flow/Sync/Jobs → evidence panels
under the inspector; Validation → folded into the state bar.

Then sub-project 4 (move `ProblemDerivation.kt` into `coroutine-viz-core` and
expose it on the session API, so the plugin and SPA stop computing the same
domain twice) and sub-project 5 (align the plugin).

## Decisions that are settled — do not relitigate

- **Option A (problems-first triage) with B's locked-question idea grafted in.** The question rail was rejected on cost; the plugin's own 004-D problems strip was widened into a *state* bar because a problems-only strip opens on zeroes for a demo session.
- **One frame for all three rungs.** The demo user is the same person earlier, not a different audience.
- **`WAITING_FOR_CHILDREN` counts as running**; **`CANCELLED` gets its own bucket** — folding it into completed reports a cancellation as success, into failed as an error. It is neither.
- **`hasJobs` does not promote a session to instrumented.** Job events are not confirmed wrapper-only; over-reporting the rung promises panels the session cannot fill.
- **A potential leak is amber, never red.**

## Carried debt (recorded in the spec, do in plan 4+)

- **C-1 — `secondary` has no vizcore meaning but 79 live references.** Neutralising the token flattens `WAITING_FOR_CHILDREN` (which `coroutine-state-colors` maps onto it) into the same grey as CREATED and CANCELLED. Retiring it is a per-call-site decision.
- **C-2 — `coroutine-state-colors.ts` is not palette-backed** and returns only Tailwind classes, so it cannot serve canvas/SVG. Extend that one module rather than adding a second beside it.
- Four hardcoded `#6366f1` remain in `FlowParticlePath.tsx` and `animation-variants.ts` — a five-colour flow-operator scheme the palette does not define.
- `/scenarios/builder` (410-line `ScenarioBuilder`) still resolves; its fate is an open question in the spec.

## Harness gotchas — these cost real time

- **CORS.** The backend allowlists `localhost:3000` only. Run it as `CORS_ALLOWED_ORIGINS=http://localhost:<devport> PORT=8085 ./gradlew run`, or POSTs return **403** while curl gets 201 — which reads like a broken UI.
- **Killing the backend.** `pkill -f "gradlew run"` does **not** reach the Gradle-spawned JVM. Kill by PID from `lsof -nP -iTCP:8085 -sTCP:LISTEN -t`, or a stale backend keeps serving and you debug a phantom.
- **JDK 21** for Gradle: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- **Ports 3000 and 8080** are usually taken on this machine; use 3103+/8085.
- **`frontend/node_modules` may be absent** — run `pnpm install` first.
- The Chrome tool **blocks reading query strings**; screenshot instead of reading `location.search`.

## Working agreements that have paid off

- **Always run the app.** Three real defects were found in the live app that the test suite could not catch: filtered states hidden behind the empty state, a purple `secondary` leak across ~15 components, and `LivePill` labelling a paused stream as "DEMO" against an ATTACHED badge.
- **Mutation-test any test written after its implementation.** Break the logic, confirm the test fails, restore. Done twice; both times it proved the tests real.
- **Check for a pre-existing module before writing a new one.** A duplicate `state-color.ts` was created alongside `coroutine-state-colors.ts` and had to be reverted.
