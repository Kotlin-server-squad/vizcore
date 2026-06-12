---
created: 2026-06-12
source: 01-REVIEW triage round (backend fixer follow-up note)
severity: warning
---
# backend/src/main/.../events/ duplicates core's events package

Same FQCN-fork hazard FND-01 (session/) and 01-07 (wrappers/) eliminated: backend/src/main/kotlin/com/jh/proj/coroutineviz/events/ largely duplicates coroutine-viz-core's events package on one classpath. Reconcile into core and extend ForkDeletionTest to guard events/ — coordinate with shared/api-types regeneration.
