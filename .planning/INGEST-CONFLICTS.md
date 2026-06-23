## Conflict Detection Report

Mode: new. Precedence: ADR > SPEC > PRD > DOC. 31 docs synthesized (27 ADR, 2 PRD, 1 SPEC, 1 DOC).

### BLOCKERS (0)

None. The three prior blockers were resolved before this re-run:
- Persistence contradiction (ADR-009 vs ADR-015) reconciled in source — see intel/context.md.
- Cross-ref cycle ADR-017 ↔ ADR-018 broken (one-way 018 → 017 retained).
- Cross-ref cycle ADR-016 ↔ ADR-019 broken (one-way 019 → 016 retained).
DFS three-color cycle detection over the full ADR dependency graph returned no cycles. No UNKNOWN / low-confidence classifications. No LOCKED-vs-LOCKED contradictions.

### WARNINGS (2)

[WARNING] Competing business-model / pricing variants for REQ-business-model
  Found: docs/planning/BUSINESS_ANALYSIS_V2.md proposes a freemium model with an Apache-2.0 free tier, Premium $29/mo (or $249/yr), and Enterprise $999-9,999/yr.
  Found: docs/COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md proposes an MIT-licensed core with Individual (free), Team $49/mo, and Enterprise $499/mo.
  Impact: The two PRDs disagree on free-tier license (Apache 2.0 vs MIT), tier naming (Premium vs Team), and price points. Synthesis cannot pick a winner without losing intent; both are equal-precedence PRDs (no per-doc override). Note: ADR-021 separately sets the published SDK artifact license to MIT in its POM — that governs the library artifact, not the product free-tier license under dispute.
  → Choose one pricing/license model (or explicitly split product-platform licensing from SDK-artifact licensing) before routing. Both variants preserved verbatim in intel/requirements.md → REQ-business-model.

[WARNING] Competing success-metric / KPI variants for REQ-success-metrics-kpis
  Found: docs/planning/BUSINESS_ANALYSIS_V2.md defines a conversion funnel (Free→Premium trial 10%, Trial→Paid 25%), MRR growth 15%+ MoM, churn < 5%, NPS 50+, LTV:CAC 3:1, plus product latency/uptime targets.
  Found: docs/COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md defines flat paid conversion of 5% of active users, scenario completion 80%+, GitHub 1,000+ stars in 6 months, MAU 5,000+ year 1, 3+ training partners, 10+ talks/year.
  Impact: The KPI sets disagree on the paid-conversion definition (multi-stage funnel vs flat 5%) and on which engagement/business metrics are authoritative. Synthesis cannot reconcile without picking a target.
  → Choose one KPI set (or merge into a single agreed scorecard) before routing. Both variants preserved verbatim in intel/requirements.md → REQ-success-metrics-kpis.

### INFO (3)

[INFO] Persistence reconciliation recorded
  Note: docs/adr/009-deployment-strategy.md (Accepted) and docs/adr/015-persistence-strategy.md (Accepted) were previously contradictory and are now reconciled — ADR-009 governs the current in-memory/ephemeral deployment; ADR-015 defines an optional, Accepted-but-unimplemented persistence seam. Code-verified: no Exposed/PostgreSQL/JDBC/H2 in the repo. Captured in intel/context.md and noted on the ADR-009/ADR-015 entries in intel/decisions.md.

[INFO] ADR-010 is Proposed, not locked
  Note: docs/adr/010-intellij-plugin-architecture.md has status Proposed, so it is the only non-locked ADR. Its decisions are advisory; downstream should not treat the IntelliJ plugin architecture as a locked commitment. All other 26 ADRs are Accepted/locked.

[INFO] SPEC aligns with ADR-015 persistence seam (no contradiction)
  Note: docs/planning/IMPLEMENTATION_ANALYSIS.md (SPEC) prescribes both the in-memory `*StoreInterface` seam and the optional JDBC/Exposed/Flyway implementation. This matches ADR-015's design rather than contradicting any higher-precedence ADR, so no auto-resolution was needed. The JDBC files it references are unimplemented targets (build-when-implementing-ADR-015), flagged in intel/constraints.md (CON-jdbc-persistence). Separately, the deep-dive PRD's WebSocket transport references are treated as superseded background; the decided transport is SSE (ADR-002 / CLAUDE.md).
