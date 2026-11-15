package ai.meeos.tago.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-06: 응급 카드 위치 — 낡았거나 부정확하거나 이상한 GPS 는 쓰지 않는다(지금 위치처럼 읽어 주지 않는다) */
class EmergencyPlaceTest {
    @Test fun freshAccurateFixIsUsable() = assertTrue(EmergencyPlace.usable(37.4, 127.1, 8.0, 3_000))
    @Test fun missingAccuracyIsStillUsableIfFresh() = assertTrue(EmergencyPlace.usable(37.4, 127.1, null, 3_000))
    @Test fun staleFixIsNot() {
        assertTrue(EmergencyPlace.usable(37.4, 127.1, 8.0, EmergencyPlace.MAX_AGE_MS))
        assertFalse(EmergencyPlace.usable(37.4, 127.1, 8.0, EmergencyPlace.MAX_AGE_MS + 1))
    }
    @Test fun badAccuracyIsNot() = assertFalse(EmergencyPlace.usable(37.4, 127.1, 450.0, 1_000))
    @Test fun nanOrOutOfRangeOrNegativeAgeIsNot() {
        assertFalse(EmergencyPlace.usable(Double.NaN, 127.1, 5.0, 0)); assertFalse(EmergencyPlace.usable(91.0, 127.1, 5.0, 0))
        assertFalse(EmergencyPlace.usable(37.4, 181.0, 5.0, 0)); assertFalse(EmergencyPlace.usable(37.4, 127.1, 5.0, -1))
    }
    @Test fun coordsAreFivePlacesWithDotEvenInKoreanLocale() {
        assertEquals("37.40123" to "127.10234", EmergencyPlace.coords(37.401234, 127.102341))
        assertEquals("-33.86800" to "151.20900", EmergencyPlace.coords(-33.868, 151.209))
    }
}
