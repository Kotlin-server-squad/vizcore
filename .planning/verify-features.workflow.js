export const meta = {
  name: 'verify-features',
  description: 'Verify all 39 vizcore requirements against real code + test results; adversarially challenge every positive verdict; write VERIFICATION.md',
  phases: [
    { title: 'Verify', detail: 'one agent per feature area → per-requirement verdicts' },
    { title: 'Challenge', detail: 'adversarial skeptic refutes every Works/Partial claim' },
    { title: 'Synthesize', detail: 'write .planning/VERIFICATION.md verdict matrix' },
  ],
}

// args = { backendTests: <summary string>, frontendTests: <summary string> }
const TEST_CONTEXT = `
TEST-SUITE GROUND TRUTH (already run inline — use as dynamic evidence, do not re-run):
Backend (./gradlew test):
${(args && args.backendTests) || '(unavailable)'}

Frontend (pnpm test / vitest run):
${(args && args.frontendTests) || '(unavailable)'}
`

const REPO = '/Users/jirihermann/Documents/workspace-vizcore/vizcore'

const AREAS = [
  { key: 'foundation', reqs: 'FND-01, FND-02, FND-03',
    hint: 'Backend session package. Verify whether the duplicate com.jh.proj.coroutineviz.session.* fork in backend/src/main/ still exists vs coroutine-viz-core; whether the RUNNING server wires the bounded EventStore (maxEvents eviction) or the unbounded CopyOnWriteArrayList; and whether any regression test asserts the bounded store is in use.' },
  { key: 'production', reqs: 'PROD-01, PROD-02, PROD-03, PROD-04, PROD-05',
    hint: 'Health routes (/api/health,/live,/ready), Logback dev/prod profiles + stray println, CORS from config vs hardcoded, OpenAPI completeness, and the full ADR-020 Micrometer metric set (events emitted/dropped, scenario+event-processing durations, active-sessions + SSE-client gauges). Count which gauges actually exist.' },
  { key: 'replay', reqs: 'RPLY-01, RPLY-02, RPLY-03',
    hint: 'Frontend replay engine (useReplayEngine hook, ReplayController). Play/pause/stop/step-fwd/step-back, all panels reflect current index, 0.5x-5x speed + scrub, animations respect replay speed.' },
  { key: 'export', reqs: 'EXPT-01, EXPT-02',
    hint: 'Frontend export: PNG via html2canvas, standalone style-inlined SVG, WebM via MediaRecorder, in-browser download.' },
  { key: 'sharing', reqs: 'SHAR-01, SHAR-02',
    hint: 'Backend share tokens: POST /api/sessions/:id/share (expiry 1d/7d/30d/never), GET /api/shared/:token read-only, revocable, rate-limited. Note ADR-019 ties this to a DB shares table (depends on persistence).' },
  { key: 'persistence', reqs: 'PERS-01, PERS-02, PERS-03',
    hint: 'JDBC store (Exposed + HikariCP, H2/PostgreSQL) implementing SessionStoreInterface/EventStoreInterface seam, storage.type=database, Flyway migrations, JSONB events, survive restart, retention policy. Audit flagged this as designed-but-UNIMPLEMENTED — confirm or refute.' },
  { key: 'auth', reqs: 'AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05',
    hint: 'CRITICAL: Auth.kt + authenticatedApi() exist, but audit found Routing.kt registers all route groups WITHOUT calling authenticatedApi() — every endpoint open. Verify exactly which routes are wrapped. Also: SHA-256 key comparison, JWT /api/auth/token with VIEWER/RUNNER/ADMIN roles, tenant isolation, and end-to-end auth enforcement tests.' },
  { key: 'performance', reqs: 'PERF-01, PERF-02, PERF-03, PERF-04',
    hint: 'Per-event-type sampling (EventSampler) wired into emission with X-Sampled; event batching + SSE gzip + X-Accel-Buffering:no header; dev-only load-test harness; rate limiting / max-active-session cap. Audit: EventSampler exists in core but may be unused by backend.' },
  { key: 'comparison', reqs: 'CMPR-01, CMPR-02',
    hint: 'ComparisonService.compare(a,b) → event-count/duration/thread-utilization deltas via GET /api/sessions/compare; frontend side-by-side delta view.' },
  { key: 'sdk', reqs: 'SDK-01, SDK-02',
    hint: 'coroutine-viz-core published to GitHub Packages (MIT POM, semver) + sample app; CLI fat JAR (coroutine-viz-ci.jar check) + coroutineVizCheck Gradle task. Check build.gradle.kts publishing config + CLI module existence.' },
  { key: 'intellij', reqs: 'IDE-01, IDE-02, IDE-03',
    hint: 'ADR-010 Proposed/advisory. Audit: RunWithVisualizerAction.actionPerformed is a TODO stub (javaagent launch missing); JCEF tool window; ZERO plugin tests. Verify the run-action body, tool window, and test presence.' },
  { key: 'frontend-testing', reqs: 'FETEST-01, FETEST-02, FETEST-03',
    hint: 'Actor/select/anti-pattern component+hook tests; overall FE coverage >=80% enforced in CI; Playwright E2E of critical flow; Storybook + Chromatic visual regression. Check for playwright/storybook/chromatic config and CI coverage gate.' },
  { key: 'observability', reqs: 'OTEL-01, OTEL-02',
    hint: 'OpenTelemetry/OTLP exporter mapping events->spans, batch processor, configurable flush, zero overhead when disabled; coroutine spans with parent-child in Jaeger/Zipkin. Check for any opentelemetry dependency at all.' },
]

