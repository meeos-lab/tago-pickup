package ai.meeos.tago.voice

import ai.meeos.tago.road.RoadLinkController.Companion.freshHazardSay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 말하기 대기열: 포커스를 기다리는 긴급 안내가 낡으면 다시 만들고, 같은 안내는 한 번만, 버려지면 알린다(깜빡이 연동 시스템 카메라 B-88) */
class SpeechQueueTest {
    private fun item(text: String, key: String? = null, fresh: (() -> String?)? = null, onDropped: (() -> Unit)? = null) =
        SpeechQueue.Item(text, key, fresh, onDropped)

    @Test fun plainSentencesKeepOrderAndDropOldestOverMax() {
        val q = SpeechQueue(max = 3)
        for (i in 1..4) q.add(item("s$i"), 0)
        assertEquals(3, q.size)
        assertEquals("s2", q.poll(0)?.text)   // s1 은 상한을 넘어 버려졌다
        assertEquals("s3", q.poll(0)?.text)
        assertEquals("s4", q.poll(0)?.text)
        assertNull(q.poll(0))
    }

    @Test fun staleSentenceIsRebuiltFromLatestState() {
        val q = SpeechQueue(staleMs = 5_000)
        var distance = 4800
        q.add(item("전방 4.8km 막힘", fresh = { "전방 ${distance / 1000.0}km 막힘" }), now = 0)
        distance = 4200
        val soon = q.poll(now = 4_000)!!   // 아직 낡지 않았다 — 그대로
        assertEquals("전방 4.8km 막힘", soon.text)
        q.addFirst(soon)                   // 포커스 거절로 되돌림
        val later = q.poll(now = 9_000)!!  // 5초 넘게 기다림 — 최신 상태로 다시 만든다
        assertEquals("전방 4.2km 막힘", later.text)
    }

    @Test fun staleSentenceWithNothingToSayIsSkipped() {
        val q = SpeechQueue(staleMs = 5_000)
        var dropped = 0
        q.add(item("긴급차량", fresh = { null }, onDropped = { dropped++ }), 0)   // 위험이 사라졌다
        q.add(item("다음 안내"), 0)
        assertEquals("다음 안내", q.poll(10_000)?.text)
        assertEquals(0, dropped)           // 필요 없어진 것이라 '버려졌다' 알림은 없다
    }

    @Test fun sameKeyReplacesWaitingSentence() {
        val q = SpeechQueue()
        q.add(item("후방 1km 긴급차량", key = "hz:h1"), 0)
        q.add(item("후방 500m 긴급차량 근접", key = "hz:h1"), 1_000)
        assertEquals(1, q.size)
        assertEquals("후방 500m 긴급차량 근접", q.poll(1_000)?.text)
        assertNull(q.poll(1_000))          // 같은 안내가 두 번 나오지 않는다
    }

    @Test fun requeuedInFlightSentenceIsNotDuplicated() {
        val q = SpeechQueue()
        val a = item("a", key = "hz:h1")
        q.add(a, 0); val cur = q.poll(0)!!
        q.addFirst(cur); q.addFirst(cur)   // 포커스를 빼앗겨 되돌리는 일이 겹쳐도
        assertEquals(1, q.size)
    }

    @Test fun requeuedOlderSentenceDoesNotEraseNewerSameKey() {
        val q = SpeechQueue()
        q.add(item("옛 문장", key = "hz:h1"), 0); val cur = q.poll(0)!!
        q.add(item("새 문장", key = "hz:h1"), 1_000)   // 옛 문장을 말하는 동안 같은 안내의 새 문장이 줄에 섰다
        q.addFirst(cur)                                 // 포커스를 잠깐 빼앗겨 옛 문장을 되돌려도
        assertEquals(1, q.size)
        assertEquals("새 문장", q.poll(1_000)?.text)    // 새 문장이 남는다
    }

    @Test fun overflowAndClearReportDropped() {
        val q = SpeechQueue(max = 2)
        val dropped = mutableListOf<String>()
        for (t in listOf("a", "b", "c")) q.add(item(t, onDropped = { dropped += t }), 0)
        assertEquals(listOf("a"), dropped)
        q.clear()
        assertEquals(listOf("a", "b", "c"), dropped)
        assertTrue(q.isEmpty())
    }

    // ---- 허브 보기에서 위험의 지금 문장 다시 읽기(RoadLinkController.freshHazardSay)

    private fun view(vararg h: JSONObject) = JSONObject().put("hazards", JSONArray(h.toList()))
    private fun hz(id: String, level: Int, say: String?) = JSONObject().put("id", id).put("level", level).also { if (say != null) it.put("say", say) }

    @Test fun freshHazardSayReadsSameHazardAndLevel() {
        val v = view(hz("h1", 1, "후방 1.2km 긴급차량 접근 중."), hz("h2", 1, "다른 위험"))
        assertEquals("후방 1.2km 긴급차량 접근 중.", freshHazardSay(v, "h1", 1))
    }

    @Test fun freshHazardSayIsNullWhenGoneOrLevelMovedOnOrNoSentence() {
        assertNull(freshHazardSay(null, "h1", 1))                                          // 보기가 없다
        assertNull(freshHazardSay(view(hz("h2", 1, "x")), "h1", 1))                         // 위험이 사라졌다
        assertNull(freshHazardSay(view(hz("h1", 2, "후방 500m 긴급차량 근접.")), "h1", 1))   // 단계가 올랐다 — 새 문장이 따로 온다
        assertNull(freshHazardSay(view(hz("h1", 1, null)), "h1", 1))                        // 지금은 말할 문장이 없다
    }
}
