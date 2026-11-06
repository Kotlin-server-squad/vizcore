package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlin.test.assertSame
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizCoroutineElement
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which coroutines of a dump are left to the wrapper events (spec "Origin": captured once), and their children's parent. */
class WrapperOwnedCoroutinesTest {
    private val session = VizSession("wrapped")

    private fun element(
        owner: VizSession?,
        ownerJob: Job?,
    ) = VizCoroutineElement("coroutine-1", "job-1", null, "scope", "w").also {
        it.owner = owner
        it.ownerJob = ownerJob
    }

    private fun raw(
        name: String,
        job: Job,
        context: CoroutineContext,
    ) = CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, job, context + CoroutineName(name), emptyList(), emptyList())

    private fun kept(vararg raws: CoroutineInfoAdapter.RawInfo) =
        raws.filterNot { isWrapperOwned(it, session) }.map { it.context[CoroutineName]!!.name }

    @Test
    fun `the coroutine a VizScope of this session launched is left to its wrapper events`() {
        val job = Job()
        assertEquals(emptyList(), kept(raw("wrapper", job, element(session, job))))
    }

    @Test
    fun `a wrapper whose job is not recorded yet is the wrapper itself`() {
        assertEquals(emptyList(), kept(raw("wrapper", Job(), element(session, null))))
    }

    @Test
    fun `a coroutine that inherited a wrapper's element is kept`() {
        val wrapperJob = Job()
        val e = element(session, wrapperJob)
        assertEquals(listOf("child"), kept(raw("wrapper", wrapperJob, e), raw("child", Job(wrapperJob), e)))
    }

    @Test
    fun `a wrapper of another session is kept`() {
        val job = Job()
        assertEquals(listOf("other"), kept(raw("other", job, element(VizSession("other"), job))))
    }

    @Test
    fun `coroutines without the element are kept`() {
        assertEquals(listOf("app"), kept(raw("app", Job(), CoroutineName("app"))))
    }

    @Test
    fun `a child of a wrapper keeps the wrapper as its parent, under the wrapper's own id`() {
        val wrapperJob = Job()
        val e = element(session, wrapperJob)
        val raws = listOf(raw("wrapper", wrapperJob, e), raw("child", Job(wrapperJob), e))
        val snapshots = attributeToWrappers(raws, CoroutineInfoAdapter().toSnapshots(raws), session)
        assertSame(e, snapshots[0].wrapper)
        assertNull(snapshots[1].wrapper)
        assertEquals("coroutine-1", snapshots[1].parentCoroutineId)
        val created = DebugProbesEventSynthesizer().synthesize(CoroutineDelta.Appeared(snapshots[1]), session).first() as CoroutineCreated
        assertEquals("coroutine-1", created.parentCoroutineId)
    }

    @Test
    fun `without wrappers the snapshots pass unchanged`() {
        val raws = listOf(raw("app", Job(), CoroutineName("app")))
        val snapshots = CoroutineInfoAdapter().toSnapshots(raws)
        val attributed = attributeToWrappers(raws, snapshots, session)
        assertEquals(snapshots, attributed)
        assertNull(attributed.single().parentCoroutineId)
        assertNull(attributed.single().wrapper)
    }

    @Test
    fun `the synthesizer adds nothing for a wrapper coroutine's deltas, which the source reconciles instead`() {
        val synthesizer = DebugProbesEventSynthesizer()
        val wrapper =
            CoroutineSnapshot(key = CoroKey("w"), state = CoroState.SUSPENDED, function = "main").also { it.wrapper = element(session, Job()) }
        assertEquals(emptyList(), synthesizer.synthesize(CoroutineDelta.Appeared(wrapper), session))
        assertEquals(emptyList(), synthesizer.synthesize(CoroutineDelta.StateChanged(CoroState.RUNNING, CoroState.SUSPENDED, wrapper), session))
        assertEquals(emptyList(), synthesizer.synthesize(CoroutineDelta.Vanished(wrapper), session), "its end is the wrapper's")
    }

    @Test
    fun `a child keeps its wrapper as parent after the wrapper's body returned and DebugProbes no longer list it`() {
        val wrapperJob = Job() // still active: it waits for the child, but its body has returned
        val e = element(session, wrapperJob)
        val raws = listOf(raw("child", Job(wrapperJob), e))
        val snapshots = attributeToWrappers(raws, CoroutineInfoAdapter().toSnapshots(raws), session)
        assertEquals("coroutine-1", snapshots.single().parentCoroutineId)
    }

    @Test
    fun `a nearer descendant of the wrapper stays the parent, an ancestor above the wrapper does not`() {
        val top = Job()
        val wrapperJob = Job(top) // the wrapper, not in the dump
        val e = element(session, wrapperJob)
        val child = Job(wrapperJob)
        val grandchild = Job(child)
        val raws =
            listOf(
                raw("top", top, CoroutineName("top")),
                raw("child", child, e),
                raw("grandchild", grandchild, e),
            )
        val snapshots = attributeToWrappers(raws, CoroutineInfoAdapter().toSnapshots(raws), session)
        assertEquals("coroutine-1", snapshots[1].parentCoroutineId, "the wrapper, not the observed coroutine above it")
        assertNull(snapshots[2].parentCoroutineId, "its observed parent is a descendant of the wrapper: kept as dp-")
        assertEquals(snapshots[1].key, snapshots[2].parentKey)
    }

    @Test
    fun `a child of an observed wrapper is named under the wrapper's id even without the element`() {
        val wrapperJob = Job()
        val raws =
            listOf(
                raw("wrapper", wrapperJob, element(session, wrapperJob)),
                // CoroutineScope(currentCoroutineContext()[Job]!!).launch { }: the wrapper's Job, not its element
                raw("plain", Job(wrapperJob), CoroutineName("plain")),
                // a VizScope of another session nested in the wrapper
                raw("foreign", Job(wrapperJob), VizCoroutineElement("coroutine-9", "job-9", "coroutine-1", "s2", "f").also {
                    it.owner = VizSession("other")
                }),
            )
        val snapshots = attributeToWrappers(raws, CoroutineInfoAdapter().toSnapshots(raws), session)
        assertEquals("coroutine-1", snapshots[1].parentCoroutineId)
        assertEquals("coroutine-1", snapshots[2].parentCoroutineId)
    }

    @Test
    fun `an unlisted inner wrapper stays the parent of its child when the outer wrapper is listed`() {
        val outerJob = Job()
        val outer = element(session, outerJob)
        val innerJob = Job(outerJob) // its body returned: DebugProbes do not list it
        val inner =
            VizCoroutineElement("coroutine-2", "job-2", "coroutine-1", "scope", "inner").also {
                it.owner = session
                it.ownerJob = innerJob
            }
        val raws = listOf(raw("outer", outerJob, outer), raw("child", Job(innerJob), inner))
        val snapshots = attributeToWrappers(raws, CoroutineInfoAdapter().toSnapshots(raws), session)
        assertEquals("coroutine-2", snapshots[1].parentCoroutineId, "the nearest wrapper, not the listed outer one")
    }
}
