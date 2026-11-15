package ai.meeos.tago.parking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 주차 위치 기억(새 기능 H, 2026-11-13) — 차 쪽 층 추정(방법 A, 경사로 회전 세기).
 *
 * 순수 로직(안드로이드 없음)이라 JVM 단위 시험이 규칙을 고정한다(ParkingTrackerTest). 입력은 1초마다 한 번:
 * GPS 한 점(없을 수 있음)·차속·조향각·기어. 출력은 주차 기록 한 건(기어 P 일 때).
 *
 * 규칙
 *  1. **입구**: 좋은 GPS(정확도 ≤ 30 m, 5초 이내)를 보다가, 저속(≤ 9 m/s)에서 8초 넘게 GPS 가 끊기거나 정확도가 60 m 밖이면 '건물 안'.
 *     입구 좌표 = 마지막 좋은 GPS 한 점. 고속 터널은 속도 조건에서 걸러진다. 좋은 GPS 가 10초 이어지면 '건물 밖'(기록 폐기).
 *  2. **회전 적분**: 조향각 → 바퀴 각 → 자전거 모델 yaw rate = v·tan(δ)/L 을 적분한다(L 3.0 m, 조향비 14). 후진은 속도 부호가 반대.
 *  3. **경사로 한 번 = 한 번의 연속 회전(run)**: 같은 쪽으로 조향각 60° 넘게 돌려 있는 동안. 직진(조향각 45° 아래)이 3초 이어지면 끝난다.
 *     270° 아래의 run(모퉁이·유턴·주차 칸 진입)은 경사로로 세지 않는다. 360° 마다 한 층(round).
 *  4. **오르내림**: 첫 경사로의 회전 방향 = '내려가는 쪽'. 같은 방향으로 더 돌면 더 내려가고, 반대로 돌면 올라온다.
 *     입구는 지상으로 보고 첫 경사로를 아래로 가정한다(지하 주차장). 건물 정보(방법 C)가 '지하 없음'이면 위로 가정한다.
 *  5. **정직하게 모름**: 경사로 run 이 하나도 없으면(직선 경사로·평면 주차장) 층은 null('층 모름'). 순 층 변화가 0 이어도 모름.
 *     run 의 회전량이 360° 의 정수배에서 20% 넘게 벗어나면 신뢰도 LOW(주차장마다 한 층의 회전량이 다르다 — 실차 보정 필요).
 *     차에 기압·피치 신호가 있는지는 확인하지 못했다(미확인) — 그래서 신뢰도는 MEDIUM 이 최대다.
 */
