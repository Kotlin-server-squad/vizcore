---
created: 2026-06-12
source: 01-REVIEW triage round (backend fixer follow-up note)
severity: warning
---
# backend/src/main/ FQCN forks of core: events/ AND checksystem/

Same FQCN-fork hazard FND-01 (session/) and 01-07 (wrappers/) eliminated: backend/src/main/kotlin/com/jh/proj/coroutineviz/events/ largely duplicates coroutine-viz-core's events package on one classpath. Reconcile into core and extend ForkDeletionTest to guard events/ — coordinate with shared/api-types regeneration.

**Also (from 01-SECURITY audit UF-01):** backend/src/main/.../checksystem/TimingAnalyzer.kt duplicates core checksystem/TimingAnalyzer.kt FQCN — and the ns→ms fix (T-01-10-01) lives only in the backend copy, so adverse fat-jar classloader ordering could silently undo it with no failing test. Reconcile both packages into core and extend ForkDeletionTest to guard events/ and checksystem/.
