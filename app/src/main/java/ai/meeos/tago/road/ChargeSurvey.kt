package ai.meeos.tago.road

import org.json.JSONObject

/**
 * 충전기 고장 신고 1분 설문의 답. 한 화면에 한 문항, 그림 타일 하나를 누르면 다음 문항으로 넘어간다(사용자: "한번에 다 띄우려고 하지마", "문항당 한번씩").
 * 순서: 증상 → 기기 번호 → 어느 자리(번호를 골랐으면 묻지 않는다) → 몇 대 → 다른 기기. 모르면 [건너뛰기](빈 답 = 모름, 허브가 버린다). 마지막 문항을 고르면 보낸다.
 * from = 충전이 안 된 자리(경로 위 s) — 허브는 이 자리로 휴게소를 찾고, 같은 자리(2.5 m 안)에서 다른 차가 충전되면 이 신고를 거둔다.
 * 정차 중에만 연다(주행 중에는 말로 "충전기 고장"만 받는다). docs/dev/40-algorithms.md A-15
 */
class ChargeSurvey(val from: Double) {
    val answers = linkedMapOf<String, String>().apply { OPTIONS.keys.forEach { put(it, "") } }
    var step = 0
        private set

    /** 지금 묻는 문항들(기기 번호를 골랐으면 자리는 뺀다) */
    val keys: List<String> get() = OPTIONS.keys.filter { !(it == "place" && answers["unit"]?.isNotEmpty() == true) }
    val key: String? get() = keys.getOrNull(step)
    val done: Boolean get() = step >= keys.size

    /** 지금 문항에 답하고 다음으로. 마지막이면 done */
    fun pick(id: String) {
        val k = key ?: return
        if (id !in OPTIONS.getValue(k)) return
        answers[k] = id
        step = keys.indexOf(k) + 1
    }
    fun skip() { val k = key ?: return; answers[k] = ""; step = keys.indexOf(k) + 1 }
    fun back() { if (step > 0) step-- }

    /** CHARGE_REPORT 이벤트에 싣는 값: sym·place·broken·alt(문자열, "" 은 허브가 버림), unit(정수, 0 = 모름), from */
    fun body(): JSONObject = JSONObject().put("from", from)
        .put("sym", answers["sym"]).put("place", answers["place"]).put("broken", answers["broken"]).put("alt", answers["alt"])
        .put("unit", answers["unit"]?.toIntOrNull() ?: 0)

    companion object {
        /** 문항 → 답 id(화면 타일 순서) */
        val OPTIONS: Map<String, List<String>> = linkedMapOf(
            "sym" to listOf("no_start", "screen_off", "payment", "connector", "notice"),
            "unit" to (1..12).map { "$it" },
            "place" to listOf("entrance", "middle", "end"),
            "broken" to listOf("1", "2", "3", "all"),
            "alt" to listOf("yes", "no", "wait"),
        )
    }
}
