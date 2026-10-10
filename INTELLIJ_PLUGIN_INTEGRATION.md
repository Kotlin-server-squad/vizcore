# IntelliJ Plugin with Your Custom Instrumentation

> **Postponed — not part of v1.2.**
> The IntelliJ plugin is parked (#130). It is excluded from the default Gradle build and is
> only configured when a build opts in with `-Pvizcore.withPlugin=true`; CI builds it on
> demand from `.github/workflows/ci-plugin.yml` (`workflow_dispatch`) only. Everything below
> describes work that resumes after v1.2 — it is neither shipped nor supported in v1.2.

## Strategy: Hybrid Approach

Your existing `VizSession`, `VizScope`, `vizLaunch`, `vizAsync` code is **more powerful** than DebugProbes alone. Here's how to combine both:

### **Approach 1: Your Code (Instrumented) - PRIMARY**
✅ **Use for code the user actively instruments**
- Detailed events: Created, Started, Suspended, Resumed, Completed
- Precise parent-child tracking
- Thread assignments
- Custom labels
- Job state monitoring

### **Approach 2: DebugProbes - FALLBACK**
✅ **Use for non-instrumented coroutines**
- Automatic detection of ALL coroutines (even non-VizScope ones)
- Baseline coroutine discovery
- Stack traces

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   IntelliJ Plugin                       │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │          Tool Window (Swing UI)                  │  │
│  │  - Tree View  - Timeline  - Thread Lanes         │  │
│  └──────────────────────────────────────────────────┘  │
│                          ↑                              │
│  ┌──────────────────────────────────────────────────┐  │
│  │      Coroutine Data Aggregator                   │  │
│  │  Merges: VizEvents + DebugProbes data           │  │
│  └──────────────────────────────────────────────────┘  │
│           ↑                            ↑                │
│  ┌────────────────┐          ┌────────────────┐        │
│  │  VizSession    │          │  DebugProbes   │        │
│  │  EventBus      │          │  Polling       │        │
│  │  (Real-time)   │          │  (Fallback)    │        │
│  └────────────────┘          └────────────────┘        │
└─────────────────────────────────────────────────────────┘
                         ↓
              ┌──────────────────────┐
              │  User's Application  │
              │  (Running in IDE)    │
              └──────────────────────┘
```

## Implementation Steps

### Step 1: Package Your Code as a Library

Create a new Gradle module for the instrumentation library:

**settings.gradle.kts:**
```kotlin
rootProject.name = "coroutine-visualizer"

include("instrumentation-lib")  // Your existing backend code
include("intellij-plugin")       // New plugin module
```

**instrumentation-lib/build.gradle.kts:**
```kotlin
plugins {
    kotlin("jvm") version "1.9.22"
    `maven-publish`
}

group = "com.jh.coroutine-visualizer"
version = "1.0.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.slf4j:slf4j-api:2.0.9")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
```

**What goes in instrumentation-lib:**
```
instrumentation-lib/src/main/kotlin/
├── com.jh.coroutineviz/
│   ├── session/
│   │   ├── VizSession.kt        ✅ Keep
│   │   ├── EventBus.kt          ✅ Keep
│   │   ├── EventStore.kt        ✅ Keep
│   │   ├── RuntimeSnapshot.kt   ✅ Keep
│   │   └── JobStatusMonitor.kt  ✅ Keep
│   ├── wrappers/
│   │   ├── VizScope.kt          ✅ Keep
│   │   └── VizCoroutineElement.kt ✅ Keep
│   ├── events/
│   │   └── VizEvent.kt          ✅ Keep (all events)
│   └── api/
│       └── PluginConnector.kt   ✨ NEW - Bridge to plugin
```

### Step 2: Create Plugin Connector (Bridge)

**instrumentation-lib/.../api/PluginConnector.kt:**
```kotlin
package com.jh.coroutineviz.api