object ParkingConst {
    const val WHEELBASE_M = 3.0          // 아이오닉 5 휠베이스 약 3.0 m
    const val STEER_RATIO = 14.0         // 조향 휠 각 ÷ 바퀴 각(대략값 — 실차 보정 필요)
    const val DEG_PER_FLOOR = 360.0
    const val MIN_RUN_DEG = 270.0
    const val TURN_STEER_DEG = 60.0
    const val STRAIGHT_STEER_DEG = 45.0
    const val STRAIGHT_HOLD_S = 3.0
    const val MIN_MOVE_MS = 0.3          // 이보다 느리면 회전을 적분하지 않는다
    const val GOOD_ACC_M = 30.0
    const val BAD_ACC_M = 60.0
    const val FIX_FRESH_S = 5.0
    const val LOSS_S = 8.0
    const val ENTRY_MAX_SPEED_MS = 9.0
    const val GOOD_LAST_MAX_S = 120.0    // 입구로 삼으려면 마지막 좋은 GPS 가 이 안이어야 한다
    const val EXIT_GOOD_S = 10.0
    const val MAX_FLOORS = 12
    const val MAX_FRAC = 0.20
    const val MAX_STEP_S = 3.0           // 틱 간격이 이보다 크면(앱이 멈췄다 돌아옴) 그 구간은 적분하지 않는다
    // 경사로 무늬(11-13 개발판 모의실험 반영): 경사로 = 느리고(평균 ≤ 5 m/s) 반지름이 작고(≤ 15 m) 조향이 일정한(변동 계수 ≤ 0.35) 긴 회전.
    // 층 안에서 크게 도는 회전·조향이 들쭉날쭉한 회전은 층으로 세지 않고 '애매함'으로 범위에만 반영한다
    const val RAMP_MAX_RADIUS_M = 15.0
    const val RAMP_MAX_SPEED_MS = 5.0
    const val RAMP_MAX_STEER_CV = 0.35
    // 11-13 채점 2바퀴(ParkingEvalTest): 경사로로 센 회전들의 반지름 비가 이보다 크면 '층 안 자리 찾기 원'이 섞였을 수 있다 → 한 층 덜 센 쪽까지 범위.
    // 같은 주차장의 나선 경사로는 반지름이 비슷하다는 가정(합성 대본 기준, 실제 주차장 분포는 미확인)
    const val RAMP_RADIUS_SPREAD = 1.4
    // 11-13 채점 3바퀴: 경사로 사이에 낀 이 각도 이상의 짧은 회전(직각 등, MIN_RUN_DEG 미만)은 '층 안 주행'의 흔적 — 그 뒤 경사로 무늬 회전은 자리 찾기 원일 수 있다
    const val AISLE_TURN_DEG = 60.0
    // 입구 보조 신호: 하향등이 꺼짐 → 켜짐(자동 전조등이 어두운 곳에서 켠다)이고 GPS 가 좋지 않은 채 4 s 면 8 s 를 기다리지 않는다
    const val BEAM_WINDOW_S = 15.0
    const val LOSS_WITH_BEAM_S = 4.0
}

/** 출차 때 거꾸로 센 값으로 본 그 기록의 품질(다음 단계 자료): parkedLevel = 기록했던 추정 층, residual = 건물을 나올 때 남은 순 층 수(0 이면 들어간 만큼 나왔다) */
data class ExitCheck(val parkedLevel: Int?, val residual: Int)

/** GPS 한 점. ageS = 그 고정이 만들어진 지 몇 초(낡았으면 끊긴 것으로 본다) */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Double?, val ageS: Double, val bearing: Double? = null)   // bearing: 움직이는 중의 진행 방위(도, 없으면 null)

enum class Confidence { LOW, MEDIUM }

/** 건물 정보(방법 C): 지하·지상 층 수. null = 모름 */
data class FloorRange(val basement: Int?, val above: Int?)

/** 방법 C 틀: 위치로 건물 층 범위를 준다. 키·자료가 없으면 null 을 돌려주고 추정은 그냥 진행한다 */
fun interface FloorRangeProvider { fun at(lat: Double, lon: Double): FloorRange? }

object NoFloorRange : FloorRangeProvider { override fun at(lat: Double, lon: Double): FloorRange? = null }

/**
 * 층 추정 결과. floor: 지하는 음수(B3 = −3), 지상 2층 이상은 양수(2층 = 2), 모름 = null.
 */
data class FloorEstimate(val floor: Int?, val confidence: Confidence, val rampRuns: Int, val netTurns: Int, val alt: Int? = null,
                         val bldgConflict: Boolean = false) {   // bldgConflict: 추정(또는 범위의 끝)이 건축물대장 층수 밖 — 자르지 않고 대장 끝 층을 범위에 함께, 사진 확인 권함
    companion object { val UNKNOWN = FloorEstimate(null, Confidence.LOW, 0, 0) }
}

/** 층 표기: −3 → "B3", 2 → "2F"(ko 는 "2층"). null → null */
fun floorLabel(floor: Int?, ko: Boolean = true): String? = when {
    floor == null -> null
    floor < 0 -> "B${-floor}"
    floor == 0 -> null
    else -> if (ko) "${floor}층" else "${floor}F"
}

/** [한 층 위/아래] 한 칸: B1 ↔ 1층 사이에 0 층은 없다. 모름(null)에서는 위 = 1층, 아래 = B1. 한계는 ±MAX_FLOORS */
fun stepFloor(floor: Int?, up: Boolean): Int {
    val f = when {
        floor == null -> if (up) 1 else -1
        up -> if (floor == -1) 1 else floor + 1
        else -> if (floor == 1) -1 else floor - 1
    }
    return f.coerceIn(-ParkingConst.MAX_FLOORS, ParkingConst.MAX_FLOORS)
}

