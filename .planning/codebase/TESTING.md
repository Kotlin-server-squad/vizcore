# Testing Patterns

**Analysis Date:** 2026-06-11

---

## Backend (Kotlin)

### Frameworks

**Runner:** JUnit 5 (Jupiter) 5.10.1 + `kotlin-test-junit`
- Config: `backend/build.gradle.kts` — `tasks.named<Test>("test") { useJUnitPlatform() }`
- Coroutine tests: `kotlinx-coroutines-test` 1.7.3 (`runTest`)
- HTTP integration: `ktor-server-test-host` + `ktor-client-content-negotiation`

**Assertion Library:** `kotlin.test` (`assertEquals`, `assertTrue`, `assertNotNull`)

**Run Commands:**
```bash
cd backend && ./gradlew test               # Run all tests
cd backend && ./gradlew test --tests "*.StructuredConcurrencyTest"  # Run specific test class
cd backend && ./gradlew test --info        # Verbose output
```

### Test File Organization

**Location:** Separate `src/test/` tree, mirroring the `src/main/` package structure.

```
backend/
├── src/
│   ├── main/kotlin/com/jh/proj/coroutineviz/
│   └── test/kotlin/
│       ├── ApplicationTest.kt                       # Top-level app smoke test
│       ├── StructuredConcurrencyTest.kt             # Coroutine behavior tests
│       ├── BaseTestService.kt                       # Shared test interfaces/data classes
│       └── com/jh/proj/coroutineviz/
│           ├── AuthTest.kt
│           ├── CompressionTest.kt
│           ├── routes/
│           │   ├── SessionRoutesTest.kt
│           │   ├── ScenarioRoutesTest.kt
│           │   ├── SseStreamTest.kt
│           │   └── HealthRoutesTest.kt
│           ├── wrappers/
│           │   ├── InstrumentedFlowTest.kt
│           │   ├── InstrumentedChannelTest.kt
│           │   ├── InstrumentedDeferredTest.kt
│           │   ├── InstrumentedSharedStateFlowTest.kt
│           │   ├── VizDispatchersTest.kt
│           │   └── VizDispatchersIntegrationTest.kt
│           ├── checksystem/
│           │   ├── HierarchyValidatorTest.kt
│           │   ├── LifecycleValidatorTest.kt
│           │   ├── SyncValidatorsTest.kt
│           │   ├── TimingAnalyzerTest.kt
│           │   ├── SequenceCheckerTest.kt
│           │   └── ValidationRoutesTest.kt
│           ├── sync/SyncPrimitivesTest.kt
│           └── events/EventSerializationTest.kt
coroutine-viz-core/
└── src/test/kotlin/          # Mirrors core submodule's main sources
```

**Naming:** Class named `<Subject>Test` (e.g., `SessionRoutesTest`, `HierarchyValidatorTest`)

### Test Structure

**Route/Integration Tests (Ktor Test Host):**
```kotlin
class SessionRoutesTest {
    @BeforeEach fun setUp() { SessionManager.clearAll() }
    @AfterEach  fun tearDown() { SessionManager.clearAll() }

    // Helper to configure JSON client once per class
    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ContentNegotiation) { json() } }

    @Test
    fun `GET sessions returns empty list initially`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            val response = client.get("/api/sessions")
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
```
- `testApplication { }` is the entry point for all HTTP-level tests
- `application { module() }` loads the full Ktor application module
- Each test is self-contained; state cleared in `@BeforeEach`/`@AfterEach`

**Coroutine Unit Tests:**
```kotlin
class StructuredConcurrencyTest {
    @Test
    fun `test basic structured concurrency - parent waits for children`() =
        runTest {
            val session = VizSession("test-basic-sc")
            // Collect events via session.bus.stream()
            val eventLogger = launch { session.bus.stream().collect { ... } }
            // Exercise the subject
            val scope = VizScope(session, "root")
            scope.vizLaunch("child") { delay(100) }
            // Advance time and assert
            advanceUntilIdle()
            assertEquals(CoroutineState.COMPLETED, ...)
        }
}
```
- `runTest` replaces `runBlocking` for coroutine tests — provides `TestCoroutineScheduler`
- `advanceUntilIdle()` drains all pending coroutines in virtual time
- `delay()` inside `runTest` is virtual — tests run instantly

