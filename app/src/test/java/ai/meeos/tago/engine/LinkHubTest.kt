package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 도로 링크 허브 규칙(40 4절·51 1~21): 경로 파일은 앱 자산과 같은 server/link/gyeongbu_route.json.
 *  두 엔진 대조(check_link_parity.py)는 에뮬레이터가 있어야 하므로, 확정 규칙·입력 처리 몇 가지만 여기서 고정한다. */
class LinkHubTest {
    private lateinit var hub: LinkHub

    @Before fun load() { hub = LinkHub(MeeosEngineTest.server("link/gyeongbu_route.json")) }

    private fun post(act: String, b: JSONObject) = hub.handle("POST", listOf("api", "road", act), emptyMap(), b)
    private fun join(name: String, s: Double, t: Double): String {
        val me = post("join", JSONObject().put("name", name).put("t", t)).second.getString("me")
        post("state", JSONObject().put("me", me).put("s", s).put("speed", 25.0).put("t", t))
        return me
    }
    private fun hazards(me: String, type: String): List<JSONObject> {
        val hz: JSONArray = post("state", JSONObject().put("me", me)).second.getJSONArray("hazards")
        return (0 until hz.length()).map { hz.getJSONObject(it) }.filter { it.getString("type") == type }
    }

    @Test fun routeAndUnknownPaths() {
        val (code, body) = hub.handle("GET", listOf("api", "road", "route"), emptyMap(), JSONObject())
        assertEquals(200, code)
        assertTrue(body.getJSONArray("route").length() > 1)
        assertEquals(404, post("teleport", JSONObject()).first)
        assertEquals(404, hub.handle("GET", listOf("api", "link", "nogroup"), emptyMap(), JSONObject()).first)
    }

    @Test fun emergencyVehicleNeedsToken() {
        assertEquals(403, post("join", JSONObject().put("kind", "ev")).first)
        assertEquals(403, post("join", JSONObject().put("kind", "ev").put("token", "guess")).first)
        assertEquals(200, post("join", JSONObject().put("kind", "ev").put("token", hub.evToken)).first)
    }

    @Test fun carIdIsPrivate() {
        val a = join("A", 5000.0, 1.0)
        val b = join("B", 4000.0, 1.0)
        assertTrue(a.startsWith("c"))
        val seen = post("state", JSONObject().put("me", b)).second.toString()
        assertFalse("남의 차에는 보기용 이름표만 보인다", seen.contains(a))
    }

    @Test fun debrisNeedsTwoCars() {
        val a = join("A", 5000.0, 1.0)
        val b = join("B", 5050.0, 1.0)
        val c = join("C", 3000.0, 1.0)   // 뒤에서 오는 차
        post("event", JSONObject().put("me", a).put("type", "SWERVE").put("t", 2.0))
        assertTrue("나만 보고한 위험은 나에게 안 알린다", hazards(a, "DEBRIS").isEmpty())
        val one = hazards(c, "DEBRIS").single()
        assertFalse("한 대 보고는 의심", one.getBoolean("confirmed"))
        assertEquals(1, one.getInt("cars"))
        post("event", JSONObject().put("me", b).put("type", "SWERVE").put("t", 3.0))
        val two = hazards(c, "DEBRIS").single()                    // 가까운 두 보고는 한 위험으로 합친다
        assertTrue("다른 차 두 대 = 확정(ANON_CONFIRM)", two.getBoolean("confirmed"))
        assertEquals(LinkHub.ANON_CONFIRM, two.getInt("cars"))
    }

    @Test fun clockOnlyMovesForwardAndFinite() {
        join("A", 1000.0, 100.0)
        for (bad in listOf("{\"t\":50}", "{\"t\":\"NaN\"}", "{\"t\":\"Infinity\"}", "{\"t\":null}"))
            post("state", JSONObject(bad))
        val world = hub.handle("GET", listOf("api", "road", "world"), emptyMap(), JSONObject()).second
        assertEquals(100.0, world.getDouble("now"), 1e-9)
    }