import com.jh.coroutineviz.events.VizEvent
import com.jh.coroutineviz.session.VizSession
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridge between instrumentation library and IntelliJ plugin.
 * Allows plugin to discover and subscribe to active VizSessions.
 */
object PluginConnector {
    private val activeSessions = CopyOnWriteArrayList<VizSession>()
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    /**
     * Register a VizSession so plugin can discover it.
     * Call this when creating a VizSession in user code.
     */
    fun registerSession(session: VizSession) {
        activeSessions.add(session)
        listeners.forEach { it.onSessionCreated(session) }
    }

    /**
     * Unregister when session is closed.
     */
    fun unregisterSession(session: VizSession) {
        activeSessions.remove(session)
        listeners.forEach { it.onSessionClosed(session) }
    }

    /**
     * Get all currently active sessions.
     */
    fun getActiveSessions(): List<VizSession> = activeSessions.toList()

    /**
     * Plugin registers a listener to be notified of new sessions.
     */
    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    interface SessionListener {
        fun onSessionCreated(session: VizSession)
        fun onSessionClosed(session: VizSession)
    }
}

/**
 * Extension to VizSession to auto-register with plugin.
 */
fun VizSession.enablePluginIntegration(): VizSession {
    PluginConnector.registerSession(this)
    return this
}
```

**Updated VizSession.kt:**
```kotlin
class VizSession(
    val sessionId: String,
    autoRegisterPlugin: Boolean = true  // ✨ NEW
) {
    // ... existing code ...
    
    init {
        if (autoRegisterPlugin) {
            PluginConnector.registerSession(this)
        }
    }
    
    fun close() {
        jobMonitor.stop()
        sessionScope.cancel()
        PluginConnector.unregisterSession(this)  // ✨ NEW
    }
}
```

### Step 3: IntelliJ Plugin Module

**intellij-plugin/build.gradle.kts:**
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
    type.set("IC")
    plugins.set(listOf("org.jetbrains.kotlin"))
}

dependencies {
    // ✨ Your instrumentation library
    implementation(project(":instrumentation-lib"))
    
    // ✨ Coroutines debug for fallback
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.7.3")
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("241.*")
    }
    
    // ✨ Bundle instrumentation-lib.jar with plugin
    prepareSandbox {
        doLast {
            copy {
                from(project(":instrumentation-lib").tasks.jar)
                into("$destinationDir/${pluginName.get()}/lib")
            }
        }
    }
}
```

### Step 4: Plugin Core - Event Aggregator