/** 주차 기록 한 건. 사진 원본은 어디에도 없다(OCR 글자만). source: est(차 추정)·ocr(사진 글자)·user(운전자가 고침) */
data class ParkRecord(
    val atMs: Long,
    val entrance: Pair<Double, Double>?,
    val floor: Int?,
    val confidence: Confidence,
    val pillar: String? = null,        // "C-12" 같은 기둥·구역 표기
    val floorSource: String = "est",
    val estFloor: Int? = null,         // 사진·고침으로 바뀌기 전의 차 추정(둘이 다를 때 보여 주려고)
    val alt: Int? = null,              // 신뢰도가 낮을 때 함께 보여 줄 다른 층(범위 "B2~B3") — 틀린 층을 단정하지 않으려고
    val rampRuns: Int = 0,             // 화면 근거 줄: 추정에 쓴 경사로 수(판정에는 쓰지 않는다)
    val bldgBasement: Int? = null,     // 화면 근거 줄: 건축물대장 지하 층수(방법 C, 모르면 null)
    val bldgConflict: Boolean = false, // 화면 근거 줄: 추정이 건축물대장과 다름(자르지 않고 범위로 보인다 · 사진으로 확인)
    val heightUsed: Boolean = false,   // 화면 근거 줄: 차 관성 센서(높이)를 썼는가 — 아니면 '회전으로만 추정'
)

class ParkingTracker(private val ranges: FloorRangeProvider = NoFloorRange) {
    enum class Phase { OUTSIDE, INSIDE, PARKED }

    var phase = Phase.OUTSIDE
        private set
    var entrance: Pair<Double, Double>? = null
        private set
    var record: ParkRecord? = null
        private set

    private var lastGoodAt = Long.MIN_VALUE
    private var lastGood: GpsFix? = null
    private var lossS = 0.0
    private var goodS = 0.0
    private var lastTickMs = Long.MIN_VALUE
    private var movedS = 0.0

    // 회전 적분
    private var run = 0.0                  // 지금 run 의 각(도, 부호 있음)
    private var straightS = 0.0
    private var firstSign = 0
    private var floors = 0                 // 첫 경사로 방향이 +(아래) 기준의 순 층 수
    private var rampRuns = 0
    private var maxFrac = 0.0
    private var altAdj = 0                 // 범위의 다른 끝: floors 에 더할 값(0 = 없음)
    private var ambiguousRuns = 0
    private var hint: FloorRange? = null
    private val rampRadii = mutableListOf<Double>()   // 경사로로 센 run 의 반지름(m) — 큰 원이 섞였는지 본다
    private var aisleTurns = 0        // 마지막 경사로 뒤 층 안 회전(AISLE_TURN_DEG ~ MIN_RUN_DEG) 수
    private var afterAisle = 0        // 층 안 회전 뒤에 나온 경사로 무늬 회전 수(첫 경사로 제외) — 자리 찾기 원일 수 있다
    private var suspDir = 0           // 의심스러운 회전이 층 수에 더한 방향(+1 = 한 층 더 내려감으로 셈, −1 = 한 층 올라감으로 셈)
    private val rampDirs = mutableListOf<Int>()   // rampRadii 와 짝: 그 run 이 층 수에 더한 방향(dir × n 의 부호)
    // run 통계(경사로 무늬)
    private var runDist = 0.0
    private var runSteer = 0.0
    private var runSteerSq = 0.0
    private var runN = 0
    private var runSpeed = 0.0
    // 입구 보조(하향등)
    private var beamOn: Boolean? = null
    private var beamOnAt = Long.MIN_VALUE
    private var poorS = 0.0
    // 출차 역산
    private var parkedLevel: Int? = null
    private var parkedFloors: Int? = null
    var lastExit: ExitCheck? = null
        private set
    // 차 관성 센서(있고 살아 있을 때만 — 호출하는 쪽이 판정해 넘긴다). 없으면 아래 경로는 전혀 쓰이지 않는다(회귀 0)
    val incline = InclineTracker()
    var imuUsed = false
        private set
    private var prevSpeed: Double? = null

