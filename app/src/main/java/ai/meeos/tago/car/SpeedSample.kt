package ai.meeos.tago.car

/**
 * 차속 표본 검증(V-02, 2026-11-13). CarPropertyValue.status 가 AVAILABLE(0)이 아니거나 값이 유한한 Float 이 아니면 버린다 —
 * 센서 오류 때 VHAL 이 주는 UNAVAILABLE·ERROR 표본의 0 이 '급정지'로 읽혀 가짜 충돌이 되는 것을 막는다.
 */
object SpeedSample {
    /** android.car.hardware.CarPropertyValue.STATUS_AVAILABLE (안드로이드 클래스를 시험에서 쓰지 않으려고 값을 둔다) */
    const val STATUS_AVAILABLE = 0

    /** 쓸 수 있는 표본이면 m/s 값, 아니면 null */
    fun accept(status: Int, value: Any?): Float? {
        if (status != STATUS_AVAILABLE) return null
        val v = value as? Float ?: return null
        return if (v.isNaN() || v.isInfinite()) null else v
    }
}
