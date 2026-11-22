# Implementation TODO Lists

This directory contains comprehensive task lists for implementing the Kotlin Coroutines Visualizer platform, derived from the business analysis and technical design documents.

## Files

### 📋 [BACKEND_IMPLEMENTATION_TASKS.md](./BACKEND_IMPLEMENTATION_TASKS.md)
Complete task list for Ktor-based backend implementation, organized in 5 phases:
- **Phase 1**: Foundation (Event model, wrappers, Ktor setup)
- **Phase 2**: Concurrency Primitives (Flow, Channel, advanced tracking)
- **Phase 3**: Teaching Scenarios & Diagnostics
- **Phase 4**: Production Features (Persistence, performance)
- **Phase 5**: Enterprise & Ecosystem

### 📋 [FRONTEND_IMPLEMENTATION_TASKS.md](./FRONTEND_IMPLEMENTATION_TASKS.md)
Complete task list for React + TypeScript frontend, organized in matching phases:
- **Phase 1**: Foundation (Setup, API client, state management)
- **Phase 2**: Core Visualizations (Tree, timeline, thread lanes)
- **Phase 3**: Advanced Features (Replay, scenarios, sessions)
- **Phase 4**: Polish & UX
- **Phase 5**: Enterprise & Ecosystem

## How to Use These Lists

### For GitHub Issues

Each task can be converted to a GitHub issue:

1. **Single Issue per Task**: Create one issue per checklist item
2. **Labels**: Add labels like `backend`, `frontend`, `phase-1`, `enhancement`, `bug`
3. **Milestones**: Group by phase (e.g., "Phase 1: Foundation")
4. **Projects**: Use GitHub Projects to track progress

**Example Issue Template:**
```markdown
## Task
Implement InstrumentedScope wrapper

## Description
Create CoroutineScope delegation pattern with event emission for launch() and async().

## Acceptance Criteria
- [ ] Implement CoroutineScope delegation
- [ ] Create launch() wrapper with event emission
- [ ] Create async() wrapper with event emission
- [ ] Implement lifecycle probe using invokeOnCompletion
- [ ] Add tests

## Phase
Phase 1: Foundation

## Estimate
5 story points

## Related
- Depends on: #12 (Event Model Design)
- Blocks: #24 (InstrumentedDispatcher)
```

### For GitHub Projects

Create a GitHub Project board with columns:
- 📋 **Backlog**: All unstarted tasks
- 🏗️ **In Progress**: Currently being worked on
- 👀 **In Review**: PRs open, awaiting review
- ✅ **Done**: Completed and merged

### For Team Management

**Sprint Planning:**
1. Assign tasks from the same phase to maintain coherence
2. Balance backend and frontend work across team members
3. Ensure prerequisites are completed first (e.g., Event Model before wrappers)

**Estimation Guide:**
- 🟢 **Small (1-2 points)**: Single component, clear requirements, < 4 hours
- 🟡 **Medium (3-5 points)**: Multiple components, some complexity, 1-2 days
- 🔴 **Large (8-13 points)**: Complex feature, multiple dependencies, 3-5 days

### Converting to Linear/Jira/Other Tools

The markdown format can be imported into most project management tools:

**Linear:**
- Use Linear's CSV import or API
- Convert sections to "Initiatives" or "Projects"
- Tasks become "Issues"

**Jira:**
- Use Jira's markdown import
- Create Epics for each phase
- Tasks become Stories or Sub-tasks

**Notion:**
- Copy-paste markdown directly
- Use Notion's database views for kanban/timeline

**Trello:**
- Each phase = Board or List
- Each task = Card

## Task Priorities

### Must-Have for MVP (Phase 1-2)
Backend:
- ✅ Event model & serialization
- ✅ InstrumentedScope (launch, async)
- ✅ InstrumentedDispatcher
- ✅ EventBus & TimelineStore
- ✅ REST API (events, hierarchy, timeline)
- ✅ WebSocket streaming

Frontend:
- ✅ Project setup & types
- ✅ API client & WebSocket
- ✅ State management
- ✅ Hierarchy tree view
- ✅ Timeline visualization
- ✅ Event list

### Should-Have for Beta (Phase 3)
Backend:
- ⭐ InstrumentedFlow & InstrumentedChannel
- ⭐ Teaching scenarios (5+)
- ⭐ DebugProbes integration

Frontend:
- ⭐ Thread lanes view
- ⭐ Replay controller
- ⭐ Scenario management UI

### Nice-to-Have for v1.0 (Phase 4-5)
Backend:
- 💎 Persistence layer
- 💎 OpenTelemetry integration
- 💎 Performance optimizations

Frontend:
- 💎 Advanced visualizations (force-directed graph)
- 💎 Export capabilities
- 💎 Collaboration features

## Dependencies Between Tasks

### Critical Path (Backend)
```
Event Model → InstrumentedScope → Teaching Scenarios
     ↓              ↓                    ↓
  EventBus → TimelineStore → REST API → WebSocket
     ↓
InstrumentedDispatcher → Thread Tracking → Projections
```

### Critical Path (Frontend)
```
Project Setup → API Client → State Management
       ↓            ↓              ↓
  Type Defs → WebSocket Hook → Event Store
       ↓            ↓              ↓
Components → Visualizations → Scenario UI
```

## Progress Tracking

Use this template to track overall progress:

```markdown
## Phase 1: Foundation
**Backend**: ███████░░░ 70% (35/50 tasks)
**Frontend**: █████░░░░░ 50% (25/50 tasks)

## Phase 2: Concurrency Primitives
**Backend**: ███░░░░░░░ 30% (12/40 tasks)
**Frontend**: ██░░░░░░░░ 20% (8/40 tasks)

## Overall Progress
**Backend**: ████░░░░░░ 40%
**Frontend**: ███░░░░░░░ 30%
```

## Estimates by Phase

| Phase | Backend Tasks | Backend Days | Frontend Tasks | Frontend Days |
|-------|--------------|--------------|----------------|---------------|
| 1 | 50 | 30 | 50 | 25 |
| 2 | 40 | 25 | 45 | 20 |
| 3 | 35 | 20 | 40 | 18 |
| 4 | 30 | 15 | 35 | 15 |
| 5 | 25 | 12 | 30 | 12 |
| **Total** | **180** | **102** | **200** | **90** |

**Team Size**: 2 backend + 2 frontend engineers
**Total Duration**: ~26 weeks (6 months)

## Getting Started

1. **Week 1**: 
   - Backend: Event model definition + IdRegistry
   - Frontend: Project setup + type definitions

2. **Week 2**:
   - Backend: EventBus + InstrumentedScope skeleton
   - Frontend: API client + WebSocket hook

3. **Week 3**:
   - Backend: Complete InstrumentedScope (launch, async)
   - Frontend: State management + basic tree view

4. **Week 4**:
   - Backend: InstrumentedDispatcher + TimelineStore
   - Frontend: Timeline component (basic)

Continue following the task lists in order for best results.

## Questions?

For questions about these task lists or implementation guidance, refer to:
- [coroutine-visualizer-backend-chap51.md](../coroutine-visualizer-backend-chap51.md) - Technical architecture deep dive

---

**Last Updated**: November 27, 2025





