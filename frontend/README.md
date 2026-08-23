# Coroutine Visualizer - Frontend

A modern React application for visualizing Kotlin coroutine execution in real-time.

## Tech Stack

See [TECH_STACK.MD](./TECH_STACK.MD) for the complete technology overview.

### Core Technologies
- **React 19** + **TypeScript** - Modern UI with type safety
- **Vite 6** with SWC - Fast build tooling
- **TanStack Router** - Type-safe file-based routing
- **TanStack Query** - Server state management
- **HeroUI** - Beautiful component library
- **Tailwind CSS** - Utility-first styling
- **Framer Motion** - Smooth animations
- **react-zoom-pan-pinch** - Interactive graph pan/zoom
- **MSW** - API mocking for offline development

## Getting Started

### Prerequisites

- Node.js >= 24.0.0
- pnpm >= 9.0.0 (preferred; the repo ships a `pnpm-lock.yaml`)
- npm >= 10.0.0 works if you cannot use pnpm (will ignore the pnpm lock)
- **Backend running on port 8080** (requests are proxied automatically via Vite)

### Installation (pnpm, recommended)

```bash
# Ensure pnpm is available (Node ships with Corepack)
corepack enable pnpm

# Install dependencies
pnpm install

# Start development server
pnpm dev

# Build for production
pnpm build

# Preview production build
pnpm preview
```

### Installation (npm fallback)

You can use npm if pnpm is unavailable; versions may differ from the lockfile.

```bash
npm install
npm run dev       # start development server
npm run build     # production build
npm run preview   # preview the build
```

Open http://localhost:3000 after starting the dev server.

## Project Structure

```
src/
├── components/                     # React components
│   ├── Layout.tsx                  # Main layout with navbar (Home, Sessions, Scenarios)
│   ├── SessionDetails.tsx          # Session detail view with tabbed interface
│   ├── CreateSessionForm.tsx       # Session creation form
│   ├── CoroutineTree.tsx           # List-based tree view with state icons
│   ├── CoroutineTreeGraph.tsx      # Interactive graph view with pan/zoom
│   ├── CoroutineNodeEnhanced.tsx   # Enhanced node rendering
│   ├── EnhancedCoroutineTreeNode.tsx
│   ├── EventsList.tsx              # Live event feed with type filtering
│   ├── CoroutineTimelineView.tsx   # Timeline visualization
│   ├── ThreadLanesView.tsx         # Horizontal thread activity lanes
│   ├── ThreadTimeline.tsx          # Thread-level event timeline
│   ├── DispatcherOverview.tsx      # Dispatcher cards (pool size, queue depth)
│   ├── DispatcherBadge.tsx         # Reusable dispatcher badge
│   ├── JobStateDisplay.tsx         # Job state from JobStateChanged events
│   ├── JobStatusDisplay.tsx        # Job status indicators
│   ├── JobInfoCard.tsx             # Detailed job information card
│   ├── StateIndicator.tsx          # State visualization helper
│   ├── ScenarioBuilder.tsx         # Custom scenario builder UI
│   ├── ScenarioForm.tsx            # Scenario configuration form
│   ├── StructuredConcurrencyInfo.tsx  # Structured concurrency info
│   ├── WaitingIndicator.tsx        # Waiting-for-children indicator
│   ├── EmptyState.tsx              # Empty state placeholder
│   ├── ErrorAlert.tsx              # Error message display
│   └── LoadingSpinner.tsx          # Loading indicator
├── hooks/                          # Custom React hooks
│   ├── use-sessions.ts             # Session CRUD + events
│   ├── use-scenarios.ts            # Scenario list + run mutations
│   ├── use-event-stream.ts         # SSE connection management
│   ├── use-hierarchy.ts            # Coroutine hierarchy, tree, stats
│   ├── use-enhanced-hierarchy.ts   # Hierarchy with suspension/timing data
│   └── use-thread-activity.ts      # Thread lanes, utilization, dispatcher grouping
├── lib/                            # Utilities and client libraries
│   ├── api-client.ts               # REST + SSE API client
│   ├── query-client.ts             # TanStack Query config (30s stale, 1 retry)
│   └── utils.ts                    # cn() class merge (clsx + tailwind-merge)
├── routes/                         # File-based routing (TanStack Router)
│   ├── __root.tsx                  # Root layout + error/404 handling
│   ├── index.tsx                   # Home / dashboard
│   ├── sessions/
│   │   ├── index.tsx               # Session list
│   │   └── $sessionId.tsx          # Session detail (tabbed)
│   └── scenarios/
│       ├── index.tsx               # Scenario catalog
│       └── builder.tsx             # Custom scenario builder
├── types/
│   └── api.ts                      # TypeScript types matching backend DTOs
├── mocks/                          # MSW mock handlers
│   ├── browser.ts                  # Worker setup
│   ├── handlers.ts                 # Mock API endpoints
│   └── mock-data.ts                # Mock data generators
├── main.tsx                        # Application entry point
└── index.css                       # Global styles
```