**intellij-plugin/.../CoroutineDataAggregator.kt:**
```kotlin
package com.jh.coroutinevisualizer.core

import com.intellij.openapi.project.Project
import com.jh.coroutineviz.api.PluginConnector
import com.jh.coroutineviz.events.*
import com.jh.coroutineviz.session.VizSession
import kotlinx.coroutines.*
import kotlinx.coroutines.debug.DebugProbes
import java.util.concurrent.ConcurrentHashMap

class CoroutineDataAggregator(
    private val project: Project
) : PluginConnector.SessionListener {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val coroutineData = ConcurrentHashMap<String, CoroutineInfo>()
    private val activeSessions = mutableListOf<VizSession>()
    private val listeners = mutableListOf<DataUpdateListener>()
    
    private var debugProbesEnabled = false
    private var pollingJob: Job? = null
    
    fun start() {
        // Register for new VizSessions
        PluginConnector.addListener(this)
        
        // Subscribe to existing sessions
        PluginConnector.getActiveSessions().forEach { session ->
            subscribeToSession(session)
        }
        
        // Enable DebugProbes as fallback
        enableDebugProbes()
        
        // Start polling DebugProbes for non-instrumented coroutines
        startDebugProbesPolling()
    }
    
    fun stop() {
        PluginConnector.removeListener(this)
        pollingJob?.cancel()
        if (debugProbesEnabled) {
            DebugProbes.uninstall()
            debugProbesEnabled = false
        }
        scope.cancel()
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // VizSession Integration (PRIMARY SOURCE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    override fun onSessionCreated(session: VizSession) {
        subscribeToSession(session)
    }
    
    override fun onSessionClosed(session: VizSession) {
        activeSessions.remove(session)
    }
    
    private fun subscribeToSession(session: VizSession) {
        activeSessions.add(session)
        
        // Subscribe to event stream
        scope.launch {
            session.eventBus.stream().collect { event ->
                handleVizEvent(event)
            }
        }
    }
    
    private fun handleVizEvent(event: VizEvent) {
        when (event) {
            is CoroutineCreated -> {
                coroutineData[event.coroutineId] = CoroutineInfo(
                    id = event.coroutineId,
                    name = event.label ?: "coroutine",
                    state = "CREATED",
                    parentId = event.parentCoroutineId,
                    source = DataSource.INSTRUMENTED,
                    createdAt = event.tsNanos,
                    lastUpdated = event.tsNanos
                )
            }
            
            is CoroutineStarted -> {
                coroutineData[event.coroutineId]?.apply {
                    state = "ACTIVE"
                    lastUpdated = event.tsNanos
                }
            }
            
            is CoroutineSuspended -> {
                coroutineData[event.coroutineId]?.apply {
                    state = "SUSPENDED"
                    lastSuspensionReason = event.reason
                    lastUpdated = event.tsNanos
                }
            }
            
            is CoroutineResumed -> {
                coroutineData[event.coroutineId]?.apply {
                    state = "ACTIVE"
                    lastUpdated = event.tsNanos
                }
            }
            
            is CoroutineCompleted -> {
                coroutineData[event.coroutineId]?.apply {
                    state = "COMPLETED"
                    lastUpdated = event.tsNanos
                }
            }
            
            is CoroutineCancelled -> {
                coroutineData[event.coroutineId]?.apply {
                    state = "CANCELLED"
                    cancellationReason = event.reason
                    lastUpdated = event.tsNanos
                }
            }
            
            is ThreadAssigned -> {
                coroutineData[event.coroutineId]?.apply {
                    threadName = event.threadName
                    dispatcher = event.dispatcherName
                    lastUpdated = event.tsNanos
                }
            }
        }
        
        notifyListeners()
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // DebugProbes Integration (FALLBACK)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    private fun enableDebugProbes() {
        if (!debugProbesEnabled) {
            try {
                DebugProbes.install()
                DebugProbes.enableCreationStackTraces = true
                debugProbesEnabled = true
            } catch (e: Exception) {
                // Already installed or not available
            }
        }
    }
    
    private fun startDebugProbesPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    if (debugProbesEnabled) {
                        val coroutines = DebugProbes.dumpCoroutinesInfo()
                        coroutines.forEach { info ->
                            val id = "debug-probe-${info.sequenceNumber}"
                            
                            // Only add if NOT already tracked by VizSession
                            if (!coroutineData.containsKey(id)) {
                                coroutineData[id] = CoroutineInfo(
                                    id = id,
                                    name = info.context[kotlinx.coroutines.CoroutineName]?.name 
                                        ?: "coroutine-${info.sequenceNumber}",
                                    state = info.state,
                                    source = DataSource.DEBUG_PROBES,
                                    createdAt = System.nanoTime(),
                                    lastUpdated = System.nanoTime()
                                )
                            }
                        }
                        notifyListeners()
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
                delay(200) // Poll every 200ms
            }
        }
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Public API
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    fun getAllCoroutines(): List<CoroutineInfo> = coroutineData.values.toList()
    
    fun getRootCoroutines(): List<CoroutineInfo> {
        return coroutineData.values.filter { it.parentId == null }
    }
    
    fun addListener(listener: DataUpdateListener) {
        listeners.add(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it.onDataUpdated() }
    }
    
    interface DataUpdateListener {
        fun onDataUpdated()
    }
}

data class CoroutineInfo(
    val id: String,
    var name: String,
    var state: String,
    val parentId: String? = null,
    val source: DataSource,
    val createdAt: Long,
    var lastUpdated: Long,
    var threadName: String? = null,
    var dispatcher: String? = null,
    var lastSuspensionReason: String? = null,
    var cancellationReason: String? = null
)

enum class DataSource {
    INSTRUMENTED,    // From your VizSession
    DEBUG_PROBES     // From DebugProbes (fallback)
}
```

