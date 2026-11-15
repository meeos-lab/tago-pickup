package ai.meeos.tago.road

/**
 * "이번 주행 요약"과 [경고 틀림] 기록(2026-11-13). 순수 집계 — 판정·허브·경고 순서에는 아무 영향이 없다(기록만).
 * 알림 = 말한 위험(id), 확인 = 그중 허브가 확정(차량 2대 이상 등)으로 본 것, 틀림 = 운전자가 정차 중에 [경고 틀림]으로 표시한 것.
 * 틀림 표시는 가장 최근에 알린 위험 하나에 붙고, 같은 위험은 한 번만 센다. 단위 시험 DriveSummaryTest.
 */
class DriveSummary {
    private val alerted = LinkedHashSet<String>()
    private val confirmed = HashSet<String>()
    private val wrong = LinkedHashSet<String>()
    private var startedAt = 0L
    private var endedAt = 0L

    val alertCount: Int get() = alerted.size
    val confirmedCount: Int get() = alerted.count { it in confirmed }
    val wrongCount: Int get() = wrong.size
    /** 가장 최근에 알린 위험(틀림 표시의 대상). 없으면 null */
    val lastAlert: String? get() = alerted.lastOrNull()
    /** 이미 틀림으로 표시했나 */
    fun isFlagged(id: String): Boolean = id in wrong

    fun start(now: Long) { alerted.clear(); confirmed.clear(); wrong.clear(); startedAt = now; endedAt = 0L }
    fun finish(now: Long) { if (endedAt == 0L) endedAt = now }

    fun alert(id: String) { alerted.add(id) }
    fun confirm(id: String) { confirmed.add(id) }

    /** [경고 틀림]: 가장 최근 알림이 있고 아직 표시하지 않았으면 그 id 를 돌려주고 센다. 아니면 null */
    fun flagLast(): String? {
        val id = lastAlert ?: return null
        return if (wrong.add(id)) id else null
    }

    /** 표시할 만한 요약인가(알림이 하나라도 있었거나 일정 시간 달렸다) */
    fun worthShowing(minMs: Long = 30_000L): Boolean = startedAt != 0L && (alertCount > 0 || (endedAt != 0L && endedAt - startedAt >= minMs))

    /** 걸린 시간(분, 내림, 최소 0) */
    fun minutes(now: Long): Int = if (startedAt == 0L) 0 else (((if (endedAt != 0L) endedAt else now) - startedAt) / 60_000L).toInt().coerceAtLeast(0)
}
