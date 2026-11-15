package ai.meeos.tago.road

/**
 * 허브 보기의 신선도(V-03, 2026-11-13). 마지막 성공 응답(또는 시작) 뒤 STALE_MS 가 넘으면 '연결 끊김' — 화면은 낡은 안내를 현재처럼 그리지 않고
 * 접근 경고음·[우회 안내]는 멈춘다. 다시 응답이 오면(ok) 바로 풀린다. 시계는 elapsedRealtime(ms)을 넘긴다(순수 클래스 — LinkFreshnessTest).
 */
class LinkFreshness(private val staleMs: Long = STALE_MS) {
    private var lastOkAt = 0L

    /** 응답을 받았거나 연결을 막 시작했다 */
    fun ok(now: Long) { lastOkAt = now }

    /** 끊김 판정을 끈다(연결 종료) */
    fun reset() { lastOkAt = 0L }

    /** 시작된 적이 있고 마지막 성공 뒤 staleMs 를 넘겼으면 참 */
    fun stale(now: Long): Boolean = lastOkAt != 0L && now - lastOkAt > staleMs

    /** 마지막 성공 뒤 경과 초(시작 전이면 0) */
    fun ageSec(now: Long): Int = if (lastOkAt == 0L) 0 else ((now - lastOkAt) / 1000).toInt()

    companion object { const val STALE_MS = 10_000L }
}
