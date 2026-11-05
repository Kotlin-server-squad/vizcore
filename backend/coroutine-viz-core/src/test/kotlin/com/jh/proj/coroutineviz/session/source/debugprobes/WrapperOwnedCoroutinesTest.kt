package com.jh.proj.coroutineviz.session.source.debugprobes

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

    private fun kept(vararg raws: CoroutineInfoAdapter.RawInfo) = excludeWrapperOwned(raws.toList(), session).map { it.context[CoroutineName]!!.name }

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
        val kept = leaveToWrappers(raws, CoroutineInfoAdapter().toSnapshots(raws), session)
        assertEquals(1, kept.size)
        assertEquals("coroutine-1", kept.single().parentCoroutineId)
        val created = DebugProbesEventSynthesizer().synthesize(CoroutineDelta.Appeared(kept.single()), session).first() as CoroutineCreated
        assertEquals("coroutine-1", created.parentCoroutineId)
    }

    @Test
    fun `without wrappers the snapshots pass unchanged`() {
        val raws = listOf(raw("app", Job(), CoroutineName("app")))
        val snapshots = CoroutineInfoAdapter().toSnapshots(raws)
        val kept = leaveToWrappers(raws, snapshots, session)
        assertEquals(snapshots, kept)
        assertNull(kept.single().parentCoroutineId)
    }
}
