---
sidebar_position: 1
---

# IntelliJ Plugin Installation

> **Postponed — not part of v1.2.**
> The IntelliJ plugin is parked (#130). It is excluded from the default Gradle build and is
> only configured when a build opts in with `-Pvizcore.withPlugin=true`; CI builds it on
> demand from `.github/workflows/ci-plugin.yml` (`workflow_dispatch`) only. Everything below
> describes work that resumes after v1.2 — it is neither shipped nor supported in v1.2.

The Kotlin Coroutine Visualizer IntelliJ plugin brings visualization directly into your IDE.

## Requirements

- **IntelliJ IDEA** 2024.1 or later (Community or Ultimate)
- **Kotlin plugin** installed and up to date
- **Visualizer backend** running locally or remotely

## Install from Marketplace

1. Open IntelliJ IDEA
2. Go to **Settings/Preferences > Plugins > Marketplace**
3. Search for **Kotlin Coroutine Visualizer**
4. Click **Install**
5. Restart the IDE when prompted

## Install from Disk

If you built the plugin from source:

1. Go to **Settings/Preferences > Plugins**
2. Click the gear icon and select **Install Plugin from Disk...**
3. Select the `.zip` file from `build/distributions/`
4. Restart the IDE

## Building from Source

```bash
cd intellij-plugin
./gradlew buildPlugin
```

The plugin archive will be in `build/distributions/`.

## Verify Installation

After installation, you should see:

- **Coroutine Visualizer** tool window in the right sidebar
- **Coroutine Visualizer** entry in the **View > Tool Windows** menu

## Connecting to the Backend

The plugin connects to the visualizer backend. By default, it expects:

```
http://localhost:8080
```

You can change this in [Plugin Settings](settings).

## Updating

The plugin checks for updates automatically through the JetBrains Marketplace. You can also manually check via **Settings/Preferences > Plugins > Updates**.