**Validator/Unit Tests:**
```kotlin
class HierarchyValidatorTest {
    // Builder helpers produce domain events with minimal boilerplate
    private fun created(coroutineId: String, seq: Long, parentCoroutineId: String? = null, ...): CoroutineCreated =
        CoroutineCreated(sessionId = "test-session", seq = seq, tsNanos = seq * 1000, ...)

    @Test
    fun `detects missing parent`() {
        val events: List<VizEvent> = listOf(created("child", seq = 2, parentCoroutineId = "ghost"))
        val result = HierarchyValidator().validate(events)
        assertTrue(result.any { it.severity == Severity.ERROR })
    }
}
```

### Mocking

**Framework:** No dedicated mocking library (Mockito/MockK not present).

**Patterns observed:**
- Inline interfaces defined in `BaseTestService` for manual stubbing
- Real domain objects (`VizSession`) used in tests — no mocking of core session/event infrastructure
- `SessionManager.clearAll()` resets global singleton state between tests
- Test-specific event builders (private helper functions) replace factories

**What to mock:** HTTP responses via `testApplication` client (Ktor handles this).

**What NOT to mock:** `VizSession`, `EventBus`, `EventStore` — use real instances in coroutine tests.

### Coverage

**Requirements:** Not enforced in CI config (no Jacoco coverage threshold found).

---

## Frontend (TypeScript)

### Frameworks

**Runner:** Vitest 4.1 — config at `frontend/vitest.config.ts`
- Environment: `jsdom`
- Globals: `true` (no explicit `import { describe, it }` needed, but codebase imports them explicitly anyway)
- Setup file: `frontend/src/test/setup.ts` — imports `@testing-library/jest-dom/vitest`
- Coverage provider: `v8`, scoped to `src/lib/**`, `src/hooks/**`, `src/components/**`

**Component testing:** `@testing-library/react` 16 + `@testing-library/user-event` 14
**HTTP mocking:** MSW 2 (`msw`) — handlers in `src/mocks/handlers.ts`, browser setup in `src/mocks/browser.ts`

**Run Commands:**
```bash
cd frontend && pnpm test            # Run all tests (one-shot)
cd frontend && pnpm test:watch      # Watch mode
cd frontend && pnpm test:coverage   # With v8 coverage
```

### Test File Organization

**Location:** Co-located with source files in same directory.

```
src/
├── components/
│   ├── CoroutineTree.tsx
│   ├── CoroutineTree.test.tsx          # Co-located
│   ├── sync/
│   │   ├── SyncPanel.tsx
│   │   └── SyncPanel.test.tsx          # Co-located in subdirectory
│   ├── comparison/ComparisonView.test.tsx
│   ├── flow/FlowPanel.test.tsx
│   ├── channels/ChannelPanel.test.tsx
│   ├── jobs/JobPanel.test.tsx
│   └── validation/ValidationPanel.test.tsx
├── hooks/
│   ├── use-event-stream.ts
│   ├── use-event-stream.test.ts        # Co-located
│   ├── use-hierarchy.test.ts
│   ├── use-timeline.test.ts
│   └── ... (one .test.ts per hook)
├── lib/
│   ├── api-client.ts
│   ├── api-client.test.ts              # Co-located
│   ├── export-png.test.ts
│   └── export-svg.test.ts
├── mocks/
│   ├── browser.ts                      # MSW service worker setup
│   ├── handlers.ts                     # MSW request handlers
│   └── mock-data.ts                    # Shared mock data generators
└── test/
    └── setup.ts                        # Global Vitest setup (jest-dom matchers)
```

**Pattern:** `include: ['src/**/*.test.{ts,tsx}']` — any `.test.ts` or `.test.tsx` file under `src/`.

### Test Structure

**Hook tests with QueryClient wrapper:**
```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// Module-level mock declaration
vi.mock('@/lib/api-client', () => ({
  apiClient: { getHierarchy: vi.fn() },
}))
const mockedApiClient = vi.mocked(apiClient)

// Wrapper factory (repeated per test file)
function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useHierarchy', () => {
  beforeEach(() => { vi.clearAllMocks() })
  afterEach(() => { vi.restoreAllMocks() })

  it('returns hierarchy data', async () => {
    mockedApiClient.getHierarchy.mockResolvedValue(mockData)
    const { result } = renderHook(() => useHierarchy('session-1'), { wrapper: createWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(mockData)
  })
})
```

