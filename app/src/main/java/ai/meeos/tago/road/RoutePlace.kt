package ai.meeos.tago.road

import org.json.JSONObject

/**
 * 시연 경로(경부고속도로 하행 자산 `link/gyeongbu_route.json`)에서 "지금 어디쯤인가"를 글로 만든다(응급 카드·주행 요약, 2026-11-13).
 * 도로 이름·방향(경로 이름), 가까운 나들목(앞/뒤 거리), 가까운 휴게소. 경로 위 위치 s(m)만 받는다 — 좌표는 쓰지 않는다.
 * 앱 안 순수 계산이라 서버·두 엔진 대조와 무관하다(단위 시험 RoutePlaceTest).
 */
class RoutePlace(file: JSONObject) {
    class Pt(val s: Double, val name: String, val nameEn: String)

    val name: String = file.optString("name")
    val nameEn: String = file.optString("nameEn", name)
    val length: Double = file.optDouble("length", 0.0)
    val junctions: List<Pt> = pts(file.optJSONArray("junctions"))
    val rests: List<Pt> = pts(file.optJSONArray("restAreas"))
    /** 단속카메라(경로 파일 cameras — 경찰청 표준데이터, build_cameras.py): s · 제한속도 · 단속구분 코드 · 구간단속 시작/끝 */
    class Cam(val s: Double, val limit: Int, val kind: String, val role: String?)
    val cameras: List<Cam> = file.optJSONArray("cameras")?.let { a ->
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            val s = o.optDouble("s", Double.NaN); val lim = o.optInt("limit", 0)
            if (s.isFinite() && lim > 0) Cam(s, lim, o.optString("kind"), o.optString("role").takeIf { it == "start" || it == "end" }) else null } }
            .sortedBy { it.s }
    } ?: emptyList()

    /** 지금 보일 단속 알림(link.html camAlertSync 와 같은 규칙, 11-15): 앞 [nearM] 안의 고정식 과속(kind 1) · 구간단속 시작 → AHEAD(남은 m),
     *  구간단속 안(시작 ≤ s < 끝) → IN_SECTION(끝까지 m). 둘 다면 앞 카메라가 가까울 때(500 m 안) 그것, 아니면 구간 */
    enum class CamKind { SPEED, SECTION_START, IN_SECTION }
    class CamAlert(val limit: Int, val kind: CamKind, val distM: Double)
    fun camAlert(s: Double, nearM: Double = 1000.0): CamAlert? {
        val ahead = cameras.firstOrNull { it.s > s && it.s - s <= nearM && (it.kind == "1" || (it.kind == "99" && it.role == "start")) }
            ?.let { CamAlert(it.limit, if (it.kind == "99") CamKind.SECTION_START else CamKind.SPEED, it.s - s) }
        var start: Cam? = null; var sec: CamAlert? = null
        for (c in cameras) {
            if (c.kind == "99" && c.role == "start") start = c
            else if (c.kind == "99" && c.role == "end" && start != null) { if (s >= start.s && s < c.s) sec = CamAlert(start.limit, CamKind.IN_SECTION, c.s - s); start = null }
        }
        return when { ahead != null && (sec == null || ahead.distM <= 500.0) -> ahead; else -> sec ?: ahead }
    }

    /** 도로 이름·방향: "경부고속도로 하행 (서울 양재 → 천안)" 같은 경로 이름 그대로 */
    fun road(english: Boolean): String = if (english) nameEn else name

    /** s 에서 가장 가까운 점과 부호 있는 거리(앞 +, 뒤 −, m). 목록이 비면 null */
    fun nearest(list: List<Pt>, s: Double): Pair<Pt, Double>? =
        list.minByOrNull { Math.abs(it.s - s) }?.let { it to it.s - s }

    fun nearestJunction(s: Double) = nearest(junctions, s)
    fun nearestRest(s: Double) = nearest(rests, s)

    /** 경로 끝(도착)에 가까운가 — 마지막 점 + 여유(m) 안 */
    fun arrived(s: Double, slackM: Double = 300.0): Boolean = length > 0 && s >= length - slackM

    private fun pts(a: org.json.JSONArray?): List<Pt> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> if (o.has("s") && !o.isNull("s") && o.optDouble("s", Double.NaN).isFinite()) Pt(o.getDouble("s"), o.optString("name"), o.optString("nameEn", o.optString("name"))) else null }
        }.sortedBy { it.s }
}