const VERDICT_SCHEMA = {
  type: 'object',
  required: ['area', 'requirements'],
  properties: {
    area: { type: 'string' },
    requirements: {
      type: 'array',
      items: {
        type: 'object',
        required: ['id', 'verdict', 'confidence', 'evidence', 'test_coverage'],
        properties: {
          id: { type: 'string' },
          verdict: { type: 'string', enum: ['Works', 'Partial', 'Broken', 'Missing', 'Untested'] },
          confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
          evidence: { type: 'array', items: { type: 'string' }, description: 'file:line references proving the verdict' },
          test_coverage: { type: 'string', description: 'which tests cover this (or "none")' },
          notes: { type: 'string' },
        },
      },
    },
  },
}

const CHALLENGE_SCHEMA = {
  type: 'object',
  required: ['area', 'challenges'],
  properties: {
    area: { type: 'string' },
    challenges: {
      type: 'array',
      items: {
        type: 'object',
        required: ['id', 'original_verdict', 'holds', 'corrected_verdict', 'reason'],
        properties: {
          id: { type: 'string' },
          original_verdict: { type: 'string' },
          holds: { type: 'boolean', description: 'true if the positive verdict survives scrutiny' },
          corrected_verdict: { type: 'string', enum: ['Works', 'Partial', 'Broken', 'Missing', 'Untested'] },
          reason: { type: 'string' },
        },
      },
    },
  },
}

