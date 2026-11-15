package ai.meeos.tago.engine

/**
 * 시연용 시계(빠르게 감기): 배율(rate)만큼 빨리 가는 시각. 뒤로 가지 않는다.
 * 허브는 요청의 t 로 시계를 옮기므로(허브 clock), 앱이 이 시각을 보내면 허브의 시연 차·각본 시계가 같은 배율로 간다 — 내 차만 빨라져 남을 앞지르지 않는다.
 * 배율 1 이면 실제 시각 + 그동안 앞당겨진 만큼. 배율은 [1, MAX_RATE] — 요청 간격 1 s 에서 한 번에 MAX_RATE−1 s 씩만 어긋나 허브의 시계 오차 가드(30 s)에 걸리지 않는다.
 */
class WarpClock(private val real: () -> Double) {
    private var last = Double.NaN
    private var ahead = 0.0
    @Volatile var rate = 1.0
        private set

    /** 지금 시각(초) */
    @Synchronized fun now(): Double {
        val r = real()
        if (!last.isNaN() && r > last) ahead += (r - last) * ((if (paused) 0.0 else rate) - 1.0)
        last = r
        return r + ahead
    }

    /** 배율을 바꾼다 — 지금까지는 옛 배율로 셈한다 */
    @Synchronized fun rate(v: Double) { now(); rate = if (v.isFinite()) v.coerceIn(1.0, MAX_RATE) else 1.0 }

    /** 멈춤(시연 주행 재생 막대): 켠 동안 시각이 서 있다. 배율은 그대로 남아, 풀면 그 배율로 다시 간다 */
    @Volatile var paused = false
        private set
    @Synchronized fun pause(on: Boolean) { now(); paused = on }

    companion object { const val MAX_RATE = 8.0 }
}
