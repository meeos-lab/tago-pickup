package ai.meeos.tago.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** V-02: 상태가 AVAILABLE 이 아니거나 값이 이상한 속도 표본은 버린다 — 오류 표본의 0 이 가짜 충돌이 되지 않게 */
class SpeedSampleTest {
    @Test fun availableFloatIsAccepted() = assertEquals(25.0f, SpeedSample.accept(0, 25.0f))
    @Test fun unavailableAndErrorStatusAreDropped() {
        assertNull(SpeedSample.accept(1, 0f)); assertNull(SpeedSample.accept(2, 25f)); assertNull(SpeedSample.accept(-1, 3f))
    }
    @Test fun nonFloatNanInfiniteAreDropped() {
        assertNull(SpeedSample.accept(0, null)); assertNull(SpeedSample.accept(0, 25)); assertNull(SpeedSample.accept(0, "25"))
        assertNull(SpeedSample.accept(0, Float.NaN)); assertNull(SpeedSample.accept(0, Float.POSITIVE_INFINITY))
    }

    /** 가짜 충돌 회귀: 오류 상태의 0 표본을 걸러 충돌 감지기에 넣지 않으면 25 → 0 이어도 충돌이 안 난다. 정상 표본의 25 → 0 은 난다(대조) */
    @Test fun errorZeroPlateauMakesNoImpactButRealOneDoes() {
        fun run(status: Int): Int {
            var n = 0
            val d = ImpactDetector(); d.onImpact = { _, _, _ -> n++ }
            val base = 100_000L   // ImpactDetector 는 시작 뒤 10 s 쿨다운(impactAt=0)이라 시각을 충분히 키운다
            for (i in 0 until 6) d.feed(base + i * 100L, 25f)
            for (i in 6 until 16) SpeedSample.accept(status, 0f)?.let { d.feed(base + i * 100L, it) }
            return n
        }
        assertEquals(0, run(1))   // UNAVAILABLE
        assertEquals(1, run(0))   // AVAILABLE — 실제 충돌은 그대로 잡는다
    }
}
