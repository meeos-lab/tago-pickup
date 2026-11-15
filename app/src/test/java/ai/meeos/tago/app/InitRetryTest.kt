package ai.meeos.tago.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-04: 서비스가 늦게 뜨는 경우(가짜 서비스가 N번째 시도부터 연결)·예외·취소 */
class InitRetryTest {
    /** 시계 없는 예약기: 예약된 일을 쌓아 두고 tick() 이 순서대로 실행한다 */
    private class FakeScheduler {
        val queue = ArrayDeque<Pair<Long, () -> Unit>>(); val delaysSeen = mutableListOf<Long>()
        fun schedule(d: Long, f: () -> Unit) { delaysSeen += d; queue.addLast(d to f) }
        fun tick() { val (_, f) = queue.removeFirst(); f() }
    }

    @Test fun retriesEveryFiveSecondsUntilServiceComesUp() {
        val sch = FakeScheduler(); var tries = 0
        val r = InitRetry(schedule = sch::schedule) { me -> tries++; if (tries >= 3) me.succeeded() else me.failed() }
        r.start()
        assertFalse(r.ready)
        while (!r.ready) sch.tick()
        assertEquals(3, tries); assertEquals(3, r.attempts)
        assertEquals(listOf(5_000L, 5_000L), sch.delaysSeen)   // 두 번 실패 → 5초 간격
        assertTrue(sch.queue.isEmpty())                         // 성공하면 더 예약하지 않는다
    }

    @Test fun exceptionCountsAsFailureAndRetries() {
        val sch = FakeScheduler(); var tries = 0
        val r = InitRetry(schedule = sch::schedule) { me -> tries++; if (tries == 1) throw IllegalStateException("service not bound") else me.succeeded() }
        r.start(); assertFalse(r.ready); sch.tick(); assertTrue(r.ready); assertEquals(2, tries)
    }

    @Test fun asyncCallbackFailureAndSuccess() {
        val sch = FakeScheduler(); val pending = mutableListOf<InitRetry>()
        val r = InitRetry(schedule = sch::schedule) { me -> pending += me }   // 비동기: 콜백이 나중에 결과를 알린다
        r.start(); pending.removeAt(0).failed(); sch.tick(); pending.removeAt(0).succeeded()
        assertTrue(r.ready); assertEquals(2, r.attempts)
    }

    @Test fun cancelStopsFurtherAttempts() {
        val sch = FakeScheduler(); var tries = 0
        val r = InitRetry(schedule = sch::schedule) { me -> tries++; me.failed() }
        r.start(); r.cancel(); sch.tick()
        assertEquals(1, tries)
        assertTrue(sch.queue.isEmpty())
    }

    @Test fun lateFailureAfterSuccessIsIgnored() {
        val sch = FakeScheduler(); val r = InitRetry(schedule = sch::schedule) { me -> me.succeeded() }
        r.start(); r.failed(); assertTrue(sch.queue.isEmpty())
    }
}