    @Test fun clockSkewIsFlaggedAndNotUsedForWindowing() {
        val a = join("A", 5000.0, 1000.0)
        val b = join("B", 4800.0, 1000.0)
        // B 의 시계가 201 s 앞섬: 허브 시계는 안 뛰고(B 의 보고 나이가 0 근처) B 에게만 clockSkew(실제 경과 ms 만큼 작게 나올 수 있어 ±1)
        val r = post("event", JSONObject().put("me", b).put("type", "HARD_BRAKE").put("value", "x").put("s", 4800.0).put("speed", 5.0).put("t", 1201.0)).second
        assertEquals(201.0, r.getDouble("clockSkew"), 1.0)
        val world = hub.handle("GET", listOf("api", "road", "world"), emptyMap(), JSONObject()).second
        assertEquals("허브 시계는 뛰지 않는다", 1000.0, world.getDouble("now"), 1e-9)
        assertTrue("정상 시계 차는 표시가 없다", post("state", JSONObject().put("me", a).put("t", 1001.0)).second.isNull("clockSkew"))
        assertTrue("30 s 안은 받는다", post("state", JSONObject().put("me", b).put("t", 1020.0)).second.isNull("clockSkew"))
        assertEquals(-200.0, post("state", JSONObject().put("me", b).put("t", 820.0)).second.getDouble("clockSkew"), 1.0)   // 뒤처진 시계
        assertEquals(1020.0, hub.handle("GET", listOf("api", "road", "world"), emptyMap(), JSONObject()).second.getDouble("now"), 1e-9)
    }

    @Test fun adviceCappedBySpeedLimit() {
        val adv = { lim: Int?, zoneLimit: Int? ->
            val route = JSONObject(MeeosEngineTest.server("link/gyeongbu_route.json"))
            if (zoneLimit != null) route.put("zones", JSONArray().put(JSONObject().put("id", "z1").put("kind", "accident").put("s0", 2000.0).put("s1", 9000.0)
                .put("name", "x").put("nameEn", "x").put("limit", zoneLimit)))
            val h = LinkHub(route.toString())
            val p = { act: String, b: JSONObject -> h.handle("POST", listOf("api", "road", act), emptyMap(), b).second }
            val me = p("join", JSONObject().put("name", "A").put("t", 1.0)).getString("me")
            val cars = (1..3).map { p("join", JSONObject().put("name", "J$it").put("t", 1.0)).getString("me") }
            for (k in 0..30) {                                // 앞 2.5 km 에 세 대가 20 s 넘게 느리게 = 정체 꼬리
                for (c in cars) p("state", JSONObject().put("me", c).put("s", 7500.0 + k * 2.0).put("speed", 2.0).put("t", 2.0 + k))
                val b = JSONObject().put("me", me).put("s", 5000.0 + k * 30.0).put("speed", 30.0).put("t", 2.0 + k)
                if (lim != null) b.put("limit", lim)
                p("state", b)
            }
            p("state", JSONObject().put("me", me).put("s", 5950.0).put("speed", 30.0).put("t", 40.0).also { if (lim != null) it.put("limit", lim) })
                .getJSONObject("ahead").optJSONObject("advice")
        }
        assertEquals(LinkHub.ADVISE_KMH, adv(null, null)!!.getInt("adviseKmh"))
        assertEquals(70, adv(70, null)!!.getInt("adviseKmh"))
        assertEquals(LinkHub.ADVISE_KMH, adv(100, null)!!.getInt("adviseKmh"))     // 제한이 80 보다 높으면 80 그대로
        assertEquals(60, adv(70, 60)!!.getInt("adviseKmh"))                        // 정체 꼬리가 든 구역의 limit 이 먼저
        assertTrue(adv(70, null)!!.getString("say").contains("70"))
    }

    @Test fun regionUnitsChangeEnglishDistanceText() {
        assertEquals("300 m", LinkHub.distText("en", 300.0, "km"))
        assertEquals("3.2 km", LinkHub.distText("en", 3200.0, "km"))
        assertEquals("1000 ft", LinkHub.distText("en", 300.0, "mi"))
        assertEquals("2.0 mi", LinkHub.distText("en", 3200.0, "mi"))
        assertEquals("300m", LinkHub.distText("ko", 300.0, "mi"))                  // 한국어는 단위와 무관
        val mi = post("join", JSONObject().put("name", "A").put("t", 1.0).put("lang", "en").put("units", "mi")).second
        assertEquals("mi", mi.getJSONObject("view").getString("units"))
        val bad = post("join", JSONObject().put("name", "B").put("t", 1.0).put("lang", "en").put("units", "furlong")).second
        assertEquals("km", bad.getJSONObject("view").getString("units"))
    }

