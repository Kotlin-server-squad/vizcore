---
title: Monorepo consolidation verified (vizcor-be + vizcor-fe → vizcore)
date: 2026-06-10
context: exploration / consolidation check
---

# Monorepo consolidation verified

Verified on **2026-06-10** that the standalone `vizcor-be` and `vizcor-fe`
repositories are **fully represented** in the `vizcore` monorepo and that the
monorepo is the advanced, active line of development. Nothing was lost.

## Repositories

| Repo | GitHub | Role | Last commit (at check) |
|------|--------|------|------------------------|
| `vizcor-be` | `hermanngeorge15/vizcor-be` | standalone backend (retire) | 2026-03-16 |
| `vizcor-fe` | `hermanngeorge15/vizcor-fe` | standalone frontend (retire) | 2026-03-16 |
| `vizcore`   | `Kotlin-server-squad/vizcore` | **monorepo (source of truth)** | 2026-03-19 |

## Evidence

**Backend** (`vizcor-be/src` vs `vizcore/backend/src`)
- Kotlin files: standalone **83** → monorepo **110**
- Files only in standalone (potential loss): **0**
- Net-new in monorepo: `Auth.kt`, `Compression.kt`, full `events/channel/*`,
  `checksystem/*` validators (EventSelector, HierarchyValidator, LifecycleValidator,
  StructuredConcurrencyValidator, TimingAnalyzer, ValidationResult), `MetricsWiring.kt`,
  extra routes (Comparison, FlowScenario, Health, Pattern, Validation), `scenarios/*`,
  `session/ChannelEventContext.kt`, `wrappers/InstrumentedChannel.kt`.

**Frontend** (`vizcor-fe/src` vs `vizcore/frontend/src`)
- Source files: standalone **52** → monorepo **151**
- Files only in standalone (potential loss): **0**
- Net-new in monorepo: **99** files.

**Content / direction check** (overlapping files differ because the monorepo moved
forward, not because of divergence):

| File | standalone lines | monorepo lines |
|------|-----------------:|---------------:|
| `Application.kt` | 14 | 16 |
| `Routing.kt` | 23 | 36 |
| `lib/api-client.ts` | 149 | 167 |
| `types/api.ts` | 346 | 842 |
| `components/CoroutineTree.tsx` | 424 | 374 |

`CoroutineTree.tsx` is the only file that shrank — logic was refactored out into the
new `EnhancedCoroutineTreeNode.tsx` and `CoroutineTreeGraph.tsx` (monorepo-only), so it
is a refactor, not a regression.

## Conclusion

- ✅ Backend fully consolidated (monorepo is a strict superset + newer).
- ✅ Frontend fully consolidated (monorepo is a strict superset + newer).
- ✅ Monorepo is the single source of truth; standalones are safe to retire.

## Redirect caveat

A true GitHub auto-redirect only happens when a repo is **renamed or transferred**
within the same owner's reach. Because the standalones live under `hermanngeorge15`
and the monorepo lives under the `Kotlin-server-squad` org, no automatic redirect is
possible. Retirement = **archive** each standalone + add a README banner pointing to
`Kotlin-server-squad/vizcore`. See todo: `retire-standalone-repos`.

## Next step (separate session, run from inside `vizcore/`)

Deep-dive audit of the monorepo, feature-by-feature, on Opus 4.8. Suggested entry:
`/gsd-ingest-docs` (bootstrap `.planning/` from existing design docs) → `/gsd-map-codebase`
→ audit milestone/roadmap.
