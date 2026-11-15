package ai.meeos.tago.navi

/**
 * 내비 요청 연타 방어(V-05, 2026-11-13): 순정 내비는 경로 탐색 중 겹친 요청을 '경로 탐색 오류'로 띄우므로, 마지막 요청 뒤 minGapMs 안의 요청은 무시한다.
 * 요청이 실패(내비 없음·예외)했어도 시도로 센다 — 안 되는 걸 연타해도 안내를 되풀이하지 않는다. 순수 클래스(RequestGateTest).
 */
class RequestGate(private val minGapMs: Long = MIN_GAP_MS) {
    private var lastAt = Long.MIN_VALUE

    /** 지금 요청해도 되면 참(그리고 시각을 기록). now = elapsedRealtime(ms) */
    fun tryEnter(now: Long): Boolean {
        if (lastAt != Long.MIN_VALUE && now - lastAt < minGapMs) return false
        lastAt = now
        return true
    }

    companion object { const val MIN_GAP_MS = 2_000L }
}

/** 내비 요청 결과: 보냄 · 못 보냄(내비 없음·준비 안 됨) · 연타로 무시 */
enum class NaviResult { SENT, FAILED, IGNORED }