    @Test fun eventFromUnknownCarIsIgnored() {
        val c = join("C", 3000.0, 1.0)
        post("event", JSONObject().put("me", "c000000000000").put("s", 5000.0).put("type", "SWERVE"))
        assertTrue(hazards(c, "DEBRIS").isEmpty())
        assertNull(hub.car("c000000000000"))
    }

    /** 동승(ADR-47): 위치 없는 휴대폰 참가자가 '같이 타요'로 합류하면 탄 차와 같은 위험 문장을 받는다. 따로 가는 참가자는 위치가 없으니 문장이 없다 */
    @Test fun riderSeesHostAlertsWithoutLocation() {
        val host = join("H", 18000.0, 1.0)
        val g = hub.handle("POST", listOf("api", "link", "group"), emptyMap(), JSONObject().put("me", host).put("t", 1.0)).second
        fun joinWeb(ride: Boolean) = hub.handle("POST", listOf("api", "link", "join"), emptyMap(),
            JSONObject().put("code", g.getString("code")).put("name", if (ride) "R" else "S").put("kind", "web").put("ride", ride).put("t", 1.5)).second.getString("me")
        val rider = joinWeb(true)
        val sep = joinWeb(false)
        val a = join("A", 20000.0, 2.0)
        val b = join("B", 20040.0, 2.0)
        post("event", JSONObject().put("me", a).put("type", "SWERVE").put("t", 3.0))
        post("event", JSONObject().put("me", b).put("type", "SWERVE").put("t", 4.0))
        val hostSay = hazards(host, "DEBRIS").single().optString("say")
        assertTrue("탄 차는 2 km 앞 확정 낙하물을 말한다", hostSay.isNotEmpty())
        val rv = post("state", JSONObject().put("me", rider)).second
        assertEquals("H", rv.getJSONObject("seenFrom").getString("name"))
        assertEquals(18000.0, rv.getDouble("s"), 1e-9)
        assertEquals("동승자는 탄 차와 같은 문장", hostSay, hazards(rider, "DEBRIS").single().optString("say"))
        val sv = post("state", JSONObject().put("me", sep)).second
        assertTrue(sv.isNull("seenFrom"))
        assertTrue("따로 가는 참가자(위치 없음)는 문장 없음", hazards(sep, "DEBRIS").none { it.has("say") })
    }

    /** 최소 우회 안내(FR-G-36, 통합 기준): 확정 정체 9 km 앞·다음 나들목(수원신갈 s 23625) 6.6 km → detour, 막힘 10 km 밖·다음 나들목 1 km 안이면 없음,
     *  나들목을 지나 다음 나들목이 막힘 뒤면 권고 없음(시뮬 detour_rules.eligible 과 같다). 문장에 나들목 이름 없음 */
    @Test fun detourFollowsUnifiedRule() {
        val me = join("M", 17000.0, 1.0)
        for ((i, s) in listOf(26000.0, 26030.0).withIndex()) {
            val o = join("x$i", s, 1.0)
            post("event", JSONObject().put("me", o).put("type", "CONGESTION").put("value", "11km/h").put("s", s).put("speed", 3.0).put("t", 2.0))
        }
        fun detourAt(s: Double, t: Double) = post("state", JSONObject().put("me", me).put("t", t).put("s", s).put("speed", 25.0)).second.optJSONObject("detour")
        val d = detourAt(17000.0, 3.0)!!
        assertEquals("ic4", d.getString("ic"))
        assertEquals(6625, d.getInt("icDist"))
        assertEquals(9000, d.getInt("inc"))
        assertEquals("JAM", d.getString("reason"))
        assertEquals(d.getString("hazard"), d.getString("id"))
        assertEquals("전방 9.0km 막힘 확정. 우회 경로를 확인하세요.", d.getString("say"))
        assertTrue(LinkHub.DETOUR_MIN_IC_M == 1000.0 && LinkHub.DETOUR_MAX_INC_M == 10000.0 && LinkHub.DETOUR_DELAY_S == 2.0 && LinkHub.DETOUR_CLEAR_GRACE_S == 300.0)
        assertNull("막힘이 10 km 밖이면 없음", detourAt(15500.0, 4.0))
        assertNull("다음 나들목이 1 km 안이면 없음", detourAt(22700.0, 5.0))
        assertNull("나들목을 지나면 다음 나들목(s 28143)이 막힘(26000) 뒤라 권고 없음", detourAt(24000.0, 6.0))
    }

