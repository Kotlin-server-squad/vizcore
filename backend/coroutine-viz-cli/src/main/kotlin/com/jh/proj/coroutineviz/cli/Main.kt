package com.jh.proj.coroutineviz.cli

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.checksystem.AntiPatternDetector
import com.jh.proj.coroutineviz.checksystem.EventRecorder
import com.jh.proj.coroutineviz.checksystem.HierarchyValidator
import com.jh.proj.coroutineviz.checksystem.LifecycleValidator
import com.jh.proj.coroutineviz.checksystem.SequenceChecker
import com.jh.proj.coroutineviz.checksystem.StructuredConcurrencyValidator
import com.jh.proj.coroutineviz.checksystem.ValidationResult
import com.jh.proj.coroutineviz.events.AntiPatternSeverity
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.models.RuntimeSnapshot
import com.jh.proj.coroutineviz.session.EventApplier
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.PolymorphicSerializer
import java.io.File
import kotlin.system.exitProcess

/**
 * Exit codes (resolved A2 exit policy):
 * - 0 → clean export: every validator Pass and no ERROR/WARNING anti-pattern (INFO-only is OK).
 * - 1 → at least one ValidationResult.Fail OR an anti-pattern with severity ERROR or WARNING.
 * - 2 → usage error (blank path) or a malformed/undecodable export (V5 — readable error, not a stack trace).
 */
private const val EXIT_CLEAN = 0
private const val EXIT_VIOLATION = 1
private const val EXIT_USAGE = 2

/**
 * Drive the in-core `checksystem` validation engine over a recorded `/events` export
 * (a bare `List<VizEvent>` JSON array) and return a process exit code.
 *
 * Zero rule duplication: every rule comes from the same validators the backend's
 * `ValidationRoutes` uses, decoded via the same core `appJson` the SSE/replay wire uses.
 */
fun runCli(path: String): Int {
    if (path.isBlank()) {
        System.err.println("usage: java -jar coroutine-viz-cli-0.1.0-all.jar <events.json>")
        return EXIT_USAGE
    }

    val file = File(path)
    if (!file.isFile) {
        System.err.println("error: file not found: $path")
        return EXIT_USAGE
    }

    val text = file.readText()
    val events: List<VizEvent> =
        try {
            appJson.decodeFromString(ListSerializer(PolymorphicSerializer(VizEvent::class)), text)
        } catch (e: SerializationException) {
            System.err.println("error: could not parse '$path' as a VizEvent export: ${e.message}")
            return EXIT_USAGE
        }

    // Run the EXACT same validators as ValidationRoutes.kt:49-53. The three validators
    // return a List each; the two SequenceChecker calls each return a single result.
    val results = mutableListOf<ValidationResult>()
    results += LifecycleValidator.validate(events)
    results += HierarchyValidator.validate(events)
    results += StructuredConcurrencyValidator.validate(events)
    results += SequenceChecker.checkNoDuplicateSequenceNumbers(events)
    results += SequenceChecker.checkEventsInExactOrder(events)

    // NEW vs the route (resolved A3/SC#3): also run the AntiPatternDetector. Build the
    // runtime state by replaying the seq-ordered events through an EventApplier and feed
    // an EventRecorder so the detector has both the snapshot and the kind-indexed events.
    val snapshot = RuntimeSnapshot()
    val applier = EventApplier(snapshot)
    val recorder = EventRecorder()
    events.sortedBy { it.seq }.forEach { applier.apply(it) }
    events.forEach { recorder.record(it) }
    val antiPatterns = AntiPatternDetector(snapshot, recorder).detectAll()

    // Print findings, one per line.
    for (result in results) {
        when (result) {
            is ValidationResult.Pass -> println("PASS  ${result.ruleName}: ${result.message}")
            is ValidationResult.Fail -> println("FAIL  ${result.ruleName}: ${result.message} — ${result.details}")
        }
    }
    for (ap in antiPatterns) {
        println("ANTI-PATTERN [${ap.severity}] ${ap.patternType}: ${ap.description}")
    }

    val hasFail = results.any { it is ValidationResult.Fail }
    val hasBlockingAntiPattern =
        antiPatterns.any {
            it.severity == AntiPatternSeverity.ERROR || it.severity == AntiPatternSeverity.WARNING
        }

    return if (hasFail || hasBlockingAntiPattern) EXIT_VIOLATION else EXIT_CLEAN
}

fun main(args: Array<String>) {
    exitProcess(runCli(args.getOrNull(0) ?: ""))
}