**Component tests with Testing Library:**
```typescript
import { render, screen } from '@testing-library/react'

// Factory for domain objects — named make<Entity> or createMock<Entity>
function makeCoroutine(overrides: Partial<CoroutineNode> = {}): CoroutineNode {
  return { id: 'c-1', jobId: 'j-1', parentId: null, scopeId: 'scope-1',
           label: 'TestCoroutine', state: CoroutineState.ACTIVE, ...overrides }
}

describe('CoroutineTree', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders empty state when no coroutines', () => {
    render(<CoroutineTree coroutines={[]} />)
    expect(screen.getByText('No coroutines in this session yet.')).toBeInTheDocument()
  })

  it('renders coroutine nodes with labels', () => {
    render(<CoroutineTree coroutines={[makeCoroutine({ label: 'Worker' })]} />)
    expect(screen.getByText('Worker')).toBeInTheDocument()
  })
})
```

**Plain utility/lib tests (no React):**
```typescript
import { apiClient } from './api-client'

const mockFetch = vi.fn()
beforeEach(() => { mockFetch.mockReset(); vi.stubGlobal('fetch', mockFetch) })
afterEach(() => { vi.unstubAllGlobals() })

function mockJsonResponse(data: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: () => Promise.resolve(data) }
}
```

### Mocking

**Framework:** `vi.mock()` (Vitest built-in) + MSW 2 for HTTP

**Module mocking pattern:**
```typescript
vi.mock('@/lib/api-client', () => ({
  apiClient: { methodName: vi.fn() },
}))
const mockedApiClient = vi.mocked(apiClient)
```

**SSE / EventSource mocking:** Manual `MockEventSource` class implementing the browser API surface:
```typescript
class MockEventSource {
  listeners = new Map<string, ((e: Event) => void)[]>()
  close = vi.fn()
  addEventListener(type: string, handler: (e: Event) => void) { ... }
  simulateEvent(type: string, data: string) { ... }  // test helper
}
```

**Animation library mocking (framer-motion):**
```typescript
vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }) => <div {...props}>{children}</div>,
    span: ({ children, ...props }) => <span {...props}>{children}</span>,
  },
  AnimatePresence: ({ children }) => <>{children}</>,
  LayoutGroup: ({ children }) => <>{children}</>,
}))
```

**What to mock:**
- `@/lib/api-client` in all hook and component tests (never make real HTTP calls)
- `framer-motion` in component tests (avoid animation side-effects in jsdom)
- Custom animation hooks (`@/lib/animation-throttle`)
- Global `fetch` when testing `api-client.ts` itself (use `vi.stubGlobal`)

**What NOT to mock:**
- TanStack Query internals — use a real `QueryClient` with `retry: false`
- Domain type constructors — build them with factory helpers
- React itself

### Fixtures and Factories

**Test data factory pattern:**
```typescript
// Per-file factory — not shared globally
function makeCoroutine(overrides: Partial<CoroutineNode> = {}): CoroutineNode { ... }
function createMockNode(overrides: Partial<HierarchyNode> & { id: string }): HierarchyNode { ... }
```

**Shared mock data:** `src/mocks/mock-data.ts` — generators for complete scenarios used by MSW handlers and dev tools (not imported by unit tests directly).

**MSW handlers:** `src/mocks/handlers.ts` — `http.get`, `http.post` via `msw` v2 `HttpResponse`. Used for development/integration scenarios, not unit tests.

### Coverage

**Requirements:** No enforced threshold.

**Scoped to:**
- `src/lib/**`
- `src/hooks/**`
- `src/components/**`

**View coverage:**
```bash
cd frontend && pnpm test:coverage
# Output in coverage/ directory (HTML + text)
```

### Test Types

**Unit tests (hooks):** `renderHook` + `waitFor` for async TanStack Query hooks. All API calls mocked via `vi.mock`.

**Unit tests (utilities):** Direct function calls; `vi.stubGlobal('fetch', ...)` for HTTP.

**Component tests:** `render` + `screen` queries from Testing Library. Props-driven, no router context unless required.

**Integration/E2E:** Not present — MSW handlers exist for development mocking only, not automated E2E tests.

---

*Testing analysis: 2026-06-11*