## Routes

| Path | Page | Description |
|------|------|-------------|
| `/` | Home | Dashboard / hero section |
| `/sessions` | Sessions | List, create, and delete visualization sessions |
| `/sessions/:sessionId` | Session Detail | Tabbed view: Visualization, Job States, Events, Threads, Dispatchers |
| `/scenarios` | Scenarios | Browse and run pre-built scenarios |
| `/scenarios/builder` | Builder | Compose custom coroutine scenarios via UI |

## Features

### 1. Session Management
- Create and manage visualization sessions
- View active sessions with coroutine counts
- Delete sessions when done

### 2. Real-time Event Streaming
- Connect to sessions via Server-Sent Events (SSE)
- Live updates as coroutines are created, started, and completed
- Visual indicators for connection status

### 3. Coroutine Visualization
- **Tree view** - Hierarchical list with state icons, animated badges, job property display
- **Graph view** - Interactive pan/zoom graph of coroutine topology
- Color-coded states: Created, Active, Suspended, Waiting-for-Children, Completed, Cancelled, Failed
- Shake animations for failed/cancelled, pulse for active/waiting

### 4. Thread & Dispatcher Tracking
- Horizontal lane view showing per-thread activity segments
- Dispatcher cards with thread pool size, thread IDs, and queue depth warnings
- Utilization statistics grouped by dispatcher (Default, IO, Main, Unconfined)

### 5. Event Timeline
- Chronological list of all coroutine events
- Filter events by type with search box
- Service icon parsing (OrderService, UserRepository, etc.)
- Dispatcher badge integration
- Expandable event details

### 6. Scenario Execution
- Run pre-built coroutine scenarios (realistic + basic)
- Custom scenario builder with coroutine hierarchy and action composition
- Automatically creates a session and navigates to results

## How It Works

- **App bootstrap:** `src/main.tsx` wires the HeroUI theme, TanStack Query client (`src/lib/query-client.ts`), and TanStack Router (generated `src/routeTree.gen.ts`) before mounting React.
- **Routing:** File-based routes in `src/routes` cover `/` (landing), `/sessions` (list/create/delete sessions), `/sessions/:sessionId` (session detail), `/scenarios` (catalog), and `/scenarios/builder` (custom scenario builder). `src/components/Layout.tsx` renders the shared navbar and shell.
- **Data layer:** `src/lib/api-client.ts` wraps all `/api` calls: session CRUD + snapshots, event history/pagination, SSE stream creation, scenarios (prebuilt + custom), thread activity, dispatchers, hierarchies, and timelines. React Query hooks (`src/hooks/use-sessions.ts`, `use-scenarios.ts`, `use-thread-activity.ts`) handle caching, refetch intervals, and invalidation after mutations.
- **Live updates:** `src/hooks/use-event-stream.ts` opens an `EventSource` to `/api/sessions/:id/stream`, normalizes backend event types, and invalidates the session query when new events arrive. Session detail can toggle between stored events and live SSE events.
- **Session detail experience:** `src/components/SessionDetails.tsx` orchestrates the page—scenario controls, live stream toggle, graph vs list views (`CoroutineTreeGraph` for pan/zoom, `CoroutineTree` for a compact tree), job state chips, event timeline (`EventsList`), thread timelines, and dispatcher cards. Scenario runs call `useRunScenario`, and deletions reset navigation.
- **Scenarios:** `/scenarios` lists backend-provided scenarios and creates a fresh session before navigating to `/sessions/:id` with scenario metadata. `/scenarios/builder` uses `src/components/ScenarioBuilder.tsx` to compose coroutine hierarchies and actions, POSTs to `/api/scenarios/custom`, and jumps to the resulting session.
- **Styling & UX:** Tailwind + HeroUI drive theming; Framer Motion animates tree nodes/status chips; `react-zoom-pan-pinch` enables pan/zoom on the graph view.

