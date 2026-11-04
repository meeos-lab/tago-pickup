package ai.meeos.tago.road

import kotlin.math.abs

/**
 * 사고 위험성(설계 10판 §6-2)의 운전 묶음 — 이 차 안에서만 계산하고 링크로 보내지 않는다(허브는 환경·도로 묶음만).
 * - SPEEDING: 제한속도(시연 경로 100 km/h) +20 km/h 넘게 20초 이어짐
 * - BRAKES: 최근 2분 급제동 2번 이상(차간거리 부족 — 2차 사고 원인 1위 전방주시 태만과 같은 꼴)
 * - FATIGUE: 도로 링크 2시간 이상 연속, 또는 핸들 보정이 자기 기준선(시속 60↑ 처음 3분)의 2배 이상(최근 1분, 1초 평균 1.5° 이상)
 *   — 졸음 운전의 보편 문턱이 없어 기준선 대비로만 본다. 말은 "휴식을 권장합니다"
 * 각 +2. 1초마다 [tick] 으로 속도·핸들 각을 넣는다.
 */
class DriveRisk {
    private var startAt = 0L
    private var overSince = 0L
    private val brakes = ArrayDeque<Long>()
    private var prevSteer: Float? = null
    private val baseline = ArrayList<Float>()           // 시속 60↑ 처음 180초의 1초 핸들 변화량
    private val recent = ArrayDeque<Float>()            // 최근 60초
    /** 시연 훅 risk_sim: 고정 태그 */
    var sim: Set<String> = emptySet()

    /** sim 은 지우지 않는다 — 시연 훅이 road_start 와 같은 인텐트로 오는데 join 응답에서 reset 이 불려 지워졌다(끄기는 risk_sim off) */
    fun reset() { startAt = 0L; overSince = 0L; brakes.clear(); prevSteer = null; baseline.clear(); recent.clear() }

    fun brake(now: Long) { brakes.addLast(now) }

    fun tick(now: Long, speedMs: Float, steer: Float?) {
        if (startAt == 0L) startAt = now
        if (speedMs * 3.6f > LIMIT_KMH + OVER_KMH) { if (overSince == 0L) overSince = now } else overSince = 0L
        while (brakes.isNotEmpty() && now - brakes.first() > BRAKE_WINDOW_MS) brakes.removeFirst()
        val p = prevSteer
        prevSteer = steer
        if (steer == null || p == null || speedMs * 3.6f < STEER_MIN_KMH) return
        val d = abs(steer - p)
        if (d >= SWERVE_DEG) return                      // 급조향(회피)은 피로 신호가 아니다
        if (baseline.size < BASELINE_N) baseline += d
        else { recent.addLast(d); while (recent.size > RECENT_N) recent.removeFirst() }
    }

    fun tags(now: Long): List<String> {
        if (sim.isNotEmpty()) return TAGS.filter { it in sim }
        val out = mutableListOf<String>()
        if (overSince != 0L && now - overSince >= OVER_HOLD_MS) out += "SPEEDING"
        if (brakes.count { now - it <= BRAKE_WINDOW_MS } >= 2) out += "BRAKES"
        val long = startAt != 0L && now - startAt >= FATIGUE_MS
        val base = if (baseline.size >= BASELINE_N) baseline.average() else null
        val cur = if (recent.size >= RECENT_N) recent.average() else null
        val wobble = base != null && cur != null && cur >= STEER_MIN_DEG && cur >= base * 2
        if (long || wobble) out += "FATIGUE"
        return out
    }

    companion object {
        val TAGS = listOf("SPEEDING", "BRAKES", "FATIGUE")
        const val PTS = 2
        const val LIMIT_KMH = 100f; const val OVER_KMH = 20f; const val OVER_HOLD_MS = 20_000L
        const val BRAKE_WINDOW_MS = 120_000L
        const val FATIGUE_MS = 2 * 3600_000L
        const val STEER_MIN_KMH = 60f; const val SWERVE_DEG = 60f; const val STEER_MIN_DEG = 1.5
        const val BASELINE_N = 180; const val RECENT_N = 60
    }
}
