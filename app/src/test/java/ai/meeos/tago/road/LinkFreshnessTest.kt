package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-03: 허브 응답이 10초 넘게 없으면 끊김, 응답이 오면 바로 복귀 */
class LinkFreshnessTest {
    @Test fun notStartedIsNeverStale() = assertFalse(LinkFreshness().stale(1_000_000))
    @Test fun staleOnlyAfterLimit() {
        val f = LinkFreshness(); f.ok(1_000)
        assertFalse(f.stale(1_000 + LinkFreshness.STALE_MS))        // 경계 10 s 까지는 신선
        assertTrue(f.stale(1_000 + LinkFreshness.STALE_MS + 1))
    }
    @Test fun recoversWhenResponseArrives() {
        val f = LinkFreshness(); f.ok(1); assertTrue(f.stale(20_000))
        f.ok(20_001); assertFalse(f.stale(21_000))
    }
    @Test fun resetTurnsItOff() { val f = LinkFreshness(); f.ok(1); f.reset(); assertFalse(f.stale(999_999)); assertEquals(0, f.ageSec(999_999)) }
    @Test fun ageSeconds() { val f = LinkFreshness(); f.ok(1_000); assertEquals(12, f.ageSec(13_500)) }
}