    /** 1초마다. parked = 기어를 알고 P. reversing = 기어 R. steerDeg = 조향 휠 각(도, 없으면 null) */
    fun tick(nowMs: Long, fix: GpsFix?, speedMs: Double, steerDeg: Double?, parked: Boolean, reversing: Boolean, lowBeamOn: Boolean? = null, imu: ImuSample? = null): ParkRecord? {
        val dt = if (lastTickMs == Long.MIN_VALUE) 0.0 else (nowMs - lastTickMs) / 1000.0
        lastTickMs = nowMs
        val dvdt = prevSpeed?.let { if (dt > 0) (speedMs - it) / dt else 0.0 } ?: 0.0
        prevSpeed = speedMs
        if (imu != null && phase == Phase.OUTSIDE) incline.update(dt, speedMs, dvdt, imu, inside = false)   // 건물 밖 평지 = 영점 학습
        val good = fix != null && fix.ageS <= ParkingConst.FIX_FRESH_S && (fix.accuracyM ?: 0.0) <= ParkingConst.GOOD_ACC_M
        val bad = fix == null || fix.ageS > ParkingConst.FIX_FRESH_S || (fix.accuracyM ?: 0.0) > ParkingConst.BAD_ACC_M
        if (good) { lastGoodAt = nowMs; lastGood = fix; lossS = 0.0; poorS = 0.0; goodS += dt } else { goodS = 0.0; poorS += dt; if (bad) lossS += dt }
        if (lowBeamOn == true && beamOn == false) beamOnAt = nowMs        // 꺼짐 → 켜짐을 본 순간만(처음부터 켜져 있던 것은 단서가 아니다)
        if (lowBeamOn != null) beamOn = lowBeamOn

        when (phase) {
            Phase.OUTSIDE -> {
                val last = lastGood
                val recentGood = last != null && (nowMs - lastGoodAt) / 1000.0 <= ParkingConst.GOOD_LAST_MAX_S + ParkingConst.LOSS_S + 2
                val slow = speedMs <= ParkingConst.ENTRY_MAX_SPEED_MS
                val byLoss = bad && lossS >= ParkingConst.LOSS_S
                val byBeam = beamOnAt != Long.MIN_VALUE && (nowMs - beamOnAt) / 1000.0 <= ParkingConst.BEAM_WINDOW_S && poorS >= ParkingConst.LOSS_WITH_BEAM_S
                if (last != null && recentGood && slow && (byLoss || byBeam)) enter(last)
            }
            Phase.INSIDE, Phase.PARKED -> {
                if (goodS >= ParkingConst.EXIT_GOOD_S) {
                    if (parkedFloors != null) lastExit = ExitCheck(parkedLevel, floors)   // 들어간 만큼 나왔으면 floors 가 0 으로 돌아온다
                    reset(); return null
                }
                if (phase == Phase.PARKED) {
                    movedS = if (!parked && speedMs > 1.0) movedS + dt else 0.0
                    if (movedS >= 5.0) { phase = Phase.INSIDE; record = null; movedS = 0.0 }   // 다시 5초 넘게 움직였다 — 칸을 옮겼거나 나가는 중: 이전 기록은 무효, 층 적분은 이어 간다(출차 역산)
                    return null
                }
                if (imu != null) { incline.update(dt, speedMs, dvdt, imu, inside = true); imuUsed = true }
                if (dt in 0.0..ParkingConst.MAX_STEP_S) integrate(dt, speedMs, steerDeg, reversing, incline.yawRate(imu))   // yaw 자이로는 정차 중에 맞춘 영점을 뺀 값
                if (parked) {
                    closeRun()
                    incline.finish()
                    val e = estimate()
                    val old = record
                    record = ParkRecord(nowMs, entrance, e.floor, e.confidence, pillar = old?.pillar, floorSource = "est", estFloor = e.floor, alt = e.alt,
                        rampRuns = e.rampRuns, bldgBasement = hint?.basement, bldgConflict = e.bldgConflict, heightUsed = imuUsed)
                    parkedLevel = e.floor; parkedFloors = floors
                    phase = Phase.PARKED
                    return record
                }
            }
        }
        return null
    }

