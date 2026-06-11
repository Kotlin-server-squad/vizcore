# Coding Conventions

**Analysis Date:** 2026-06-11

## Overview

This is a monorepo with two distinct language stacks. Conventions are per-language and enforced by separate toolchains.

---

## Kotlin (Backend)

### Tooling

**Linting:** `detekt` 1.23.7 — config at `backend/detekt.yml`, baseline at `backend/detekt-baseline.xml`
- `build.maxIssues: 0` — zero tolerance; CI blocks on any new violations
- `GlobalCoroutineUsage: active: true` — never use `GlobalScope` or top-level coroutine builders
- `CyclomaticComplexMethod.threshold: 20`
- `WildcardImport: active: false` — wildcard imports are permitted
- `MaxLineLength: active: false` — no hard line-length limit
- Run: `cd backend && ./gradlew detekt`

**Formatting:** `ktlint` 12.2.0 via `org.jlleitschuh.gradle.ktlint` plugin
- Run: `cd backend && ./gradlew ktlintCheck` / `./gradlew ktlintFormat`

### Naming Patterns

**Files:** PascalCase, matching the primary class/object name (e.g., `VizSession.kt`, `SessionRoutesTest.kt`)

**Classes/Objects:** PascalCase — `VizSession`, `EventBus`, `SessionManager`, `InstrumentedFlow`

**Interfaces:** PascalCase without `I` prefix — `UserRepository`, `NotificationService`

**Functions:** camelCase — `getUser`, `sendSms`, `coroutineCreated`, `jobStateChanged`
- Suspend functions follow same convention
- Test functions use backtick names: `` `test basic structured concurrency - parent waits for children` ``

**Variables/Properties:** camelCase — `eventLog`, `sessionId`, `eventSource`

**Constants:** SCREAMING_SNAKE_CASE in companion objects — standard Kotlin convention

**Packages:** lowercase dot-separated — `com.jh.proj.coroutineviz.session`, `com.jh.proj.coroutineviz.wrappers`

### Coroutine Conventions

