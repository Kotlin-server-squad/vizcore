# Documentation

## Guides

- [Setup](guides/SETUP.md) — Local development setup, prerequisites, troubleshooting
- [User Guide](guides/USER_GUIDE.md) — How to use the visualizer, panels, scenarios
- [API Reference](guides/API_REFERENCE.md) — REST endpoints, SSE streams, request/response examples
- [Deployment](guides/DEPLOYMENT.md) — Production deployment, Docker, monitoring, scaling
- [IntelliJ Plugin Guide](guides/INTELLIJ_PLUGIN_GUIDE.md) — Building the IntelliJ plugin *(postponed — not part of v1.2)*
- [IntelliJ Plugin Integration](guides/INTELLIJ_PLUGIN_INTEGRATION.md) — Hybrid VizSession + DebugProbes architecture *(postponed — not part of v1.2)*

> **Postponed — not part of v1.2.**
> The IntelliJ plugin is parked (#130). It is excluded from the default Gradle build and is
> only configured when a build opts in with `-Pvizcore.withPlugin=true`; CI builds it on
> demand from `.github/workflows/ci-plugin.yml` (`workflow_dispatch`) only. The two plugin
> guides above, [ADR-010](adr/010-intellij-plugin-architecture.md),
> [ADR-014](adr/014-plugin-communication-protocol.md) and the
> [distribution runbook](guides/intellij-plugin-distribution.md) describe work that resumes
> after v1.2 — none of it is shipped or supported in v1.2.

## Architecture Decision Records

- [ADR-001](adr/001-monorepo-architecture.md) — Monorepo Architecture
- [ADR-002](adr/002-shared-api-contract.md) — Shared API Contract
- [ADR-003](adr/003-test-strategy.md) — Test Strategy
- [ADR-004](adr/004-ci-cd-pipeline.md) — CI/CD Pipeline
- [ADR-005](adr/005-docker-development.md) — Docker Development
- [ADR-006](adr/006-pr-consolidation.md) — PR Consolidation
- [ADR-007](adr/007-frontend-visualization-expansion.md) — Frontend Visualization Expansion
- [ADR-008](adr/008-shared-types-migration.md) — Shared Types Migration
- [ADR-009](adr/009-deployment-strategy.md) — Deployment Strategy
- [ADR-010](adr/010-intellij-plugin-architecture.md) — IntelliJ Plugin Architecture
- [ADR-011](adr/011-animation-system-design.md) — Animation System Design
- [ADR-012](adr/012-validation-engine-architecture.md) — Validation Engine Architecture
- [ADR-013](adr/013-core-library-extraction.md) — Core Library Extraction
- [ADR-014](adr/014-plugin-communication-protocol.md) — Plugin Communication Protocol
- [ADR-015](adr/015-persistence-strategy.md) — Persistence Strategy
- [ADR-016](adr/016-authentication-architecture.md) — Authentication Architecture
- [ADR-017](adr/017-replay-engine-design.md) — Replay Engine Design
- [ADR-018](adr/018-export-system-design.md) — Export System Design
- [ADR-019](adr/019-session-sharing.md) — Session Sharing
- [ADR-020](adr/020-performance-scaling.md) — Performance & Scaling
- [ADR-021](adr/021-sdk-distribution.md) — SDK Distribution
- [ADR-022](adr/022-frontend-testing-strategy.md) — Frontend Testing Strategy

## Planning

- [Roadmap](planning/ROADMAP.md) — What's done (~92%) and what's left, with priority order
- [Implementation Analysis](planning/IMPLEMENTATION_ANALYSIS.md) — Deep-dive implementation guide for all remaining features
- [Business Analysis](planning/BUSINESS_ANALYSIS_V2.md) — Market analysis, GTM strategy, financial projections

## Topics

- [Coroutine Testing Framework](topics/COROUTINE_TESTING_FRAMEWORK.md) — Testing architecture and DSL design
- [VizScope Testing Design](topics/VIZSCOPE_TESTING_FRAMEWORK_DESIGN.md) — VizScope test framework
- [VizScope Testing Summary](topics/VIZSCOPE_TESTING_SUMMARY.md) — Testing summary and results
- [VizScope Testing Delivery](topics/VIZSCOPE_TESTING_DELIVERY.md) — Delivery status
- [FlatMap Design](topics/FLATMAP_DESIGN.md) — FlatMap operator design
- [Flow Frontend Implementation](topics/FLOW_FRONTEND_IMPLEMENTATION.md) — Flow panel implementation details
- [Backend Deep Dive](topics/coroutine-visualizer-backend-chap51.md) — Backend internals chapter

### Advanced Coroutine Topics

- [Actor Pattern](topics/advanced/ACTOR_PATTERN.md)
- [Backpressure Strategies](topics/advanced/BACKPRESSURE_STRATEGIES.md)
- [Coroutine Internals](topics/advanced/COROUTINE_INTERNALS.md)
- [Exception Handling Patterns](topics/advanced/EXCEPTION_HANDLING_PATTERNS.md)
- [Mutex & Semaphore Sync](topics/advanced/MUTEX_SEMAPHORE_SYNC.md)
- [Performance Anti-Patterns](topics/advanced/PERFORMANCE_ANTIPATTERNS.md)
- [Real-World Patterns](topics/advanced/REAL_WORLD_PATTERNS.md)
- [Select Expression](topics/advanced/SELECT_EXPRESSION.md)
- [Testing with Virtual Time](topics/advanced/TESTING_VIRTUAL_TIME.md)
