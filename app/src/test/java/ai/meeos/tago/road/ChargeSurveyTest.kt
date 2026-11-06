package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 충전기 고장 신고 1분 설문: 한 화면 한 문항, 기기 번호를 고르면 자리는 묻지 않는다(40 A-15) */
class ChargeSurveyTest {
    @Test fun unitSkipsPlace() {
        val s = ChargeSurvey(from = 27119.0)
        assertEquals("sym", s.key)
        s.pick("payment")
        assertEquals("unit", s.key)
        s.pick("3")
        assertEquals("broken", s.key)                 // place 를 건너뛴다
        s.pick("2"); s.pick("no")
        assertTrue(s.done)
        assertNull(s.key)
        val b = s.body()
        assertEquals(27119.0, b.getDouble("from"), 0.0)
        assertEquals("payment", b.getString("sym"))
        assertEquals(3, b.getInt("unit"))
        assertEquals("", b.getString("place"))
        assertEquals("2", b.getString("broken"))
        assertEquals("no", b.getString("alt"))
    }

    @Test fun skipMeansUnknownAndPlaceIsAskedWithoutUnit() {
        val s = ChargeSurvey(0.0)
        s.pick("no_start")
        s.skip()                                      // 번호 모름
        assertEquals("place", s.key)
        s.pick("end"); s.skip(); s.skip()
        assertTrue(s.done)
        assertEquals(0, s.body().getInt("unit"))      // 0 = 모름
        assertEquals("end", s.body().getString("place"))
    }

    @Test fun wrongAnswerIsIgnoredAndBackGoesOneStep() {
        val s = ChargeSurvey(0.0)
        s.pick("not-a-symptom")
        assertEquals("sym", s.key)                    // 그 문항에 없는 답은 무시
        s.pick("screen_off"); s.back()
        assertEquals("sym", s.key)
        assertFalse(s.done)
    }
}
