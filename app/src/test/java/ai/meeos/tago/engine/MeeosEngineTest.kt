package ai.meeos.tago.engine

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 픽업 엔진 규칙(40 3절·51 22~40): 앱이 내려받는 것과 같은 server/ 지도·지역 파일로 로컬 JVM 에서 돈다.
 *  두 엔진 대조(check_parity.py)는 에뮬레이터가 있어야 하므로, 세션 흐름·입력 검증만 여기서 빠르게 고정한다. */
class MeeosEngineTest {
    companion object {
        private lateinit var eng: MeeosEngine

        /** 단위 시험의 작업 폴더는 app/ 이다 — 저장소 루트에서 돌려도 찾게 둘 다 본다 */
        fun server(name: String): String =
            listOf(File("../server", name), File("server", name)).first { it.isFile }.readText(Charsets.UTF_8)

        @BeforeClass @JvmStatic fun load() {
            val profile = server("regions/daechi.json")
            val pj = JSONObject(profile)
            eng = MeeosEngine(server(pj.getString("map")), server(pj.getString("cctv")), profile)
        }
    }

    private fun newSession(body: JSONObject = JSONObject()) = eng.createSession(body).getString("id")
    private fun riderAtDefault(sid: String): JSONObject {
        val d = eng.scenario().getJSONObject("riderDefault")
        return eng.action(sid, "rider", JSONObject().put("lat", d.getDouble("lat")).put("lon", d.getDouble("lon")))!!
    }

    @Test fun sessionFlowProposeThenConfirm() {
        val sid = newSession()
        assertEquals("waiting_rider", eng.get(sid)!!.getString("status"))
        val after = riderAtDefault(sid)
        val best = after.optJSONObject("proposal")?.optJSONObject("best")
        assertNotNull("기다리는 사람 위치가 오면 만남 지점을 제안한다", best)
        assertEquals("proposed", after.getString("status"))
        val conf = eng.action(sid, "confirm", JSONObject())!!
        assertEquals("confirmed", conf.getString("status"))
        assertEquals(best!!.getString("id"), conf.getString("confirmed"))
        assertEquals("driver", conf.getString("confirmedBy"))
    }

    @Test fun sameInputSameProposal() {
        val a = riderAtDefault(newSession()).getJSONObject("proposal").getJSONObject("best").getString("id")
        val b = riderAtDefault(newSession()).getJSONObject("proposal").getJSONObject("best").getString("id")
        assertEquals(a, b)
    }

    @Test fun clockRejectsBadValuesAndKeepsSession() {
        val sid = newSession()
        eng.action(sid, "clock", JSONObject().put("hhmm", "21:30").put("weekday", false))
        for (bad in listOf(JSONObject().put("hhmm", "24:00"), JSONObject().put("hhmm", "12:60"), JSONObject().put("hhmm", "7:30"),
            JSONObject().put("hhmm", 730), JSONObject().put("hhmm", "08:00").put("weekday", "yes"))) {
            assertThrows(JSONException::class.java) { eng.action(sid, "clock", bad) }
        }
        val clock = eng.get(sid)!!.getJSONObject("clock")
        assertEquals("21:30", clock.getString("hhmm"))
        assertFalse(clock.getBoolean("weekday"))
        assertEquals("23:59", eng.action(sid, "clock", JSONObject().put("hhmm", "23:59"))!!.getJSONObject("clock").getString("hhmm"))
    }

    @Test fun carAndRiderRejectNonNumbersAndRange() {
        val sid = newSession()
        assertThrows(JSONException::class.java) { eng.action(sid, "car", JSONObject().put("progress", true)) }
        assertThrows(JSONException::class.java) { eng.action(sid, "car", JSONObject().put("gear", 3)) }
        assertThrows(JSONException::class.java) { eng.action(sid, "rider", JSONObject().put("lat", 91.0).put("lon", 127.0)) }
        assertThrows(JSONException::class.java) { eng.action(sid, "rider", JSONObject().put("lat", "37.5").put("lon", 127.0)) }
        val s = eng.get(sid)!!
        assertTrue("거절된 위치는 기록하지 않는다", s.getJSONObject("rider").isNull("lat"))
        assertEquals(0.0, s.getJSONObject("car").getDouble("progress"), 0.0)
    }

    @Test fun createSessionKeepsOnlyKnownFields() {
        val sid = newSession(JSONObject().put("member", JSONObject().put("name", "가".repeat(50)).put("type", "alien"))
            .put("clock", JSONObject().put("hhmm", "99:99")).put("carDesc", "x".repeat(100)))
        val s = eng.get(sid)!!
        assertEquals(20, s.getJSONObject("member").getString("name").length)
        assertTrue(s.getJSONObject("member").getString("type") != "alien")
        assertEquals(40, s.getJSONObject("car").getString("desc").length)
        assertTrue("잘못된 시각은 기본 시각으로", s.getJSONObject("clock").getString("hhmm").matches(Regex("[0-9]{2}:[0-9]{2}")))
    }

    @Test fun unknownActionSessionAndKind() {
        val sid = newSession()
        assertNull(eng.action(sid, "teleport", JSONObject()))
        assertNull(eng.action("0000000000000000", "car", JSONObject()))
        assertNull(eng.get("nope"))
        assertThrows(JSONException::class.java) { eng.nearby(sid, "casino") }
        assertNotNull(eng.nearby(sid, "parking"))
    }

    @Test fun boardingStoresOnlyKnownFields() {
        val sid = newSession()
        assertThrows(JSONException::class.java) { eng.action(sid, "boarding", JSONObject().put("state", "flying")) }
        val s = eng.action(sid, "boarding", JSONObject().put("state", "boarded").put("seatLabel", "뒷좌석 오른쪽").put("secret", "x"))!!
        val b = s.getJSONObject("boarding")
        assertEquals("boarded", s.getString("status"))
        assertFalse(b.has("secret"))
        assertFalse(b.getBoolean("riderConfirmed"))
    }

    @Test fun sessionIdIs64BitHex() {
        val sid = newSession()
        assertTrue(sid.matches(Regex("[0-9a-f]{16}")))
    }
}