    /** 방법 C 결과가 늦게(네트워크) 왔다: 범위를 바꾸고, 차 추정 그대로인 기록이면 다시 계산한다 */
    fun setRange(r: FloorRange?) {
        hint = r
        val rec = record ?: return
        if (rec.floorSource != "est") { record = rec.copy(bldgBasement = r?.basement); return }
        val e = estimate()
        record = rec.copy(floor = e.floor, confidence = e.confidence, estFloor = e.floor, alt = e.alt, rampRuns = e.rampRuns, bldgBasement = r?.basement, bldgConflict = e.bldgConflict)
    }

    private fun enter(last: GpsFix) {
        phase = Phase.INSIDE
        entrance = last.lat to last.lon
        hint = runCatching { ranges.at(last.lat, last.lon) }.getOrNull()
        run = 0.0; straightS = 0.0; firstSign = 0; floors = 0; rampRuns = 0; maxFrac = 0.0; altAdj = 0; ambiguousRuns = 0; clearRunStats(); rampRadii.clear(); rampDirs.clear(); aisleTurns = 0; afterAisle = 0; suspDir = 0
        record = null; parkedLevel = null; parkedFloors = null
        incline.startInside(); imuUsed = false
    }

    /** 앱이 다시 켜졌을 때 기기 안에 보관한 마지막 기록을 되살린다(층·기둥·근거만, 입구 좌표 없음). 주차 상태로 두어 5초 넘게 움직이면 무효 — 새 기록과 같은 규칙 */
    fun restore(r: ParkRecord) { reset(); record = r.copy(entrance = null); phase = Phase.PARKED; movedS = 0.0 }

    fun reset() {
        phase = Phase.OUTSIDE; entrance = null; record = null; hint = null
        run = 0.0; straightS = 0.0; firstSign = 0; floors = 0; rampRuns = 0; maxFrac = 0.0; lossS = 0.0; goodS = 0.0; poorS = 0.0
        altAdj = 0; ambiguousRuns = 0; parkedLevel = null; parkedFloors = null; beamOnAt = Long.MIN_VALUE; clearRunStats(); rampRadii.clear(); rampDirs.clear(); aisleTurns = 0; afterAisle = 0; suspDir = 0
        incline.startInside(); imuUsed = false
    }

    private fun clearRunStats() { runDist = 0.0; runSteer = 0.0; runSteerSq = 0.0; runN = 0; runSpeed = 0.0 }

    /** yawRad: 자이로 yaw 각속도(rad/s, 있으면 조향각 적분 대신). 조향각이 없을 때는 yaw 각속도로 회전·직진을 가른다(조향 휠 각으로 환산) */
    private fun integrate(dt: Double, speedMs: Double, steerDeg: Double?, reversing: Boolean, yawRad: Double? = null) {
        val steer = steerDeg ?: yawRad?.let { steerFromYaw(it, speedMs) } ?: 0.0
        val moving = speedMs >= ParkingConst.MIN_MOVE_MS
        if (abs(steer) < ParkingConst.STRAIGHT_STEER_DEG) {
            straightS += dt
            if (straightS >= ParkingConst.STRAIGHT_HOLD_S) closeRun()
        } else if (abs(steer) >= ParkingConst.TURN_STEER_DEG) straightS = 0.0
        if (!moving) return
        val v = if (reversing) -speedMs else speedMs
        val delta = steer / ParkingConst.STEER_RATIO * PI / 180.0                // 바퀴 각(rad)
        val yaw = yawRad ?: (v * tan(delta) / ParkingConst.WHEELBASE_M)         // rad/s — 자이로가 있으면 그 값
        run += yaw * dt * 180.0 / PI
        if (abs(steer) >= ParkingConst.STRAIGHT_STEER_DEG) {                   // 회전 중인 표본만 run 통계에
            runDist += speedMs * dt; runSteer += abs(steer); runSteerSq += steer * steer; runN++; runSpeed += speedMs
        }
    }

