package ai.meeos.tago.parking

import kotlin.math.PI
import kotlin.math.atan

/**
 * 시연 훅 `parking_sim` 의 대본(adb 전용 수신기로만 들어온다): 에뮬레이터에는 지하 주차장이 없어, 합성 궤적을 1초 표본으로 ParkingTracker 에 먹인다.
 * 형식: `spiral:N`(입구 → 나선 경사로 N층 → 기어 P, N 이 소수면 한 번에 N 바퀴) · `straight`(직선 경사로) · `flat`(평면 주차장: 모퉁이만) · `off`(지우고 실제 신호로 돌아감).
 * 시간은 합성이라 즉시 끝난다. 실제 기록 경로(차속·조향각·기어)와 같은 ParkingTracker.tick 을 쓴다.
 */
object ParkingSim {
    const val LAT = 37.4012
    const val LON = 127.1081
    data class Step(val sec: Int, val speed: Double, val steer: Double, val fix: Boolean, val parked: Boolean = false)

    private fun wheelFor(r: Double) = atan(ParkingConst.WHEELBASE_M / r) * 180 / PI * ParkingConst.STEER_RATIO

    fun steps(spec: String): List<Step>? {
        val s = spec.trim().lowercase()
        val out = mutableListOf(Step(20, 8.0, 0.0, true), Step(10, 4.0, 0.0, false))     // 달리다 입구로 → GPS 끊김
        when {
            s.startsWith("spiral:") -> {
                val v = s.substringAfter(':').toDoubleOrNull()?.takeIf { it > 0 && it <= ParkingConst.MAX_FLOORS } ?: return null
                val n = v.toInt()
                if (v == n.toDouble()) repeat(n) { out += Step(15, 3.0, wheelFor(7.0), false); out += Step(5, 3.0, 0.0, false) }   // 반지름 7 m·시속 11 km 한 바퀴 + 직진
                else { out += Step((14.66 * v).toInt(), 3.0, wheelFor(7.0), false); out += Step(5, 3.0, 0.0, false) }          // 소수: 한 번에 v 바퀴(범위 표시 시험)
            }
            s == "straight" -> out += Step(40, 3.0, 5.0, false)
            s == "flat" -> repeat(4) { out += Step(4, 3.0, 150.0, false); out += Step(5, 3.0, 0.0, false) }
            else -> return null
        }
        out += Step(10, 3.0, 0.0, false); out += Step(2, 0.0, 0.0, false, parked = true)
        return out
    }
}
