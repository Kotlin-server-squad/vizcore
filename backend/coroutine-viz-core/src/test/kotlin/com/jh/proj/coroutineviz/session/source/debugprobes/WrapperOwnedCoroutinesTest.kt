package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlin.test.assertSame
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.SuspensionProvenance
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

    private fun wrapperSnapshot(
        state: CoroState,
        ownerJob: Job = Job(),
    ) = CoroutineSnapshot(
        key = CoroKey("w"),
        state = state,
        function = "main",
        lastObservedStackTrace = listOf(StackTraceElement("com.acme.Repo", "load", "Repo.kt", 42)),
    ).also { it.wrapper = element(session, ownerJob) }

    @Test
    fun `a wrapper coroutine gets only its sampled suspensions and resumes, under its own id`() {
        val synthesizer = DebugProbesEventSynthesizer()
        val suspended = wrapperSnapshot(CoroState.SUSPENDED)
        val appeared = synthesizer.synthesize(CoroutineDelta.Appeared(suspended), session)
        val event = appeared.single() as CoroutineSuspended
        assertEquals("coroutine-1", event.coroutineId)
        assertEquals("job-1", event.jobId)
        assertEquals(SuspensionProvenance.REAL_FRAME, event.provenance)
        assertEquals(emptyList(), synthesizer.synthesize(CoroutineDelta.Appeared(wrapperSnapshot(CoroState.RUNNING)), session))
        val resumed = synthesizer.synthesize(CoroutineDelta.StateChanged(CoroState.SUSPENDED, CoroState.RUNNING, wrapperSnapshot(CoroState.RUNNING)), session)
        assertEquals(listOf("CoroutineResumed"), resumed.map { it.kind })
        assertEquals("coroutine-1", (resumed.single() as CoroutineResumed).coroutineId)
        assertEquals(emptyList(), synthesizer.synthesize(CoroutineDelta.Vanished(suspended), session), "its end is the wrapper's")
    }

    @Test
    fun `nothing is added for a wrapper coroutine whose job completed`() {
        val done = Job().also { it.complete() }
        val events = DebugProbesEventSynthesizer().synthesize(CoroutineDelta.Appeared(wrapperSnapshot(CoroState.SUSPENDED, done)), session)
        assertEquals(emptyList(), events)
    }
}
