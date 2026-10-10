# IntelliJ Plugin Implementation Guide

> **Postponed — not part of v1.2.**
> The IntelliJ plugin is parked (#130). It is excluded from the default Gradle build and is
> only configured when a build opts in with `-Pvizcore.withPlugin=true`; CI builds it on
> demand from `.github/workflows/ci-plugin.yml` (`workflow_dispatch`) only. Everything below
> describes work that resumes after v1.2 — it is neither shipped nor supported in v1.2.

## Overview
This guide explains how to create the Coroutine Visualizer as an IntelliJ IDEA plugin.

## Prerequisites
- IntelliJ IDEA (Community or Ultimate)
- Plugin DevKit plugin enabled
- JDK 17+
- Kotlin plugin

## Getting Started

### 1. Create Plugin Project

```bash
# Using IntelliJ IDEA
File → New → Project → IDE Plugin
# Or use Gradle IntelliJ Plugin
```

**build.gradle.kts:**
```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "com.jh.coroutine-visualizer"
version = "1.0.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3")
    type.set("IC") // IC = Community, IU = Ultimate
    plugins.set(listOf("org.jetbrains.kotlin"))
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.7.3")
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("241.*")
    }
}
```

### 2. Plugin Configuration

**src/main/resources/META-INF/plugin.xml:**
```xml
<idea-plugin>
    <id>com.jh.coroutine-visualizer</id>
    <name>Coroutine Visualizer</name>
    <vendor email="your@email.com" url="https://github.com/yourrepo">Your Name</vendor>
    
    <description><![CDATA[
        Real-time visualization of Kotlin coroutines during development and debugging.
        Shows coroutine hierarchy, state transitions, dispatcher activity, and thread usage.
    ]]></description>
    
    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.kotlin</depends>
    
    <extensions defaultExtensionNs="com.intellij">
        <!-- Tool Window -->
        <toolWindow 
            id="Coroutine Visualizer" 
            anchor="bottom" 
            factoryClass="com.jh.coroutinevisualizer.ui.CoroutineVisualizerToolWindowFactory"
            icon="/icons/coroutine.svg"/>
        
        <!-- Run Configuration Extension -->
        <programRunner implementation="com.jh.coroutinevisualizer.runner.CoroutineDebugRunner"/>
        
        <!-- Debugger Support -->
        <xdebugger.breakpointType 
            implementation="com.jh.coroutinevisualizer.debug.CoroutineBreakpointType"/>
    </extensions>
    
    <actions>
        <action 
            id="CoroutineVisualizer.StartVisualization"
            class="com.jh.coroutinevisualizer.actions.StartVisualizationAction"
            text="Start Coroutine Visualization"
            description="Enable coroutine visualization for this run"
            icon="/icons/play.svg">
            <add-to-group group-id="RunMenu" anchor="last"/>
        </action>
    </actions>
    
    <projectListeners>
        <listener 
            class="com.jh.coroutinevisualizer.listeners.CoroutineEventListener"
            topic="com.intellij.execution.ExecutionListener"/>
    </projectListeners>
</idea-plugin>
```

### 3. Core Components

#### A. Coroutine Event Capture

**CoroutineDebugAgent.kt:**
```kotlin
package com.jh.coroutinevisualizer.agent

import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.debug.internal.DebugCoroutineInfo

class CoroutineDebugAgent {
    private val eventListeners = mutableListOf<CoroutineEventListener>()
    
    fun start() {
        // Install debug probes
        DebugProbes.install()
        DebugProbes.enableCreationStackTraces = true
        
        // Start polling for coroutine state
        startMonitoring()
    }
    
    private fun startMonitoring() {
        // Poll DebugProbes periodically
        timer.scheduleAtFixedRate(100) {
            val coroutines = DebugProbes.dumpCoroutinesInfo()
            processCoroutines(coroutines)
        }
    }
    
    private fun processCoroutines(coroutines: List<DebugCoroutineInfo>) {
        coroutines.forEach { info ->
            val event = CoroutineEvent(
                id = info.sequenceNumber,
                name = info.context[CoroutineName]?.name ?: "coroutine",
                state = info.state,
                dispatcher = info.context[CoroutineDispatcher]?.toString(),
                creationStackTrace = info.creationStackTrace
            )
            notifyListeners(event)
        }
    }
    
    fun stop() {
        DebugProbes.uninstall()
    }
}
```

#### B. Data Model

**CoroutineTreeNode.kt:**
```kotlin
package com.jh.coroutinevisualizer.model

data class CoroutineInfo(
    val id: Long,
    val name: String,
    val state: String,
    val dispatcher: String?,
    val parentId: Long?,
    val children: MutableList<CoroutineInfo> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)

class CoroutineTreeModel {
    private val coroutines = mutableMapOf<Long, CoroutineInfo>()
    private val listeners = mutableListOf<() -> Unit>()
    
    fun update(info: CoroutineInfo) {
        coroutines[info.id] = info
        notifyListeners()
    }
    
    fun getRoots(): List<CoroutineInfo> {
        return coroutines.values.filter { it.parentId == null }
    }
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
```

#### C. Tool Window UI

**CoroutineVisualizerToolWindowFactory.kt:**
```kotlin
package com.jh.coroutinevisualizer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CoroutineVisualizerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val visualizerPanel = CoroutineVisualizerPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(visualizerPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
```

**CoroutineVisualizerPanel.kt:**
```kotlin
package com.jh.coroutinevisualizer.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class CoroutineVisualizerPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Coroutines"))
    private val tree = Tree(treeModel)
    
    init {
        setupUI()
        setupListeners()
    }
    
    private fun setupUI() {
        // Split pane: tree on left, details on right
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(tree),
            createDetailsPanel()
        )
        splitPane.dividerLocation = 300
        add(splitPane, BorderLayout.CENTER)
        
        // Toolbar
        add(createToolbar(), BorderLayout.NORTH)
    }
    
    private fun createToolbar(): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("Start").apply {
                addActionListener { startVisualization() }
            })
            add(JButton("Stop").apply {
                addActionListener { stopVisualization() }
            })
            add(JButton("Clear").apply {
                addActionListener { clearTree() }
            })
        }
    }
    
    private fun createDetailsPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            add(JLabel("Select a coroutine to view details"), BorderLayout.CENTER)
        }
    }
    
    fun updateTree(rootCoroutines: List<CoroutineInfo>) {
        val root = DefaultMutableTreeNode("Coroutines (${rootCoroutines.size})")
        rootCoroutines.forEach { coroutine ->
            root.add(createNode(coroutine))
        }
        treeModel.setRoot(root)
        treeModel.reload()
    }
    
    private fun createNode(info: CoroutineInfo): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(
            "${info.name} [${info.state}] @ ${info.dispatcher ?: "?"}"
        )
        info.children.forEach { child ->
            node.add(createNode(child))
        }
        return node
    }
}
```

#### D. Run Configuration Integration

**CoroutineDebugRunner.kt:**
```kotlin
package com.jh.coroutinevisualizer.runner

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.GenericProgramRunner
import org.jetbrains.kotlin.idea.run.KotlinRunConfiguration

class CoroutineDebugRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "CoroutineDebugRunner"
    
    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        return executorId == DefaultDebugExecutor.EXECUTOR_ID && 
               profile is KotlinRunConfiguration
    }
    
    override fun execute(environment: ExecutionEnvironment) {
        // Add VM options for coroutine debugging
        val javaParameters = (environment.runProfile as KotlinRunConfiguration)
            .getJavaParameters()
        
        javaParameters.vmParametersList.apply {
            add("-Dkotlinx.coroutines.debug=on")
            add("-ea") // Enable assertions
        }
        
        // Start the agent
        CoroutineDebugAgent.instance.start()
        
        super.execute(environment)
    }
}
```

### 4. Advanced Features

#### A. Integration with DebugProbes

```kotlin
import kotlinx.coroutines.debug.DebugProbes

// Get all active coroutines
val coroutines = DebugProbes.dumpCoroutinesInfo()

// Get job hierarchy
val jobHierarchy = DebugProbes.printJob(job)

// Get detailed state
coroutines.forEach { info ->
    println("Coroutine #${info.sequenceNumber}")
    println("  State: ${info.state}")
    println("  Context: ${info.context}")
    println("  Stack trace:")
    info.lastObservedStackTrace().forEach { frame ->
        println("    $frame")
    }
}
```

#### B. Custom Breakpoints

```kotlin
class CoroutineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    "coroutine-state-breakpoint",
    "Coroutine State Breakpoint"
) {
    override fun createBreakpointProperties(
        file: VirtualFile,
        line: Int
    ): XBreakpointProperties<*>? {
        return CoroutineBreakpointProperties()
    }
}

class CoroutineBreakpointProperties : XBreakpointProperties<CoroutineBreakpointProperties>() {
    var breakOnState: String = "SUSPENDED"
    var coroutineName: String? = null
}
```

### 5. Building and Testing

```bash
# Build the plugin
./gradlew buildPlugin

# Run in sandbox IDE
./gradlew runIde

# Test
./gradlew test

# Publish to JetBrains Marketplace
./gradlew publishPlugin
```

### 6. Installation

**From Marketplace:**
1. Settings → Plugins → Marketplace
2. Search "Coroutine Visualizer"
3. Install & Restart

**From Disk:**
1. Build: `./gradlew buildPlugin`
2. Settings → Plugins → ⚙️ → Install Plugin from Disk
3. Select `build/distributions/plugin.zip`

## Key Advantages

1. **Zero Configuration** - Works automatically with any Kotlin project
2. **Live Updates** - See coroutines as they run
3. **Debugger Integration** - Set breakpoints on coroutine state changes
4. **Source Navigation** - Click to jump to coroutine creation site
5. **Performance Profiling** - Built-in timing and thread usage stats

## Migration from Web App

Your existing backend logic can be reused:
- Event models → Plugin data models
- Event processing → DebugProbes integration  
- REST endpoints → Plugin services
- WebSocket → IntelliJ event bus

## Resources

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [Kotlin Coroutines Debug](https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-debug)
- [Plugin Development Forum](https://intellij-support.jetbrains.com/hc/en-us/community/topics/200366979-IntelliJ-IDEA-Open-API-and-Plugin-Development)
- [Example Plugins](https://github.com/JetBrains/intellij-sdk-code-samples)

## Next Steps

1. **Prototype**: Create basic tool window with tree view
2. **Integration**: Hook into DebugProbes
3. **Polish**: Add timeline view, thread lanes, etc.
4. **Publish**: Submit to JetBrains Marketplace

This would be a valuable tool for the Kotlin community! 🚀





