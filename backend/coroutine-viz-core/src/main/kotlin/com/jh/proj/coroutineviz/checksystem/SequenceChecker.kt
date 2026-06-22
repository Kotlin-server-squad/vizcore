package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent

/**
 * Validates event ordering and sequence number integrity.
 *
 * Checks that events appear in the expected order and that
 * sequence numbers are unique across the event stream.
 */
object SequenceChecker {
    /**
     * Check that event types appear in the expected order within the event list.
     *
     * This does not require the events to be strictly contiguous -- other event
     * types may appear between the expected ones. It only verifies that the
     * relative order of the specified kinds is preserved.
     *
     * @param events The event list to check (should be sorted by sequence number)
     * @param expectedSequence List of event kind strings in expected order
     * @return [ValidationResult.Pass] if order is correct, [ValidationResult.Fail] otherwise
     */
    fun checkOrdering(
        events: List<VizEvent>,
        expectedSequence: List<String>,
    ): ValidationResult {
        val ruleName = "EventOrdering"

        if (expectedSequence.isEmpty()) {
            return ValidationResult.Pass(ruleName, "Empty expected sequence, nothing to check")
        }

        val sortedEvents = events.sortedBy { it.seq }
        var expectedIndex = 0

        for (event in sortedEvents) {
            if (expectedIndex < expectedSequence.size && event.kind == expectedSequence[expectedIndex]) {
                expectedIndex++
            }
        }

        return if (expectedIndex == expectedSequence.size) {
            ValidationResult.Pass(
                ruleName,
                "All ${expectedSequence.size} expected event types found in correct order",
            )
        } else {
            val missing = expectedSequence.subList(expectedIndex, expectedSequence.size)
            ValidationResult.Fail(
                ruleName,
                "Event ordering violation",
                "Expected event types not found in order. " +
                    "Matched $expectedIndex/${expectedSequence.size}. " +
                    "Missing or out-of-order: $missing",
            )
        }
    }

    /**
     * Check that events were recorded in EXACT sequential order: as they appear in the stream
     * (store/append order), each event's sequence number is strictly greater than the previous
     * one — i.e. no reordering and no duplicate sequence numbers.
     *
     * Sequence numbers are intentionally NOT required to be contiguous: [VizSession.nextSeq] also
     * mints coroutine / scope / semaphore ids, so some sequence values are deliberately not events.
     * This validates "events were sent in exact order" (the recorded order matches the sequence the
     * session assigned), as opposed to [checkOrdering] which checks against a caller-supplied
     * expected sequence of event kinds.
     *
     * @param events The event list to check, in stream (store) order
     * @return [ValidationResult.Pass] if strictly increasing, [ValidationResult.Fail] otherwise
     */
    fun checkEventsInExactOrder(events: List<VizEvent>): ValidationResult {
        val ruleName = "EventsInExactOrder"

        if (events.size < 2) {
            return ValidationResult.Pass(
                ruleName,
                "Fewer than 2 events; exact ordering trivially holds (${events.size} event(s))",
            )
        }

        val violations = mutableListOf<String>()
        for (i in 1 until events.size) {
            val prev = events[i - 1]
            val cur = events[i]
            if (cur.seq <= prev.seq) {
                violations.add(
                    "position $i: ${cur.kind}(seq=${cur.seq}) does not strictly follow " +
                        "${prev.kind}(seq=${prev.seq})",
                )
            }
        }

        return if (violations.isEmpty()) {
            ValidationResult.Pass(
                ruleName,
                "All ${events.size} events are recorded in exact sequential order " +
                    "(strictly increasing sequence numbers, no reordering or duplicates)",
            )
        } else {
            ValidationResult.Fail(
                ruleName,
                "Events are not in exact sequential order",
                "Found ${violations.size} ordering violation(s): " +
                    violations.take(10).joinToString("; ") +
                    if (violations.size > 10) " …(+${violations.size - 10} more)" else "",
            )
        }
    }

    /**
     * Check that no two events share the same sequence number.
     *
     * Duplicate sequence numbers indicate a bug in the event emission logic,
     * as each event should have a globally unique sequence number within a session.
     *
     * @param events The event list to check
     * @return [ValidationResult.Pass] if all sequence numbers are unique,
     *         [ValidationResult.Fail] if duplicates are found
     */
    fun checkNoDuplicateSequenceNumbers(events: List<VizEvent>): ValidationResult {
        val ruleName = "NoDuplicateSequenceNumbers"

        val seqCounts = events.groupBy { it.seq }.filter { it.value.size > 1 }

        return if (seqCounts.isEmpty()) {
            ValidationResult.Pass(
                ruleName,
                "All ${events.size} events have unique sequence numbers",
            )
        } else {
            val duplicates =
                seqCounts.map { (seq, evts) ->
                    "seq=$seq appears ${evts.size} times (kinds: ${evts.map { it.kind }})"
                }
            ValidationResult.Fail(
                ruleName,
                "Duplicate sequence numbers detected",
                "Found ${seqCounts.size} duplicate sequence number(s): ${duplicates.joinToString("; ")}",
            )
        }
    }
}