const results = await pipeline(
  AREAS,
  // Stage 1 — verify each feature area against code + tests
  (area) => agent(
    `You are verifying a feature area of the vizcore monorepo at ${REPO}. ` +
    `Determine, for EACH requirement, whether it actually works in the real code — not whether code merely exists, but whether it is WIRED and reachable at runtime.\n\n` +
    `Feature area: ${area.key}\nRequirements to verify: ${area.reqs}\nWhere to look: ${area.hint}\n\n` +
    `Read the actual source (backend/, backend/coroutine-viz-core/, frontend/src/, plugin code, build files). Cross-check against the test results below.\n${TEST_CONTEXT}\n\n` +
    `Full requirement text is in ${REPO}/.planning/REQUIREMENTS.md — read it for exact acceptance wording.\n\n` +
    `Verdict rules: Works = implemented AND wired AND (ideally) tested. Partial = implemented but a key piece missing/unwired. Broken = present but does not function. Missing = not implemented. Untested = appears implemented but no test or runtime evidence either way. ` +
    `Be skeptical of "exists but not wired" traps. Cite file:line evidence for every verdict.`,
    { label: `verify:${area.key}`, phase: 'Verify', schema: VERDICT_SCHEMA, agentType: 'Explore' }
  ),
  // Stage 2 — adversarially challenge every Works/Partial verdict
  (verdict, area) => {
    const positives = (verdict && verdict.requirements || []).filter(r => r.verdict === 'Works' || r.verdict === 'Partial')
    if (!positives.length) {
      return { area: area.key, challenges: [], _verdict: verdict }
    }
    return agent(
      `Adversarial verification for vizcore feature area "${area.key}" at ${REPO}. ` +
      `A first-pass reviewer assigned these POSITIVE verdicts. Your job is to REFUTE them — assume each is wrong until the code proves otherwise. ` +
      `For each, independently inspect the cited files and the wiring, and decide if the positive verdict holds. Default to demoting (Works->Partial, Partial->Broken/Missing) when the runtime wiring is not provably present.\n\n` +
      `Claims to challenge:\n${positives.map(r => `- ${r.id}: ${r.verdict} (${r.confidence}) — evidence: ${(r.evidence||[]).join('; ')} — notes: ${r.notes||''}`).join('\n')}\n\n${TEST_CONTEXT}`,
      { label: `challenge:${area.key}`, phase: 'Challenge', schema: CHALLENGE_SCHEMA }
    ).then(ch => ({ ...ch, _verdict: verdict }))
  }
)

// Merge stage-1 verdicts with stage-2 challenges into a final per-requirement list
const merged = results.filter(Boolean).map(r => {
  const verdict = r._verdict || { area: r.area, requirements: [] }
  const challengeById = {}
  for (const c of (r.challenges || [])) challengeById[c.id] = c
  const reqs = (verdict.requirements || []).map(req => {
    const c = challengeById[req.id]
    const finalVerdict = c && !c.holds ? c.corrected_verdict : req.verdict
    return {
      id: req.id,
      verdict: finalVerdict,
      original: req.verdict,
      changed: finalVerdict !== req.verdict,
      confidence: req.confidence,
      evidence: req.evidence,
      test_coverage: req.test_coverage,
      notes: req.notes || '',
      challenge: c ? c.reason : '',
    }
  })
  return { area: verdict.area || r.area, requirements: reqs }
})

phase('Synthesize')
const summary = await agent(
  `Write a feature-verification report to ${REPO}/.planning/VERIFICATION.md for the vizcore product. ` +
  `Use the Write tool. This is the deliverable of a deep-dive feature-verification audit (39 requirements, dynamic + static evidence).\n\n` +
  `Structure the report:\n` +
  `1. Title + date 2026-06-11 + one-paragraph method note (static code read + test-suite ground truth + adversarial challenge of every positive).\n` +
  `2. Scoreboard: counts of Works / Partial / Broken / Missing / Untested across all 39 requirements, and per feature area.\n` +
  `3. A verdict matrix TABLE: Requirement | Verdict | Conf | Test coverage | Evidence (file refs) | Notes. Group by feature area. Mark any verdict the adversarial pass CHANGED with a ⟳ and a one-line reason.\n` +
  `4. "Confirmed audit findings" section: explicitly state whether the 5 prior findings held (session fork, unbounded store, unwired perf, open auth, plugin TODO stub).\n` +
  `5. "Recommended next" — which phase/requirement to tackle first given the verdicts.\n\n` +
  `Here is the merged, adversarially-reviewed verdict data (JSON):\n${JSON.stringify(merged, null, 2)}\n\n${TEST_CONTEXT}\n` +
  `Return a concise text summary (scoreboard + the headline confirmed findings + recommended next) — NOT the file contents.`,
  { label: 'synthesize:VERIFICATION.md', phase: 'Synthesize' }
)

return { merged, summary }
