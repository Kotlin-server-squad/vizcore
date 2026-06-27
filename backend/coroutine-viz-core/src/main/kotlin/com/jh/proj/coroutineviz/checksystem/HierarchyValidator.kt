package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated

/**
 * Validates parent-child relationships in the coroutine hierarchy.
 *
 * Rules enforced:
 * - Children must be created after their parent is created
 * - Parent must not complete before all of its children (structured concurrency)
 */
object HierarchyValidator {
    private val TERMINAL_KINDS = setOf("CoroutineCompleted", "CoroutineCancelled", "CoroutineFailed")

    /**
     * Run all hierarchy validation rules against the given event list.
     *
     * @param events Full event stream (may contain events for multiple coroutines)
     * @return List of [ValidationResult] -- one per rule per parent-child pair
     */
    fun validate(events: List<VizEvent>): List<ValidationResult> {
        val results = mutableListOf<ValidationResult>()

        val coroutineEvents = events.filterIsInstance<CoroutineEvent>()
        val byCoroutine = coroutineEvents.groupBy { it.coroutineId }

        // Whether the source distinguishes body completion from job-tree completion.
        // Sources that model the structured-concurrency "Completing" / WAITING_FOR_CHILDREN
        // state emit CoroutineBodyCompleted (e.g. the VizScope wrapper, which also defers a
        // coroutine's terminal until all children have terminated). Coarse sources such as the
        // DebugProbes synthesizer emit a single CoroutineCompleted at body/vanish completion and
        // never emit CoroutineBodyCompleted — so a parent legitimately "completes" (body done)
        // before its still-running children. The parent-before-children ordering check is only
        // meaningful when this richer signal is present.
        val streamModelsBodyCompletion = coroutineEvents.any { it is CoroutineBodyCompleted }

        // Build parent-child relationships from CoroutineCreated events
        val createEvents = coroutineEvents.filterIsInstance<CoroutineCreated>()

        if (createEvents.isEmpty()) {
            results +=
                ValidationResult.Pass(
                    "HierarchyValidation",
                    "No coroutine creation events to validate",
                )
            return results
        }

        for (childCreated in createEvents) {
            val parentId = childCreated.parentCoroutineId ?: continue

            results += checkChildCreatedWithinParentScope(childCreated, parentId, byCoroutine)
            results += checkParentNotCompletedBeforeChildren(
                childCreated,
                parentId,
                byCoroutine,
                streamModelsBodyCompletion,
            )
        }

        if (results.isEmpty()) {
            results +=
                ValidationResult.Pass(
                    "HierarchyValidation",
                    "No parent-child relationships to validate (all coroutines are roots)",
                )
        }

        return results
    }

    private fun checkChildCreatedWithinParentScope(
        childCreated: CoroutineCreated,
        parentId: String,
        byCoroutine: Map<String, List<CoroutineEvent>>,
    ): ValidationResult {
        val ruleName = "ChildCreatedWithinParentScope"
        val parentEvents = byCoroutine[parentId]

        if (parentEvents == null) {
            return ValidationResult.Fail(
                ruleName,
                "Child ${childCreated.coroutineId} references non-existent parent $parentId",
                "CoroutineCreated at seq=${childCreated.seq} references parentCoroutineId=$parentId " +
                    "but no events exist for that coroutine",
            )
        }

        val parentCreated = parentEvents.filterIsInstance<CoroutineCreated>().firstOrNull()
        return if (parentCreated == null) {
            ValidationResult.Fail(
                ruleName,
                "Parent $parentId has no CoroutineCreated event",
                "Child ${childCreated.coroutineId} references parent $parentId which has events " +
                    "but no CoroutineCreated",
            )
        } else if (childCreated.seq > parentCreated.seq) {
            ValidationResult.Pass(
                ruleName,
                "Child ${childCreated.coroutineId} created (seq=${childCreated.seq}) " +
                    "after parent $parentId (seq=${parentCreated.seq})",
            )
        } else {
            ValidationResult.Fail(
                ruleName,
                "Child ${childCreated.coroutineId} created before parent $parentId",
                "Child created at seq=${childCreated.seq} but parent created at seq=${parentCreated.seq}",
            )
        }
    }

    private fun checkParentNotCompletedBeforeChildren(
        childCreated: CoroutineCreated,
        parentId: String,
        byCoroutine: Map<String, List<CoroutineEvent>>,
        streamModelsBodyCompletion: Boolean,
    ): ValidationResult {
        val ruleName = "ParentNotCompletedBeforeChildren"

        // Coarse sources (e.g. DebugProbes) emit CoroutineCompleted at body/vanish completion,
        // not job-tree completion, and never emit CoroutineBodyCompleted. Under those semantics a
        // parent legitimately terminates before its children, so the ordering assertion below would
        // be a false positive. Genuinely never-terminated children are still caught by the
        // lifecycle "started-has-terminal" leak rule, independent of this hierarchy check.
        if (!streamModelsBodyCompletion) {
            return ValidationResult.Pass(
                ruleName,
                "Source does not model body-vs-tree completion (no CoroutineBodyCompleted events); " +
                    "CoroutineCompleted denotes body completion, so parent/child terminal ordering is not asserted",
            )
        }

        val parentEvents =
            byCoroutine[parentId] ?: return ValidationResult.Pass(
                ruleName,
                "Parent $parentId has no events (skipped)",
            )

        val childEvents =
            byCoroutine[childCreated.coroutineId] ?: return ValidationResult.Pass(
                ruleName,
                "Child ${childCreated.coroutineId} has no events beyond creation (skipped)",
            )

        val parentCompleted =
            parentEvents
                .firstOrNull { it is CoroutineCompleted }
                ?: return ValidationResult.Pass(
                    ruleName,
                    "Parent $parentId has not completed (skipped)",
                )

        val childTerminal =
            childEvents
                .firstOrNull { it.kind in TERMINAL_KINDS }

        return if (childTerminal == null) {
            // Child hasn't terminated -- if parent completed that's a problem
            ValidationResult.Fail(
                ruleName,
                "Parent $parentId completed before child ${childCreated.coroutineId} terminated",
                "Parent completed at seq=${parentCompleted.seq} but child has no terminal event",
            )
        } else if (parentCompleted.seq >= childTerminal.seq) {
            ValidationResult.Pass(
                ruleName,
                "Parent $parentId (completed seq=${parentCompleted.seq}) " +
                    "waited for child ${childCreated.coroutineId} (terminal seq=${childTerminal.seq})",
            )
        } else {
            ValidationResult.Fail(
                ruleName,
                "Parent $parentId completed before child ${childCreated.coroutineId}",
                "Parent completed at seq=${parentCompleted.seq} but child terminated at seq=${childTerminal.seq}",
            )
        }
    }
}