### Step 5: Auto-inject Library into User's Classpath

**RunConfigurationPatcher.kt:**
```kotlin
package com.jh.coroutinevisualizer.runner

import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.application.PathManager
import java.io.File

class RunConfigurationPatcher {
    
    fun patchRunConfiguration(javaParameters: JavaParameters) {
        // Add instrumentation-lib.jar to classpath
        val pluginPath = PathManager.getPluginsPath()
        val libPath = File(pluginPath, "coroutine-visualizer/lib/instrumentation-lib.jar")
        
        if (libPath.exists()) {
            javaParameters.classPath.add(libPath)
        }
        
        // Enable coroutines debug
        javaParameters.vmParametersList.apply {
            add("-Dkotlinx.coroutines.debug=on")
            add("-ea")
        }
    }
}
```

### Step 6: User Code (How Developers Use It)

**Before (Your current web app approach):**
```kotlin
fun main() = runBlocking {
    val session = VizSession("my-session")
    val scope = VizScope(session)
    
    scope.vizLaunch("parent") {
        vizLaunch("child-1") { delay(1000) }
        vizLaunch("child-2") { delay(1500) }
    }
}
```

**After (With plugin - SAME CODE!):**
```kotlin
fun main() = runBlocking {
    // ✨ Plugin automatically detects this
    val session = VizSession("my-session")
    val scope = VizScope(session)
    
    scope.vizLaunch("parent") {
        vizLaunch("child-1") { delay(1000) }
        vizLaunch("child-2") { delay(1500) }
    }
    
    // Tool window in IntelliJ shows real-time visualization!
}
```

**Mixed instrumented + non-instrumented:**
```kotlin
fun main() = runBlocking {
    val session = VizSession("my-session")
    val scope = VizScope(session)
    
    // ✅ Tracked with full detail (VizSession events)
    scope.vizLaunch("instrumented") {
        delay(1000)
    }
    
    // ✅ Also visible (via DebugProbes fallback)
    launch {
        delay(1000)
    }
}
```

## Key Advantages

### ✅ **Best of Both Worlds**
1. **Your VizSession**: Precise, detailed, real-time events
2. **DebugProbes**: Automatic fallback for non-instrumented code

### ✅ **Zero Configuration**
- Plugin auto-injects your library
- Automatically discovers VizSessions
- No manual setup required

### ✅ **Seamless Migration**
- Your existing code works as-is
- No web server needed
- All in the IDE

### ✅ **Progressive Enhancement**
- Start with DebugProbes (basic view)
- Add VizScope to code you want detailed tracking
- Get richer visualization automatically

## Development Workflow

```bash
# 1. Build instrumentation library
cd instrumentation-lib
./gradlew publishToMavenLocal

# 2. Build plugin
cd ../intellij-plugin
./gradlew buildPlugin

# 3. Test in sandbox IDE
./gradlew runIde

# 4. In sandbox IDE, run your coroutine code
# Tool window automatically appears at bottom
```

## Next Steps

1. **Package your existing backend** as `instrumentation-lib`
2. **Create plugin module** with Tool Window
3. **Connect via PluginConnector** bridge
4. **Test with your existing examples** from VizEventMain.kt

Would you like me to help you scaffold this structure? I can:
1. Restructure your project into library + plugin modules
2. Create the PluginConnector bridge
3. Set up the basic Tool Window with tree view





