package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 우회 권고 준수 점수 c = (따른 수 + 1) / (받은 수 + 2), 수락 = c ≥ 1 − p (FR-G-36 확장) */
class DetourComplyTest {
    @Test fun scoreAndAcceptRule() {
        val c = DetourComply()
        assertEquals(0.5, c.score, 1e-12)
        assertTrue("처음 0.5 ≥ 1 − 0.5", c.receive(0.5))
        assertEquals(1.0 / 3, c.score, 1e-12)
        assertFalse("1/3 < 1 − 0.2", c.receive(0.2))
        c.follow()
        assertEquals(2.0 / 4, c.score, 1e-12)
        assertTrue("0.5 ≥ 1 − 0.8", c.receive(0.8))
        assertEquals(3, c.received)
    }

    @Test fun followNeverExceedsReceived() {
        val c = DetourComply()
        c.follow()
        assertEquals(0, c.followed)
        assertEquals(0.5, c.score, 1e-12)
    }
}
