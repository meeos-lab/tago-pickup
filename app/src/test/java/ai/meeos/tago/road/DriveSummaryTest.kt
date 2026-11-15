package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주행 요약(A)·[경고 틀림] 기록(B) 집계 */
class DriveSummaryTest {
    @Test fun countsAlertsConfirmedAndWrong() {
        val s = DriveSummary(); s.start(1_000)
        s.alert("h1"); s.alert("h2"); s.alert("h2"); s.alert("h3")           // 같은 위험은 한 번
        s.confirm("h1"); s.confirm("h3"); s.confirm("zzz")                   // 알린 적 없는 확인은 세지 않는다
        assertEquals(3, s.alertCount); assertEquals(2, s.confirmedCount)
        assertEquals("h3", s.flagLast()); assertEquals(1, s.wrongCount)
        assertNull(s.flagLast())                                              // 같은 위험을 또 누르면 두 번 세지 않는다
        s.alert("h4"); assertEquals("h4", s.flagLast()); assertEquals(2, s.wrongCount)
    }

    @Test fun flagWithoutAnyAlertIsNothing() { val s = DriveSummary(); s.start(0); assertNull(s.flagLast()); assertEquals(0, s.wrongCount) }

    @Test fun startResetsEverything() {
        val s = DriveSummary(); s.start(0); s.alert("a"); s.flagLast(); s.start(5_000)
        assertEquals(0, s.alertCount); assertEquals(0, s.wrongCount); assertNull(s.lastAlert)
    }

    @Test fun worthShowingAndMinutes() {
        val b = 1_000_000L   // 시작 시각 0 은 '시작 안 함'이라 실제처럼 큰 값을 쓴다
        val s = DriveSummary(); assertFalse(s.worthShowing())                 // 시작 전
        s.start(b); assertFalse(s.worthShowing()); s.finish(b + 10_000); assertFalse(s.worthShowing())   // 알림도 없고 10 s 뿐
        s.start(b); s.alert("a"); assertTrue(s.worthShowing())
        s.start(b); s.finish(b + 5 * 60_000L + 59_000L); assertTrue(s.worthShowing()); assertEquals(5, s.minutes(b))
        s.finish(b + 1)                                                        // 두 번 끝내도 처음 시각 유지
        assertEquals(5, s.minutes(b + 99_999_999))
    }
}