    /** 자이로 yaw 각속도 → 같은 회전을 만드는 조향 휠 각(자전거 모델 역산). 조향각 신호가 없을 때 직진·회전 판정과 무늬 통계용 */
    private fun steerFromYaw(yawRad: Double, speedMs: Double): Double {
        if (speedMs < ParkingConst.MIN_MOVE_MS) return 0.0
        return kotlin.math.atan(yawRad * ParkingConst.WHEELBASE_M / speedMs) * 180.0 / PI * ParkingConst.STEER_RATIO
    }

    /** 이 run 이 경사로 무늬인가: 반지름 작고 느리고 조향이 일정 */
    private fun rampLike(): Boolean {
        if (runN == 0) return false
        val angRad = abs(run) * PI / 180.0
        val radius = runDist / angRad
        val mean = runSteer / runN
        val varr = (runSteerSq / runN - mean * mean).coerceAtLeast(0.0)
        val cv = if (mean > 0) kotlin.math.sqrt(varr) / mean else 1.0
        return radius <= ParkingConst.RAMP_MAX_RADIUS_M && runSpeed / runN <= ParkingConst.RAMP_MAX_SPEED_MS && cv <= ParkingConst.RAMP_MAX_STEER_CV
    }

    private fun closeRun() {
        val a = abs(run)
        if (a >= ParkingConst.MIN_RUN_DEG) {
            val sign = if (run > 0) 1 else -1
            if (rampLike()) {
                val x = a / ParkingConst.DEG_PER_FLOOR
                val n = x.roundToInt().coerceAtLeast(1)
                val frac = abs(x - n)
                if (firstSign == 0) firstSign = sign
                val dir = if (sign == firstSign) 1 else -1
                if (frac > maxFrac) {
                    maxFrac = frac
                    if (frac > ParkingConst.MAX_FRAC) altAdj = dir * (if (x > n) 1 else -1)   // 1.4바퀴 → 1층, 다른 끝 2층
                }
                if (rampRuns > 0 && aisleTurns > 0) { afterAisle++; suspDir = dir }   // 경사로 사이에 층 안 회전이 끼었다(첫 경사로 앞 회전은 입구 진입이라 세지 않는다)
                aisleTurns = 0
                floors += dir * n
                rampRuns++
                if (runN > 0) { rampRadii += runDist / (a * PI / 180.0); rampDirs += dir }
            } else {
                ambiguousRuns++                                   // 경사로 무늬가 아닌 긴 회전: 층으로 세지 않고 범위에만
                if (altAdj == 0) altAdj = if (firstSign == 0 || sign == firstSign) 1 else -1
            }
        } else if (a >= ParkingConst.AISLE_TURN_DEG && rampRuns > 0) aisleTurns++   // 첫 경사로 뒤의 짧은 회전 = 층 안 주행
        run = 0.0; clearRunStats()
    }

    /** 진단 한 줄(시연·시험용 로그): 단계·지금 run 의 각·센 층·경사로 run 수 */
    fun debugLine(): String = "단계 $phase run ${"%.0f".format(run)}° 층 $floors 경사로 $rampRuns 애매 $ambiguousRuns" +
        (if (imuUsed) " · 관성 pitch ${"%.1f".format(incline.pitchDeg)}° Δh ${"%.1f".format(incline.dhM)} m(전 구간 ${"%.1f".format(incline.dhAllM)}) 경사 ${incline.downRamps}↓${incline.upRamps}↑ 자이로 영점 ${if (incline.biasS > 0) "%.2f°/s".format(Math.toDegrees(incline.gxBias)) else "없음"}" else "")

    /** 지금까지의 추정(경사로 run 이 하나도 없으면 UNKNOWN) */
    fun estimate(): FloorEstimate {
        val turn = turnEstimate()
        return if (imuUsed) combineWithHeight(turn) else turn
    }

