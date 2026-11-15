package ai.meeos.tago.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연용 시계(빠르게 감기): 배율만큼 빨리 가고, 배율을 내려도 뒤로 가지 않는다 */
class WarpClockTest {
    @Test fun rateOneIsRealTime() {
        var r = 1000.0
        val c = WarpClock { r }
        assertEquals(1000.0, c.now(), 1e-9)
        r = 1010.0
        assertEquals(1010.0, c.now(), 1e-9)
    }

    @Test fun runsFasterThenKeepsTheLead() {
        var r = 1000.0
        val c = WarpClock { r }
        c.now()
        c.rate(6.0)
        r = 1010.0                                   // 실제 10 s → 시계 60 s
        assertEquals(1060.0, c.now(), 1e-9)
        c.rate(1.0)
        r = 1020.0                                   // 다시 실제 속도, 앞선 50 s 는 그대로
        assertEquals(1070.0, c.now(), 1e-9)
    }

    @Test fun neverGoesBackAndClampsTheRate() {
        var r = 1000.0
        val c = WarpClock { r }
        var prev = c.now()
        for ((rate, dt) in listOf(8.0 to 1.0, 1.0 to 0.5, 3.0 to 2.0, 1.0 to 0.0, 100.0 to 1.0, 0.2 to 1.0, Double.NaN to 1.0)) {
            c.rate(rate); r += dt
            val n = c.now()
            assertTrue("뒤로 감 $prev → $n", n >= prev)
            prev = n
        }
        c.rate(100.0); assertEquals(WarpClock.MAX_RATE, c.rate, 0.0)
        c.rate(0.2); assertEquals(1.0, c.rate, 0.0)
        c.rate(Double.NaN); assertEquals(1.0, c.rate, 0.0)
        // 요청 간격 1 s 에서 한 번에 어긋나는 양이 허브 시계 오차 가드보다 작다
        assertTrue((WarpClock.MAX_RATE - 1.0) * 1.0 < LinkHub.CLOCK_SKEW_SEC)
    }
    @Test fun pauseHoldsTheTimeThenResumesAtTheSameRate() {
        var r = 1000.0
        val c = WarpClock { r }
        c.now()
        c.rate(4.0)
        r = 1010.0                                   // 실제 10 s → 시계 40 s
        assertEquals(1040.0, c.now(), 1e-9)
        c.pause(true)
        r = 1070.0                                   // 멈춘 60 s 동안 시각은 그대로
        assertEquals(1040.0, c.now(), 1e-9)
        assertEquals(4.0, c.rate, 0.0)               // 배율은 남아 있다
        c.pause(false)
        r = 1080.0                                   // 풀면 다시 ×4
        assertEquals(1080.0, c.now(), 1e-9)
    }
}
