package ai.meeos.tago.app

/**
 * 서비스 초기화 재시도(V-04, 2026-11-13). 시동 직후 차량·내비·음성 서비스가 늦게 뜨면 첫 시도가 실패하는데, 전에는 한 번뿐이라 그 주행 내내 못 썼다.
 * attempt 가 서비스를 붙이려 하고, 결과를 succeeded()/failed() 로 알린다(비동기 콜백에서도 부를 수 있다). 실패하면 delayMs 뒤 다시 시도한다(성공할 때까지).
 * 시계·예약은 밖에서 넣는다(앱: Handler.postDelayed, 시험: 가짜) — InitRetryTest.
 */
class InitRetry(
    private val delayMs: Long = DELAY_MS,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val attempt: (InitRetry) -> Unit,
) {
    @Volatile var ready = false
        private set
    var attempts = 0
        private set
    @Volatile private var cancelled = false

    fun start() = launch()

    fun succeeded() { ready = true }

    /** 이번 시도가 실패했다 — 다시 예약한다(이미 성공했거나 취소됐으면 무시) */
    fun failed() {
        if (ready || cancelled) return
        schedule(delayMs) { launch() }
    }

    /** 앱을 닫을 때: 남은 재시도를 멈춘다 */
    fun cancel() { cancelled = true }

    private fun launch() {
        if (ready || cancelled) return
        attempts++
        try { attempt(this) } catch (e: Throwable) { failed() }
    }

    companion object { const val DELAY_MS = 5_000L }
}