## API Integration

All requests go through Vite's dev proxy (`/api` -> `http://localhost:8080`). No CORS configuration needed in development.

| Method | Endpoint | Hook | Description |
|--------|----------|------|-------------|
| `GET` | `/api/sessions` | `useSessions()` | List all sessions |
| `POST` | `/api/sessions` | `useCreateSession()` | Create new session |
| `GET` | `/api/sessions/:id` | `useSession(id)` | Get session snapshot |
| `DELETE` | `/api/sessions/:id` | `useDeleteSession()` | Delete session |
| `GET` | `/api/sessions/:id/events` | `useSessionEvents(id)` | Stored events (paginated) |
| `GET` | `/api/sessions/:id/stream` | `useEventStream(id)` | SSE real-time stream |
| `GET` | `/api/sessions/:id/hierarchy` | `useHierarchy(id)` | Coroutine hierarchy |
| `GET` | `/api/sessions/:id/threads` | `useThreadActivity(id)` | Thread activity (auto-refresh 2s) |
| `GET` | `/api/scenarios` | `useScenarios()` | List available scenarios |
| `POST` | `/api/scenarios/:id` | `useRunScenario()` | Run scenario |
| `POST` | `/api/scenarios/custom` | `useRunScenario()` | Run custom scenario |

### SSE Event Handling

The `useEventStream` hook:
- Connects to `/api/sessions/:id/stream`
- Receives history replay first, then live events
- Normalizes both PascalCase (`CoroutineCreated`) and kebab-case (`coroutine.created`) formats
- Auto-invalidates TanStack Query caches on new events
- Exposes: `events`, `isConnected`, `error`, `clearEvents()`

## Development

### Proxy Configuration

Defined in `vite.config.ts` - `/api/*` requests forward to `http://localhost:8080`:

```typescript
server: {
  port: 3000,
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
},
```

### Mock Service Worker (MSW)

For local development without the backend:

1. Initialize MSW:
   ```bash
   npx msw init public/
   ```

2. Enable mocking in `main.tsx`:
   ```typescript
   if (import.meta.env.DEV) {
     const { worker } = await import('./mocks/browser')
     worker.start()
   }
   ```

Mock data includes realistic scenarios (Order Processing, User Registration, Report Generation) with generated hierarchies, thread activity, and timeline events.

### Code Quality

```bash
# Lint code
pnpm lint

# Format code
pnpm format
```

## Key Hooks

| Hook | Description |
|------|-------------|
| `useSessions()` | Fetch all sessions with caching |
| `useSession(id)` | Get session snapshot with coroutines and events |
| `useCreateSession()` | Mutation to create a new session |
| `useDeleteSession()` | Mutation to delete a session |
| `useSessionEvents(id)` | Fetch stored events with pagination |
| `useEventStream(id, enabled)` | SSE connection for real-time events |
| `useScenarios()` | List available scenarios |
| `useRunScenario()` | Mutation to execute a scenario |
| `useHierarchy(id)` | Coroutine hierarchy tree + stats |
| `useThreadActivity(id)` | Thread lanes + dispatcher utilization (2s refresh) |

## Styling

The app uses Tailwind CSS with the HeroUI component library:

- Dark/light mode support (class-based toggle)
- Primary color: `#006fee` (vizcore blue, dark-first). Defined once in `src/styles/palette.ts`.
- Responsive design with mobile-first approach
- Custom container utilities (`container-custom`)
- Framer Motion for smooth animations
- `cn()` utility combining clsx + tailwind-merge for safe class composition

## Performance

- Route-based code splitting
- Optimistic updates with TanStack Query
- Virtualized lists for large datasets (planned)
- Debounced filters and search
- `defaultPreload: 'intent'` - routes prefetch on hover

## Contributing

1. Follow the existing code structure
2. Use TypeScript strictly (no `any` types)
3. Write accessible components
4. Test with both real backend and MSW mocks

See [CONTRIBUTING.md](../backend/CONTRIBUTING.md) for full guidelines.

## License

MIT
