package ai.meeos.tago.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * 앱 내장 허브(LinkHub)의 경계 시험(2026-11-13, docs/dev/95) — PC 허브의 test_hub_boundaries.py 와 짝.
 * LocalServer 는 JSONException 을 400 으로 바꾸고 그 밖의 예외는 500 이므로, 이상한 값에서 JSONException 외의 예외가 나면 안 된다(500 = 결함).
 * 또 한 대의 이상한 보고가 다른 차의 보기를 깨면 안 된다.
 */
class LinkHubBoundaryTest {
    private lateinit var hub: LinkHub
    private lateinit var ids: List<String>

    @Before fun load() {
        hub = LinkHub(MeeosEngineTest.server("link/gyeongbu_route.json"))
        ids = listOf("A", "B", "C").mapIndexed { i, n ->
            val me = post("join", JSONObject().put("name", n).put("t", 1)).second.getString("me")
            post("state", JSONObject().put("me", me).put("s", 1000.0 * (i + 1)).put("speed", 25.0).put("t", 2))
            me
        }
    }

    private fun post(act: String, b: JSONObject) = hub.handle("POST", listOf("api", "road", act), emptyMap(), b)
    private fun viewOf(me: String) = hub.handle("GET", listOf("api", "road"), mapOf("me" to me), JSONObject())

    @Test fun chargerStatusStationsMustBeArray() {
        for (bad in listOf<Any>(1, 2.5, true, "abc", JSONObject().put("a", 1), JSONObject.NULL, JSONArray().put(1).put("x").put(JSONObject().put("statId", 5).put("chargers", "no")))) {
            val (code, _) = post("chargerstatus", JSONObject().put("t", 3).put("me", ids[0]).put("stations", bad))
            assertEquals(bad.toString(), 200, code)
        }
        assertEquals(200, viewOf(ids[1]).first)
    }

    @Test fun unknownCarAndOddEventTypesNeverThrow() {
        for (me in listOf("", "c000000000000", "x".repeat(500))) {
            for (act in listOf("state", "event", "respond")) {
                val code = post(act, JSONObject().put("me", me).put("t", 3).put("s", 100.0).put("speed", 5.0).put("type", "HARD_BRAKE").put("value", "x")).first
                assertTrue("$act/$me → $code", code in setOf(200, 400, 404))
            }
        }
        for (typ in listOf("EVIL", "", "<script>", "IMPACT".repeat(50), "한글")) {
            val code = post("event", JSONObject().put("me", ids[0]).put("t", 3).put("type", typ).put("value", "v".repeat(300)).put("s", 100.0)).first
            assertTrue("type $typ → $code", code in setOf(200, 400))
        }
        assertEquals(200, viewOf(ids[2]).first)
    }

    /** 올바른 본문에서 한 칸을 이상 값으로 바꾼 요청 4000개(고정 시드). JSONException(=400) 외의 예외는 결함, 매 요청 뒤 모든 차의 보기가 문자열로 나간다 */
    @Test fun mutatedRequestsNeverCrashOrPoisonViews() {
        val r = Random(20261113)
        val bases = mapOf(
            "state" to { JSONObject().put("s", 1500.0).put("speed", 22.0).put("wiper", "OFF").put("soc", 50).put("temp", 20).put("limit", 100).put("offRoute", false) },
            "event" to { JSONObject().put("type", "HARD_BRAKE").put("value", "9.0->2.0").put("s", 1500.0).put("speed", 5.0) },
            "respond" to { JSONObject().put("ok", true).put("timeout", false) },
            "official" to { JSONObject().put("items", JSONArray().put(JSONObject().put("type", "ACCIDENT").put("s", 2000).put("desc", "x"))) },
            "chargerstatus" to { JSONObject().put("stations", JSONArray().put(JSONObject().put("statId", "a").put("chargers", JSONArray().put(JSONObject().put("id", "1").put("stat", "2"))))) },
        )
        val acts = bases.keys.toList()
        for (i in 0 until 4000) {
            val act = acts[r.nextInt(acts.size)]
            val b = bases.getValue(act)().put("me", ids[r.nextInt(ids.size)]).put("t", 3 + i % 5)
            val keys = b.keys().asSequence().toList() + "extra"
            b.put(keys[r.nextInt(keys.size)], odd(r, 0))
            try { post(act, b) } catch (e: JSONException) { /* LocalServer 가 400 으로 바꾼다 */ } catch (e: Exception) { throw AssertionError("#$i $act $b → $e", e) }
            for (me in ids) try { viewOf(me).second.toString() } catch (e: Exception) { throw AssertionError("#$i 뒤에 $me 의 보기가 깨짐: $act $b → $e", e) }
        }
    }

    private fun odd(r: Random, depth: Int): Any = when (r.nextInt(12)) {
        0 -> 0; 1 -> -1; 2 -> Long.MAX_VALUE; 3 -> 1e300; 4 -> -0.5; 5 -> ""; 6 -> "NaN"; 7 -> "x".repeat(500); 8 -> JSONObject.NULL; 9 -> true
        10 -> if (depth < 2) JSONArray().put(odd(r, depth + 1)).put(odd(r, depth + 1)) else 0
        else -> if (depth < 2) JSONObject().put("a", odd(r, depth + 1)) else "한글🚗"
    }
}
