package com.jh.proj.coroutineviz.agent.boot;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Child-first class loader over the agent fat jar (the OpenTelemetry java-agent isolation
 * pattern). Written in plain Java with ZERO non-JDK imports so it can be constructed by the
 * {@link VizcoreAgentPremain} shim before any Kotlin/third-party class has been touched.
 *
 * <h2>Why this exists (the 15-13 fix)</h2>
 * A {@code -javaagent:...jar} is appended to the target JVM's <em>system</em> classpath, and
 * {@code -cp} entries precede it. So an exploded-classpath launch (an ordinary IDE run config)
 * resolves the agent's un-relocated runtime deps (ktor-client, kotlinx-serialization,
 * kotlinx-io, kotlin-stdlib) from the TARGET APP's classpath, not the fat jar. The version
 * repro matrix proved this mixed stack mis-reads Content-Length-framed keep-alive HTTP
 * responses into an empty body (a timing-dependent corruption — the exploded path is unsafe
 * even when it appears to work, and version alignment does NOT fix it). Isolating the agent
 * bootstrap in this child-first loader makes the agent's HTTP/serialization stack resolve from
 * the fat jar regardless of {@code -cp} ordering.
 *
 * <h2>Delegation policy</h2>
 * {@link #loadClass(String, boolean)} applies, in order:
 * <ol>
 *   <li>{@code findLoadedClass} — never load a class twice.</li>
 *   <li>{@code java.} / {@code javax.} / {@code jdk.} / {@code sun.} / {@code com.sun.} ->
 *       the platform loader (JDK classes; there is exactly one correct copy).</li>
 *   <li>{@code com.jh.proj.coroutineviz.agent.boot.} -> the system loader. These are the
 *       shim's OWN classes, already defined on the system loader that ran {@code premain};
 *       loading a second copy here would split {@code AgentClassLoader} into two class
 *       identities and break the reflective bridge back to the shim.</li>
 *   <li>{@code kotlin.} and {@code kotlinx.coroutines.} -> SYSTEM-FIRST, child fallback.
 *       DebugProbes must instrument the HOST's kotlinx-coroutines classes — a private child
 *       copy would observe nothing (the Phase-13 relocation lesson), and
 *       {@code Job.invokeOnCompletion} callbacks cross the boundary as
 *       {@code kotlin.jvm.functions.Function1}, which must live in ONE class space. The
 *       child fallback covers pure-Java hosts that ship no Kotlin at all (the agent's own
 *       bundled kotlin-stdlib/coroutines then satisfy agent code).</li>
 *   <li>everything else (io.ktor., kotlinx.serialization., kotlinx.io.,
 *       com.jh.proj.coroutineviz., the shaded slf4j, ...) -> CHILD-FIRST: the agent jar wins,
 *       falling back to the system loader. THIS is the fix — the agent's HTTP/serialization
 *       stack always comes from the fat jar.</li>
 * </ol>
 * {@link #getResource(String)}/{@link #getResources(String)} mirror the child-first bias so
 * ktor/serialization {@code META-INF/services} + module resources resolve from the fat jar.
 *
 * <h2>Documented limitation</h2>
 * Because {@code kotlin.}/{@code kotlinx.coroutines.} stay SHARED with the host, a host whose
 * kotlin-stdlib is OLDER than what the bundled ktor/client code requires may still fail inside
 * agent code paths — an identical failure envelope to today's. Shared-kotlin is a deliberate
 * tradeoff for DebugProbes correctness; the {@link VizcoreAgentPremain} fail-soft catch absorbs
 * any such failure so the host runs uninstrumented rather than aborting.
 */
public final class AgentClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    /**
     * @param agentJar the {@code file:} URL of the agent fat jar (from the shim's own
     *     {@code CodeSource}); the sole search URL for child-first resolution.
     */
    public AgentClassLoader(URL agentJar) {
        // Parent = the platform loader (JDK-only), NOT the system/app loader: that is what
        // makes non-shared packages resolve child-first from the fat jar. Shared packages are
        // reached explicitly via getSystemClassLoader() in loadClass, bypassing the parent.
        super(new URL[] {agentJar}, ClassLoader.getPlatformClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = resolve(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> resolve(String name) throws ClassNotFoundException {
        // JDK classes -> platform loader (the parent). One correct copy, always.
        if (isJdkClass(name)) {
            return getParent().loadClass(name);
        }
        // The shim's own boot package -> the system loader that already defined it. Sharing
        // this identity is what lets the reflective bridge (premain -> AgentBootstrap) work.
        if (name.startsWith("com.jh.proj.coroutineviz.agent.boot.")) {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        }
        // Shared runtime -> system-first so DebugProbes/callback types stay one class space;
        // fall back to the fat jar for pure-Java hosts that ship no Kotlin.
        if (isHostShared(name)) {
            try {
                return ClassLoader.getSystemClassLoader().loadClass(name);
            } catch (ClassNotFoundException notInHost) {
                return findClass(name);
            }
        }
        // Everything else -> CHILD-FIRST from the fat jar, system loader as a fallback.
        try {
            return findClass(name);
        } catch (ClassNotFoundException notLocal) {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        }
    }

    /** JDK-owned packages that must resolve to the single platform/bootstrap copy. */
    private static boolean isJdkClass(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("com.sun.");
    }

    /**
     * Packages that MUST stay shared with the host application (system-first). Only
     * {@code kotlin.} and {@code kotlinx.coroutines.} qualify — NOT {@code kotlinx.serialization.}
     * or {@code kotlinx.io.}, which are agent-bundled and load child-first. The trailing dots
     * matter: {@code "kotlinx.serialization.Foo".startsWith("kotlin.")} is false.
     */
    private static boolean isHostShared(String name) {
        return name.startsWith("kotlin.") || name.startsWith("kotlinx.coroutines.");
    }

    @Override
    public URL getResource(String name) {
        // Child-first: the fat jar's META-INF/services + module resources win, then the JDK
        // platform parent. The host's own resources are never consulted here — this loader is
        // only used by agent code, so its resource view should be the agent jar's.
        URL local = findResource(name);
        if (local != null) {
            return local;
        }
        ClassLoader parent = getParent();
        return (parent != null) ? parent.getResource(name) : null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>();
        Enumeration<URL> local = findResources(name);
        while (local.hasMoreElements()) {
            urls.add(local.nextElement());
        }
        ClassLoader parent = getParent();
        if (parent != null) {
            Enumeration<URL> fromParent = parent.getResources(name);
            while (fromParent.hasMoreElements()) {
                urls.add(fromParent.nextElement());
            }
        }
        return Collections.enumeration(urls);
    }
}