    /** 우회 확장: 배정 추첨(FNV-1a 32 — 알려진 값), 응답(detourAck) 되돌림, 위경도 경로 이탈(offRoute) */
    @Test fun detourAssignAckAndOffRoute() {
        assertEquals(0x811C9DC5L, LinkHub.fnv1a32(""))
        assertEquals(0xE40C292CL, LinkHub.fnv1a32("a"))
        val me = join("M", 17000.0, 1.0)
        for ((i, s) in listOf(26000.0, 26030.0).withIndex()) {
            val o = join("x$i", s, 1.0)
            post("event", JSONObject().put("me", o).put("type", "CONGESTION").put("value", "11km/h").put("s", s).put("speed", 3.0).put("t", 2.0))
        }
        val v0 = post("state", JSONObject().put("me", me).put("t", 3.0).put("s", 17000.0).put("speed", 25.0)).second
        assertTrue("위경도를 보낸 적 없으면 null", v0.isNull("offRoute"))
        val d = v0.getJSONObject("detour")
        assertEquals(LinkHub.DETOUR_P_FIXED, d.getDouble("p"), 1e-12)
        assertEquals(0.5, d.getDouble("p"), 1e-9)
        val want = if (LinkHub.fnv1a32("$me:${d.getString("hazard")}") / 4294967296.0 < 0.5) "detour" else "stay"
        assertEquals(want, d.getString("assign"))
        assertTrue(d.isNull("ack"))
        val v1 = post("state", JSONObject().put("me", me).put("t", 4.0).put("s", 17000.0).put("speed", 25.0)
            .put("detourAck", JSONObject().put("hazard", d.getString("hazard")).put("ack", "accepted"))
            .put("lat", 37.0).put("lon", 128.5)).second
        assertEquals("accepted", v1.getJSONObject("detour").getString("ack"))
        assertTrue("경로에서 수십 km 떨어진 위경도", v1.getBoolean("offRoute"))
    }

    /** 휴게소 제외(11-08): 기흥휴게소(s 27119) 앞 본선 위 위경도로 멈춘 충돌 두 대는 사고 의심 유지, 경로에서 옆으로 약 70 m 면 지운다 */
    @Test fun restExclusionNeedsLateralOffset() {
        fun crashAt(lonShift: Double): List<JSONObject> {
            load()
            val me = join("M", 21000.0, 1.0)
            for (i in 0..1) {
                val sx = 27219.0 + 20 * i
                val o = join("r$i", sx, 1.0)
                post("event", JSONObject().put("me", o).put("type", "IMPACT").put("value", "25.0→0.0 m/s").put("s", sx).put("speed", 0.0).put("t", 2.0))
                val ll = hub.pointAt(sx)
                for (t in listOf(3.0, 20.0, 45.0)) post("state", JSONObject().put("me", o).put("t", t).put("s", sx).put("speed", 0.0)
                    .put("lat", ll.getDouble(0)).put("lon", ll.getDouble(1) + lonShift))
            }
            post("state", JSONObject().put("me", me).put("t", 46.0).put("s", 21000.0).put("speed", 25.0))
            return hazards(me, "INCIDENT")
        }
        assertTrue("본선 위 정차는 휴게소 차가 아니다", crashAt(0.0).any { it.getBoolean("confirmed") })
        assertTrue("휴게소 주차장(옆으로 약 70 m)은 지운다", crashAt(0.0008).isEmpty())
        assertEquals(30.0, LinkHub.REST_LATERAL_M, 0.0)
    }
}
