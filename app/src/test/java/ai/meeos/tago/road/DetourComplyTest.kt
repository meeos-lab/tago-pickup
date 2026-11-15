package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 우회 권고 준수 점수 c = (따른 수 + 1) / (받은 수 + 2), 수락 = c ≥ 1 − p, 받은 수는 판정이 난 권고만 센다 (FR-G-36 확장·FR-G-37) */
class DetourComplyTest {
    @Test fun scoreAndAcceptRule() {
        val c = DetourComply()
        assertEquals(0.5, c.score, 1e-12)
        assertTrue("처음 0.5 ≥ 1 − 0.5", c.accepts(0.5))
        c.judge(false)
        assertEquals(1.0 / 3, c.score, 1e-12)
        assertFalse("1/3 < 1 − 0.2", c.accepts(0.2))
        c.judge(true)
        assertEquals(2.0 / 4, c.score, 1e-12)
        assertTrue("0.5 ≥ 1 − 0.8", c.accepts(0.8))
        assertEquals(2, c.received)
        assertEquals(1, c.followed)
    }

    /** 판정 없이 권고만 받아도 점수가 변하지 않는다(받음만 늘어 c 가 계속 내려가던 고착 — FR-G-37) */
    @Test fun acceptWithoutJudgmentDoesNotMoveScore() {
        val c = DetourComply()
        repeat(10) { assertTrue(c.accepts(0.5)) }
        assertEquals(0, c.received)
        assertEquals(0.5, c.score, 1e-12)
    }

    /** 안 따름 판정은 받은 수만 늘린다(c 하락), 따름 판정은 둘 다 늘린다 */
    @Test fun judgmentCountsReceivedAndFollowed() {
        val c = DetourComply(received = 3, followed = 1)
        c.judge(false)
        assertEquals(4, c.received); assertEquals(1, c.followed)
        c.judge(true)
        assertEquals(5, c.received); assertEquals(2, c.followed)
        assertEquals(3.0 / 7, c.score, 1e-12)
    }

    /** 따름 판정의 근거(B-86): 허브 보기 offRoute 가 있으면 그것, 없으면 차 안 GPS 횡거리(1 km 초과), 둘 다 없으면 판정 미룸 */
    @Test fun offRoute1kmSource() {
        assertEquals(true, DetourComply.offRoute1km(true, null, 1000.0))
        assertEquals("허브 값이 우선", false, DetourComply.offRoute1km(false, 5000.0, 1000.0))
        assertEquals(true, DetourComply.offRoute1km(null, 1500.0, 1000.0))
        assertEquals(false, DetourComply.offRoute1km(null, 80.0, 1000.0))
        assertEquals("경계는 넘어야 따름", false, DetourComply.offRoute1km(null, 1000.0, 1000.0))
        org.junit.Assert.assertNull("둘 다 모름", DetourComply.offRoute1km(null, null, 1000.0))
    }
}