- **Never use `GlobalScope`** — enforced by detekt `GlobalCoroutineUsage` rule
- Always launch coroutines from a scoped `CoroutineScope` (typically `VizSession`'s internal scope)
- Use `SupervisorJob` when child failures must not cancel siblings — see `VizSession.kt`
- Use `CoroutineName` for labeling: `launch(CoroutineName("name")) { ... }`
- Cancellation: always handle `CancellationException` — do not swallow it
- Use `kotlinx-coroutines-test` `runTest` for all coroutine tests
- Dispatcher pattern: `CoroutineScope(Dispatchers.Default + SupervisorJob())` for session-level scopes

### Error Handling

- Use `try/catch(CancellationException)` — rethrow `CancellationException` always
- Use sealed classes (`UiState`, event hierarchies) for modeling outcomes
- Return `null` or sealed `Error` types rather than throwing from business logic
- `TooGenericExceptionCaught` is suppressed for `wrappers/**` only — general catch is not permitted elsewhere

### KDoc / Comments

- KDoc on public classes and complex functions: describe what the class represents and the lifecycle/invariants
- Example from `VizSession.kt`: multi-paragraph KDoc explaining ownership, event flow order, and threading
- Internal helper functions: inline comments explaining non-obvious logic
- Test classes: numbered `TEST N:` comment blocks describing expected behavior before each test method

### Serialization

- Use `kotlinx.serialization` with `@Serializable` annotation
- JSON content negotiation via Ktor `ContentNegotiation` plugin
- Event types use polymorphic serialization registered in `Serialization.kt`

### Import Style

- Wildcard imports allowed (detekt rule disabled)
- Group: stdlib → kotlinx → ktor → project-local (no enforced order, but consistent in practice)

---

## TypeScript (Frontend)

### Tooling

**Compiler:** TypeScript 5.7 in strict mode — `tsconfig.json` enables:
- `strict: true`
- `noUnusedLocals: true`
- `noUnusedParameters: true`
- `noFallthroughCasesInSwitch: true`
- `noUncheckedIndexedAccess: true`

**Linting:** ESLint 9 flat config — `frontend/eslint.config.js`
- `@typescript-eslint/no-unused-vars`: warn (args prefixed with `_` ignored)
- `@typescript-eslint/no-explicit-any`: warn
- `no-unused-vars`: off (replaced by TS-aware rule)
- Run: `cd frontend && pnpm lint`

**Formatting:** Prettier 3.4 — `frontend/.prettierrc`
- No semicolons (`"semi": false`)
- Single quotes (`"singleQuote": true`)
- 2-space indent
- Trailing commas: `"es5"`
- Print width: 100
- Arrow parens: `"avoid"` (omit parens for single-arg arrows)
- Run: `cd frontend && pnpm format`

### Naming Patterns

**Files:**
- Components: PascalCase `.tsx` — `CoroutineTree.tsx`, `SyncPanel.tsx`, `JobInfoCard.tsx`
- Hooks: kebab-case `.ts` prefixed with `use-` — `use-event-stream.ts`, `use-hierarchy.ts`
- Utilities/libraries: kebab-case `.ts` — `api-client.ts`, `export-svg.ts`
- Test files: co-located, same name + `.test.ts` / `.test.tsx`
- Type files: kebab-case — `api.ts` in `src/types/`

**Functions/Hooks:** camelCase — `useEventStream`, `useHierarchy`, `fetchJson`
- Hooks always prefixed `use`

**Variables:** camelCase — `mockEventSource`, `queryClient`, `isConnected`

**Types/Interfaces:** PascalCase — `CoroutineNode`, `SessionInfo`, `VizEvent`, `BaseVizEvent`
- Interfaces do not use `I` prefix
- Event interfaces suffixed `Event` where needed to disambiguate — `JobStateChangedEvent`, `ThreadAssignedEvent`

**Enums:** PascalCase name, SCREAMING_SNAKE_CASE values — `CoroutineState.ACTIVE`, `CoroutineState.COMPLETED`

**Constants:** camelCase at module level — `API_BASE_URL`

### Path Aliases

Defined in `tsconfig.json` and mirrored in `vitest.config.ts`:
- `@/*` → `src/*`
- `@vizcor/api-types` → `../shared/api-types/index.ts`

Use `@/` prefix for all intra-src imports. Never use relative `../../` paths that cross directory levels.

### Import Organization

1. External packages (React, TanStack, vitest)
2. Internal path-aliased imports (`@/lib/...`, `@/hooks/...`, `@/types/...`)
3. Relative same-directory imports

Type-only imports use `import type { ... }` syntax consistently.

### Component Design

- Functional components only — no class components
- Props typed with inline interfaces or `type` aliases (not separate `IProps` interface)
- Factory functions for test data named `make<Entity>` or `createMock<Entity>` — e.g., `makeCoroutine()`, `createMockNode()`
- Components accept domain types directly from `@/types/api` — no intermediate ViewModel types

### State Management

- Local state: `useState` / `useReducer`
- Server state: TanStack Query (`useQuery`, `useMutation`) via hooks in `src/hooks/`
- No global store (Redux/Zustand) — query cache is the shared server state
- SSE events accumulated in `useState<VizEvent[]>` within `useEventStream`

### Error Handling

- Async functions in `api-client.ts` throw on non-2xx responses (check `response.ok`)
- Components receive error state from hooks (`error: string | null`)
- No `any` casts for error types where avoidable — use `unknown` + type narrowing

### CSS / Styling

- Tailwind CSS 3 utility classes
- HeroUI component library (`@heroui/react`)
- `clsx` + `tailwind-merge` for conditional class composition
- No CSS modules; no inline `style` props for layout

---

## Cross-Cutting

### Commit Messages

Conventional commits enforced by convention:
- `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`
- PRs targeted < 500 lines, linked to issues

### API Contract

Shared types in `shared/api-types/` — TypeScript types generated from backend OpenAPI spec.
- Frontend imports via `@vizcor/api-types` alias
- Backend-defined events are the source of truth; frontend `src/types/api.ts` re-exports and extends

---

*Convention analysis: 2026-06-11*
