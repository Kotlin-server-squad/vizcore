package com.jh.proj.coroutineviz.agent.boot;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Dependency-free Java {@code premain} shim — the fat jar's {@code Premain-Class} (15-13).
 *
 * <p>The JVM invokes {@link #premain(String, Instrumentation)} before the target application's
 * {@code main}. Nothing outside {@code java.*} may be touched here <em>before</em> the isolating
 * {@link AgentClassLoader} exists — that is the whole point of a plain-Java shim: it constructs
 * the child-first loader over the agent fat jar and reflectively hands control to the Kotlin
 * {@code AgentBootstrap} <em>through</em> that loader, so the agent's HTTP/serialization stack
 * resolves from the fat jar rather than the target app's system classpath (the 15-13 fix).
 *
 * <p><b>Fail-soft contract (preserved verbatim from 8c7d67c):</b> the ENTIRE body is wrapped in
 * {@code try/catch(Throwable)}. A propagated {@code premain} exception aborts the host JVM with
 * "processing of -javaagent failed" (the Phase-15 UAT blocker). On any failure we print the
 * established {@code [coroutine-viz-agent] DISABLED — bootstrap failed: ...} line to stderr and
 * return normally; the host then runs uninstrumented.
 */
public final class VizcoreAgentPremain {

    /** FQN of the Kotlin bootstrap object exposing {@code @JvmStatic void run(String)}. */
    private static final String BOOTSTRAP_FQN = "com.jh.proj.coroutineviz.agent.AgentBootstrap";

    private VizcoreAgentPremain() {
    }

    /**
     * Java agent entry point (called by the JVM at launch, never by app code).
     *
     * @param agentArgs the raw {@code =}-suffixed arg string from {@code -javaagent:jar=<here>}
     * @param inst the JVM-supplied instrumentation handle (unused — we transform no bytecode)
     */
    @SuppressWarnings("unused")
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            URL location = locateAgentJar();
            if (location != null && isJar(location)) {
                // Fat-jar launch: isolate the bootstrap in the child-first loader.
                AgentClassLoader loader = new AgentClassLoader(location);
                runThroughLoader(loader, agentArgs);
            } else {
                // classes-dir launch (unit tests / IDE-exploded agent module): isolation is a
                // fat-jar concern, so invoke AgentBootstrap directly on the current loader.
                Class<?> bootstrap = Class.forName(BOOTSTRAP_FQN);
                bootstrap.getMethod("run", String.class).invoke(null, agentArgs);
            }
        } catch (Throwable failure) {
            // FAIL SOFT — the host must NEVER die. Log once, run uninstrumented.
            System.err.println("[coroutine-viz-agent] DISABLED — bootstrap failed: " + failure);
        }
    }

    /**
     * Reflectively invoke {@code AgentBootstrap.run} loaded THROUGH the child-first loader,
     * with the thread context classloader set to the same loader for the duration of the call
     * so ktor/serialization {@code ServiceLoader} lookups (and any coroutine dispatcher threads
     * spawned during startup) inherit the fat-jar view rather than the host's.
     */
    private static void runThroughLoader(AgentClassLoader loader, String agentArgs) throws Exception {
        Class<?> bootstrap = Class.forName(BOOTSTRAP_FQN, true, loader);
        Method run = bootstrap.getMethod("run", String.class);
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        try {
            current.setContextClassLoader(loader);
            run.invoke(null, agentArgs);
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    /** Locate the agent jar (or classes dir) via this shim's own protection domain. */
    private static URL locateAgentJar() {
        ProtectionDomain domain = VizcoreAgentPremain.class.getProtectionDomain();
        CodeSource source = (domain != null) ? domain.getCodeSource() : null;
        return (source != null) ? source.getLocation() : null;
    }

    /** True when the code source is a real {@code file:...jar} (vs an exploded classes dir). */
    private static boolean isJar(URL location) {
        return "file".equals(location.getProtocol()) && location.getPath().endsWith(".jar");
    }
}