    /**
     * 관성 센서가 있을 때: 높이로 센 층(Δh ÷ 층고, 방향은 절대 — 내려가면 지하)을 기준으로, 회전으로 센 층과 다르면 범위로 보인다.
     * 높이 변화가 없는데 회전이 층을 셌으면(평면 한 바퀴 오판) 회전 값을 낮은 신뢰도로만 둔다
     */
    private fun combineWithHeight(turn: FloorEstimate): FloorEstimate {
        val hF = incline.floorsByHeight()
        if (hF == 0) return if (turn.floor != null) turn.copy(confidence = Confidence.LOW, alt = null) else turn
        if (abs(hF) > ParkingConst.MAX_FLOORS) return turn
        var level = if (hF > 0) -hF else 1 - hF                      // 아래 d 층 = B d, 위 d 층 = (d+1) 층
        var alt: Int? = when {
            turn.floor != null && turn.floor != level -> turn.floor
            incline.heightFrac() > InclineConst.RANGE_FRAC -> { val x = incline.floorsX(); val other = if (x > hF) hF + 1 else hF - 1; if (other == 0) null else if (other > 0) -other else 1 - other }
            else -> null
        }
        var conf = if (alt == null) Confidence.MEDIUM else Confidence.LOW
        var conflict = false
        hint?.let { h ->
            fun inRange(l: Int) = !((l < 0 && h.basement != null && -l > h.basement) || (l > 0 && h.above != null && l > h.above))
            if (!inRange(level)) {   // 추정이 대장보다 깊다(높다): 자르지 않는다 — 범위(대장 끝 층 ~ 추정)로 보이고 사진 확인을 권한다. 대장은 범위 안 대표 후보를 고르는 데만
                val est = level; level = bldgEdge(level, h); alt = est; conf = Confidence.LOW; conflict = true
            } else {
                val al = alt   // 범위 끝이 대장에 없는 층이어도 지우지 않고 '대장과 다름'(채점 3바퀴)
                if (al != null && !inRange(al)) conflict = true
            }
        }
        if (alt == level || alt == 0) alt = null
        return FloorEstimate(level, conf, turn.rampRuns, turn.netTurns, alt, bldgConflict = conflict)
    }

    private fun turnEstimate(): FloorEstimate {
        if (rampRuns == 0 || floors == 0 || abs(floors) > ParkingConst.MAX_FLOORS) return FloorEstimate.UNKNOWN.copy(rampRuns = rampRuns, netTurns = floors)
        var level = levelOf(floors)
        // 경사로로 센 회전들의 반지름이 크게 다르면 큰 원(층 안 자리 찾기)이 한 층으로 세어졌을 수 있다 → 한 층 덜 센 쪽까지 범위(채점 2바퀴)
        // + 층 안 회전 뒤에 나온 경사로 무늬 회전이 있으면 같은 처리(채점 3바퀴 — 반지름이 비슷한 자리 찾기 원)
        val spread = rampRadii.size >= 2 && (rampRadii.maxOrNull() ?: 0.0) > ParkingConst.RAMP_RADIUS_SPREAD * (rampRadii.minOrNull() ?: 0.0)
        val mixed = spread || afterAisle > 0
        // 범위의 다른 끝 = 의심스러운 회전 하나를 되돌린 층(그 회전이 더했든 뺐든 — 반대 방향 자리 찾기 원은 한 층 얕게 셌다, 채점 3바퀴)
        val susp = when { afterAisle > 0 -> suspDir; spread -> rampDirs.getOrNull(rampRadii.indexOf(rampRadii.max())) ?: 1; else -> 0 }
        val adj = if (mixed && susp != 0) -susp else altAdj   // 의심스러운 회전이 있으면 그것을 되돌리는 쪽이 먼저(그 회전의 끝수로 정한 altAdj 는 방향이 엉뚱할 수 있다 — 채점 3바퀴 진단)
        var alt: Int? = if (adj != 0) levelOf(floors + adj) else null
        var conf = if (maxFrac <= ParkingConst.MAX_FRAC && ambiguousRuns == 0 && !mixed) Confidence.MEDIUM else Confidence.LOW
        var conflict = false
        hint?.let { h ->
            fun inRange(l: Int) = !((l < 0 && h.basement != null && -l > h.basement) || (l > 0 && h.above != null && l > h.above))
            if (!inRange(level)) {   // 추정이 대장보다 깊다(높다): 자르지 않는다(채점 2바퀴: 잘라 내면 대장이 얕을 때 100 % 자신 있게 틀렸다; 주차 논문 5.6: 표기가 하나 큰 건물에서 44.1 → 36.2 %)
                val est = level; level = bldgEdge(level, h); alt = est; conf = Confidence.LOW; conflict = true   // 범위(대장 끝 층 ~ 추정), 대표 후보는 대장 끝 층
            } else {
                val al = alt   // 범위의 다른 끝이 건물에 없는 층이어도 지우지 않는다 — 건물 정보도 틀릴 수 있다(채점 3바퀴: 지우면 대장이 얕을 때 12~20 % 자신 있게 틀렸다)
                if (al != null && !inRange(al)) conflict = true
            }
        }
        if (level == 0) return FloorEstimate.UNKNOWN.copy(rampRuns = rampRuns, netTurns = floors)
        if (alt == level || alt == 0) alt = null
        if (alt != null) conf = Confidence.LOW
        return FloorEstimate(level, conf, rampRuns, floors, alt, bldgConflict = conflict)
    }

