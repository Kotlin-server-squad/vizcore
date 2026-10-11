---
sidebar_position: 3
---

# Plugin Settings

> **Postponed — not part of v1.2.**
> The IntelliJ plugin is parked (#130). It is excluded from the default Gradle build and is
> only configured when a build opts in with `-Pvizcore.withPlugin=true`; CI builds it on
> demand from `.github/workflows/ci-plugin.yml` (`workflow_dispatch`) only. Everything below
> describes work that resumes after v1.2 — it is neither shipped nor supported in v1.2.

Configure the IntelliJ plugin behavior via **Settings/Preferences > Tools > Coroutine Visualizer**.

## Connection Settings

| Setting | Default | Description |
|---|---|---|
| **Backend URL** | `http://localhost:8080` | URL of the visualizer backend |
| **Auto-connect** | `true` | Automatically connect when the tool window opens |
| **Reconnect interval** | `5s` | Time between reconnection attempts |
| **Connection timeout** | `10s` | Maximum time to wait for initial connection |

## Display Settings

| Setting | Default | Description |
|---|---|---|
| **Tree refresh rate** | `100ms` | How often the tree panel re-renders |
| **Timeline resolution** | `10ms` | Minimum time granularity on the timeline |
| **Max visible events** | `10000` | Limit events shown in the event panel |
| **Compact mode** | `false` | Reduce visual density for smaller screens |

## Data Settings

| Setting | Default | Description |
|---|---|---|
| **Event retention** | `30min` | How long to keep events in memory |
| **Auto-clear on disconnect** | `false` | Clear local event cache when connection drops |
| **Export format** | `JSON` | Default format for event exports |

## Notification Settings

| Setting | Default | Description |
|---|---|---|
| **Show connection notifications** | `true` | Notify on connect/disconnect |
| **Show validation alerts** | `true` | Show balloon alerts for validation failures |
| **Alert severity threshold** | `Warning` | Minimum severity to trigger alerts |

## Resetting to Defaults

Click **Reset to Defaults** at the bottom of the settings page to restore all settings to their original values.

## Settings Storage

Settings are stored per-project in `.idea/coroutineVisualizer.xml`. They can be shared with your team by including this file in version control.
