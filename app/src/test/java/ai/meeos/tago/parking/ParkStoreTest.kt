package ai.meeos.tago.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마지막 주차 기록 보관(11-13 채점 1바퀴): 되살리기·만료·깨진 값·좌표 미보관 */
class ParkStoreTest {
    private val rec = ParkRecord(atMs = 123L, entrance = 37.5 to 127.1, floor = -2, confidence = Confidence.MEDIUM, pillar = "D-14", floorSource = "ocr",
        estFloor = -3, alt = null, rampRuns = 2, bldgBasement = 4)

    @Test fun roundTripKeepsEverythingButCoordinates() {
        val (r, wall) = ParkStore.decode(ParkStore.encode(rec, 1_000_000L), 1_000_000L + 3600_000L)!!
        assertEquals(1_000_000L, wall)
        assertEquals(rec.copy(atMs = 0L, entrance = null), r)
        assertFalse(ParkStore.encode(rec, 1L).contains("37.5"))   // 입구 좌표는 넣지 않는다
    }

    @Test fun rangeRecordSurvives() {
        val low = rec.copy(floor = -2, alt = -3, floorSource = "est", confidence = Confidence.LOW, pillar = null)
        assertEquals(-3, ParkStore.decode(ParkStore.encode(low, 0L), 1000L)!!.first.alt)
    }

    @Test fun expiredFutureAndBrokenAreDropped() {
        val enc = ParkStore.encode(rec, 1_000_000L)
        assertNull(ParkStore.decode(enc, 1_000_000L + ParkStore.MAX_AGE_MS + 1))
        assertNull(ParkStore.decode(enc, 999_999L))                     // 미래 시각(시계가 뒤로 감)
        for (bad in listOf(null, "", "{", "{\"v\":2}", enc.replace("\"ocr\"", "\"x\""))) assertNull(bad, ParkStore.decode(bad, 1_000_000L))
    }

    @Test fun ageText() {
        assertNull(ParkStore.ageText(9 * 60_000L))
        assertEquals(25 to false, ParkStore.ageText(25 * 60_000L))
        assertEquals(3 to true, ParkStore.ageText(3 * 3600_000L + 5))
    }

    @Test fun restoredRecordIsInvalidatedByDrivingAway() {
        val t = ParkingTracker()
        t.restore(rec)
        assertTrue(t.record != null && t.record!!.entrance == null)
        var ms = 0L
        repeat(6) { ms += 1000; t.tick(ms, null, 3.0, 0.0, parked = false, reversing = false) }
        assertNull(t.record)                                              // 5초 넘게 움직이면 무효(새 기록과 같은 규칙)
    }

    @Test fun mountRoundTripAndGuards() {
        val text = ParkStore.encodeMount(1.7, 900.0, "가속도 A · 자이로 B")
        assertEquals(1.7 to 900.0, ParkStore.decodeMount(text, "가속도 A · 자이로 B"))
        assertNull("센서가 다르면 버린다", ParkStore.decodeMount(text, "가속도 C · 자이로 B"))
        assertNull(ParkStore.decodeMount("{", "x")); assertNull(ParkStore.decodeMount(null, "x"))
        assertNull(ParkStore.decodeMount(ParkStore.encodeMount(30.0, 900.0, "s"), "s"))
    }
}
