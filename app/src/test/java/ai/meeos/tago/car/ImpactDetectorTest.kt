package ai.meeos.tago.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 충돌 감지 규칙(40 A-02): 감속률 ≥ 14 m/s²·Δv ≥ 2.2 m/s 후보 → 0.5 s 동안 사건 전 50 % 아래면 확정, 튐 제외, 10 s 쿨다운 */
class ImpactDetectorTest {
    private class Run {
        val hits = mutableListOf<Pair<Float, Float>>()
        val d = ImpactDetector().apply { onImpact = { pre, now, _ -> hits += pre to now } }
        var t = 20_000L                                   // 쿨다운 기준(0)에서 멀리
        fun feed(vararg v: Float) = v.forEach { d.feed(t, it); t += 100 }   // 10 Hz
    }

    @Test fun crashIsConfirmedAfterHalfSecond() {
        val r = Run()
        r.feed(25f, 25f, 25f, 25f, 0f)                    // 0.1 s 에 25 → 0 m/s: 250 m/s²
        assertTrue(r.hits.isEmpty())                      // 아직 후보
        r.feed(0f, 0f, 0f, 0f)
        assertTrue(r.hits.isEmpty())                      // 0.4 s
        r.feed(0f)                                        // 0.5 s 유지 → 확정
        assertEquals(listOf(25f to 0f), r.hits)
    }

    @Test fun singleSampleSpikeIsIgnored() {
        val r = Run()
        r.feed(25f, 25f, 0f, 24f, 25f, 25f, 25f, 25f, 25f, 25f)   // 한 표본만 0 — 다음 표본이 50 % 이상
        assertTrue(r.hits.isEmpty())
    }

    @Test fun hardBrakingIsNotACrash() {
        val r = Run()
        var v = 30f
        repeat(30) { r.feed(v); v = maxOf(0f, v - 1.1f) }      // 11 m/s²(제동 한계 근처) — 14 m/s² 미만
        repeat(10) { r.feed(0f) }
        assertTrue(r.hits.isEmpty())
    }

    @Test fun onlyOncePerTenSeconds() {
        val r = Run()
        r.feed(25f, 25f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(1, r.hits.size)
        r.feed(20f, 20f, 0f, 0f, 0f, 0f, 0f, 0f)          // 1 s 뒤 또 — 쿨다운
        assertEquals(1, r.hits.size)
        r.t += ImpactDetector.IMPACT_GAP_MS
        r.feed(20f, 20f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(2, r.hits.size)
    }
}