    /**
     * 건축물대장(방법 C)은 약한 단서다(11-14, 주차 논문 7절 2): 추정을 대장 층수로 자르지 않는다(추정 층을 화면에서 지우지 않는다). 추정이 대장보다
     * 깊으면(지상이면 높으면) 범위 '대장 끝 층 ~ 추정'으로 보이고 '대장과 다름 · 사진으로 확인'을 권한다 — 현장 표기가 물리 층과 어긋난 건물·대장 기재 오류가 있다.
     * 범위 안에서 기록의 대표 층(`floor`)은 대장 끝 층이다: 높이 ÷ 3.0 m 가 깊게 세는 쪽으로 치우쳐 있어, 대표 층을 추정으로 두면 개발 시드에서
     * 대표 층 일치가 45.9 → 38.3 % 로 떨어졌다(11-14 주차 논문 세션 재측정 — 화면에 보이는 범위는 같고 대표 층만 다른 비교). 화면·음성은 범위를 그대로 말한다.
     * 그 밖의 쓰임: 지하가 없는 건물이면 첫 경사로를 위로 본다(levelOf), 화면 근거 줄에 지하 층수를 보인다
     */
    private fun bldgEdge(level: Int, h: FloorRange): Int =
        if (level < 0) h.basement?.let { if (it >= 1) -it else 1 } ?: level   // 지하가 없다고 적힌 건물에서 내려갔으면 다른 끝은 입구 층(1층)
        else h.above?.let { if (it >= 1) it else 1 } ?: level

    /** 순 층 수 → 층 번호(지하 음수, 지상 2층 이상 양수, 0 = 입구 층). 건물 정보가 '지하 없음'이면 첫 경사로는 올라간다 */
    private fun levelOf(f: Int): Int {
        if (f == 0) return 0
        val upBuilding = hint?.let { it.basement == 0 && (it.above ?: 0) > 0 } == true
        return if (upBuilding) f + 1 else if (f > 0) -f else -f + 1
    }

    /** 운전자가 층을 고쳤다(±1) 또는 사진 글자가 층을 줬다. 기록이 없으면 만들지 않는다 */
    fun setFloor(floor: Int?, source: String, nowMs: Long): ParkRecord? {
        val r = record ?: return null
        record = r.copy(floor = floor, floorSource = source, confidence = if (source == "est") r.confidence else Confidence.MEDIUM, atMs = nowMs)
        return record
    }

    fun setPillar(pillar: String?): ParkRecord? {
        val r = record ?: return null
        record = r.copy(pillar = pillar)
        return record
    }

    fun clear() { reset() }
}
