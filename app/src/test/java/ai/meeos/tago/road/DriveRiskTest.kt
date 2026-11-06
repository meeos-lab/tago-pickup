package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사고 위험성의 운전 묶음(설계 10판 §6-2). 시각은 ms 로 직접 넣는다 — 0 은 '아직 없음' 표시라 1 이상부터 */
class DriveRiskTest {
    private val kmh = { v: Float -> v / 3.6f }

    @Test fun speedingAfterHold() {
        val r = DriveRisk()
        var t = 1000L
        repeat(19) { r.tick(t, kmh(125f), null); t += 1000 }
        assertEquals(emptyList<String>(), r.tags(t - 1000))           // 18 s — 아직
        r.tick(t, kmh(125f), null)
        assertEquals(listOf("SPEEDING"), r.tags(t + 1000))            // 20 s 이어짐
        r.tick(t + 2000, kmh(110f), null)                             // 제한 +20 안으로 돌아오면 바로 풀린다
        assertEquals(emptyList<String>(), r.tags(t + 2000))
    }

    @Test fun twoHardBrakesInTwoMinutes() {
        val r = DriveRisk()
        r.brake(1); r.tick(1, 20f, null)
        assertEquals(emptyList<String>(), r.tags(1000))
        r.brake(100_000)
        assertEquals(listOf("BRAKES"), r.tags(100_000))
        r.tick(230_000, 20f, null)                                     // 첫 급제동이 2분 창 밖으로
        assertEquals(emptyList<String>(), r.tags(230_000))
    }

    @Test fun fatigueAfterTwoHours() {
        val r = DriveRisk()
        r.tick(1, 20f, null)
        assertEquals(emptyList<String>(), r.tags(DriveRisk.FATIGUE_MS))
        assertEquals(listOf("FATIGUE"), r.tags(1 + DriveRisk.FATIGUE_MS))
    }

    @Test fun steeringWobbleAgainstOwnBaselineIgnoresSwerves() {
        val r = DriveRisk()
        var t = 1000L; var a = 0f
        repeat(DriveRisk.BASELINE_N + 1) { r.tick(t, kmh(80f), a); a += if (it % 2 == 0) 1f else -1f; t += 1000 }   // 기준선 1°
        r.tick(t, kmh(80f), a + 90f); t += 1000                                                                        // 급조향은 피로가 아니다
        r.tick(t, kmh(80f), a); t += 1000
        repeat(DriveRisk.RECENT_N) { r.tick(t, kmh(80f), a); a += if (it % 2 == 0) 2.5f else -2.5f; t += 1000 }         // 최근 2.5° ≥ 기준선 2배·1.5°
        assertTrue("FATIGUE" in r.tags(t))
    }

    @Test fun simTagsOverrideAndKeepOrder() {
        val r = DriveRisk()
        r.sim = setOf("FATIGUE", "SPEEDING")
        assertEquals(listOf("SPEEDING", "FATIGUE"), r.tags(0))
        r.reset()
        assertEquals(listOf("SPEEDING", "FATIGUE"), r.tags(0))         // reset 은 시연 태그를 지우지 않는다
    }
}
