package ai.meeos.tago.navi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-05: 내비 요청은 2초 안에 다시 보내지 않는다(연타·안 되는 요청의 되풀이 방어) */
class RequestGateTest {
    @Test fun firstRequestPasses() = assertTrue(RequestGate().tryEnter(5))
    @Test fun withinGapIsIgnoredAfterGapPasses() {
        val g = RequestGate()
        assertTrue(g.tryEnter(10_000)); assertFalse(g.tryEnter(10_500)); assertFalse(g.tryEnter(11_999))
        assertTrue(g.tryEnter(12_000))
    }
    @Test fun ignoredRequestsDoNotExtendTheWindow() {
        val g = RequestGate()
        g.tryEnter(0); g.tryEnter(1_500); g.tryEnter(1_900)
        assertTrue(g.tryEnter(2_000))   // 무시된 요청은 시각을 갱신하지 않는다
    }
    @Test fun worksAtZeroClock() { val g = RequestGate(); assertTrue(g.tryEnter(0)); assertFalse(g.tryEnter(1_000)) }
}
