# Requirements (from PRDs)

Synthesized from two business-analysis PRDs. IDs derived as `REQ-{slug}`. Where two PRDs define the same requirement scope with divergent acceptance criteria, **both variants are preserved** and the requirement is flagged as a competing variant (see INGEST-CONFLICTS.md → WARNINGS). Do not pre-merge these downstream.

Sources:
- PRD-A = docs/planning/BUSINESS_ANALYSIS_V2.md ("Business Analysis: Kotlin Coroutines Visualizer Platform")
- PRD-B = docs/COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md ("Deep Dive Business Analysis")

---

## REQ-core-visualization
- source: PRD-A, PRD-B
- description: A tool that makes invisible coroutine execution visible — real-time, interactive visualizations of lifecycle, structured concurrency, dispatcher behavior, flow/channel primitives, and anti-patterns, by non-invasively wrapping `kotlinx-coroutines-core`.
- acceptance: Multi-view perspectives (timeline, tree, graph, event log); wraps the official library without modification; captures actual behavior, not simulations; event-sourced for time-travel/replay.
- scope: product core / visualization engine

## REQ-teaching-scenarios
- source: PRD-A, PRD-B
- description: A library of pre-built teaching scenarios demonstrating common coroutine patterns and anti-patterns (race condition, starvation, cancellation, flow backpressure, deadlock, etc.).
- acceptance: PRD-B targets 50+ community scenarios in year 1; built-in scenarios runnable via API; users can author custom scenarios.
- scope: scenarios / education

## REQ-target-segments
- source: PRD-A, PRD-B
- description: Serve four audiences — independent/learning developers, online educators/content creators, enterprise training programs, and IDE-tool partnership (JetBrains).
- acceptance: Value proposition articulated per segment; personas defined (Alex/Morgan/Taylor in PRD-A).
- scope: market / personas

## REQ-export-and-sharing
- source: PRD-A, PRD-B
- description: Export visualizations (PNG/SVG/video) and share read-only sessions — positioned as premium/team capabilities.
- acceptance: Export and shareable demos cited as premium-tier features; aligns with ADR-018 (export) and ADR-019 (sharing) on the technical side.
- scope: premium features

## REQ-ide-plugin
- source: PRD-A, PRD-B
- description: IDE plugin for IntelliJ IDEA / Android Studio providing a native integrated experience (a scale/partnership-phase deliverable).
- acceptance: Plugin listed as v2.0 / scale-phase deliverable; aligns with ADR-010/ADR-014 (currently Proposed).
- scope: product expansion

---

## REQ-business-model  ⚠ COMPETING VARIANTS — DO NOT MERGE
- scope: business model / pricing tiers / license
- See INGEST-CONFLICTS.md → WARNINGS for the resolution gate. Both variants retained verbatim:

  ### Variant A — source: PRD-A (BUSINESS_ANALYSIS_V2.md §4.1)
  - Recommended model: Freemium open source.
  - Free tier license: **Apache 2.0**.
  - Premium tier: **$29/month or $249/year** (advanced wrappers, custom scenario builder, export, hosted sharing, priority support, commercial license).
  - Enterprise tier: **$999–9,999/year** (private deployment, custom branding, team analytics, SLA).

  ### Variant B — source: PRD-B (COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md §10.3)
  - Open-source foundation license: **MIT** for the core framework.
  - Individual: **Free** (web UI, 10 scenarios, community support).
  - Team: **$49/month** (scenario sharing, team collaboration, priority support).
  - Enterprise: **$499/month** (on-premise, custom scenarios, SSO, SLA).

  - divergences: free-tier license (Apache 2.0 vs MIT); tier names (Premium vs Team); price points ($29/mo vs $49/mo; $999–9,999/yr vs $499/mo enterprise).
  - note: ADR-021 independently declares the published SDK artifact license as MIT (POM) — this is the library artifact license, distinct from the product/platform free-tier license under dispute here.

---

## REQ-success-metrics-kpis  ⚠ COMPETING VARIANTS — DO NOT MERGE
- scope: success metrics / KPIs
- See INGEST-CONFLICTS.md → WARNINGS for the resolution gate. Both variants retained verbatim:

  ### Variant A — source: PRD-A (BUSINESS_ANALYSIS_V2.md §7)
  - Product: avg session 15+ min; 5+ scenarios/session; 40% 7-day return; API p95 < 200ms; event processing < 10ms; render < 1s/1000 events; uptime 99.5%+.
  - Adoption/conversion: Free→Premium trial 10%; Trial→Paid 25%.
  - Business: MRR growth 15%+ MoM; monthly churn < 5%; NPS 50+; LTV:CAC 3:1.
  - Community: 1K+ GitHub stars (early); 5K+ free users (year 1).

  ### Variant B — source: PRD-B (COROUTINE-VISUALIZER-BUSINESS-ANALYSIS.md §10.4)
  - Adoption: GitHub stars 1,000+ in 6 months; MAU 5,000+ in year 1; 3+ training partners.
  - Engagement: avg session 15+ min; scenario completion 80%+ finish rate; 20+ community scenarios.
  - Business: paid conversion **5% of active users**; enterprise deals 2+ in year 1; 10+ conference talks/workshops in year 1.

  - divergences: paid-conversion definition/target (10% trial + 25% trial→paid funnel vs flat 5% of active users); engagement metric set (return-rate/scenarios-per-session vs completion-rate); business-health framing (MRR/churn/NPS/LTV:CAC vs partner/conversion/talk counts).
