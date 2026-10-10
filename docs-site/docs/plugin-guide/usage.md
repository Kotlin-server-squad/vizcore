---
sidebar_position: 2
---

# Plugin Usage

> **Postponed — not part of v1.2.**
> The IntelliJ plugin is parked (#130). It is excluded from the default Gradle build and is
> only configured when a build opts in with `-Pvizcore.withPlugin=true`; CI builds it on
> demand from `.github/workflows/ci-plugin.yml` (`workflow_dispatch`) only. Everything below
> describes work that resumes after v1.2 — it is neither shipped nor supported in v1.2.

The IntelliJ plugin provides a tool window with multiple panels for visualizing coroutine execution.

## Opening the Tool Window

- Click **Coroutine Visualizer** in the right sidebar, or
- Go to **View > Tool Windows > Coroutine Visualizer**

The tool window opens at the bottom or side of the IDE.

## Connecting to a Session

1. Ensure the backend is running
2. The plugin auto-discovers available sessions
3. Select a session from the dropdown at the top of the tool window
4. Events begin streaming immediately

## Tree Panel

Displays the coroutine hierarchy as a collapsible tree.

- **Coroutine nodes** show name, state, and dispatcher
- **State icons** indicate active, suspended, completed, cancelled, or failed
- **Double-click** a coroutine to navigate to its source code (when available)
- **Right-click** for context actions (cancel, inspect, copy ID)

## Timeline Panel

A compact timeline view showing coroutine events over time.

- **Horizontal bars** represent coroutine lifetimes
- **Color-coded** by state transitions
- **Tooltip** on hover shows event details
- **Scroll** to zoom in/out on the time axis

## Event Panel

A chronological list of all events in the session.

- **Filter** by event type, coroutine, or thread
- **Search** for specific events
- **Click** an event to highlight the related coroutine in the tree panel
- **Export** events as JSON for offline analysis

## Gutter Icons

When viewing Kotlin files with instrumented coroutine code, gutter icons appear:

- **Green play icon** — Run this scope as a visualization scenario
- **Eye icon** — Jump to the coroutine in the visualizer

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+Shift+V` | Toggle tool window |
| `Ctrl+Shift+R` | Refresh session |
| `Escape` | Clear selection |
