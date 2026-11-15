package ai.meeos.tago.parking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

/**
 * 차 관성 센서(가속도·자이로)가 있을 때의 경사로 판정(새 기능 H, 2026-11-13 사용자 승인) — 순수 계산(단위 시험 InclineTrackerTest).
 *
 * 차 관성 센서는 Pleos Vehicle SDK 2.3.2 목록에 없다. 실차 헤드유닛에서 안드로이드 센서(SensorManager)로 읽히는지는 **미확인**이다 —
 * 있으면 쓰고, 없거나 값이 죽어 있으면 이 클래스는 쓰이지 않는다(기존 회전 세기 그대로). 에뮬레이터에서는 가상 센서 값 주입으로만 시험했다.
 *
 * 축 가정(실차 장착 방향 **미확인**): 자동차용 안드로이드 센서 축 = x 오른쪽, y 앞, z 위. 가속도계는 비력(specific force)을 잰다:
 *   f_y = 앞뒤 가속 + g·sin(pitch), f_z ≈ g·cos(pitch)  →  pitch_acc = atan2(f_y − dv/dt, f_z)   (pitch > 0 = 앞이 들림 = 오르막)
 * 자이로 x(옆 축 회전)로 보완하는 상보 필터: pitch = α·(pitch + ω_x·dt) + (1 − α)·pitch_acc.
 * 영점 두 가지:
 *  - 장착 기울기: 건물 밖 주행(차속 > 2 m/s, |dv/dt| < 0.3 m/s²)의 pitch 평균을 장착 기울기로 보고 뺀다. 11-14 바꿈(주차 논문 표 10 '건물 밖 도로가
 *    1° 기울어 있음'): 시상수를 배운 시간만큼 늘려(30 s → 최대 3시간) 여러 길의 오르내림이 평균으로 지워지게 하고, 값은 앱 전용 저장소에 남겨 다음
 *    주행이 이어 쓴다(`restoreMount`). 헤드유닛 장착 각은 바뀌지 않는 값이라서다. 처음 쓰는 차의 첫 주행은 처음 30 s 만 30 s 평균이고 그 뒤 배운 시간만큼
 *    느려져 그 길의 기울기를 예전보다 조금 덜 삼킨다(90 s 뒤 예전 86 % → 65 %). 상한 30분으로 잰 값(논문 세션, 지난 30분 도로 기울기 N(0, 1.5°) 가정):
 *    밖 도로 1° 에서 높이 오차 퍼짐 2.16 → 0.78 m, 평지에서는 0.65 → 0.78 m(남은 길 기울기 평균 약 0.4°) — 그래서 상한을 3시간으로 늘렸다(짐·승객으로
 *    장착 각이 조금 바뀌면 그만큼 늦게 따라간다). 3시간 배운 차: 세 조건(영점 맞춤·자이로 0.5 °/s·밖 도로 1°) 모두 퍼짐 0.46~0.47 m(가정에 기댄 값).
 *    정차 중에는 장착 기울기를 배우지 않는다(정차 중 pitch 를 가속도계 값으로 맞추는 것은 '가속도 − 장착 기울기'라 기운 길의 기울기를 영점으로 삼키지 않는다).
 *  - 자이로 영점(11-14, 주차 논문 표 10 — 영점을 맞추지 않으면 오류가 기준을 넘었다): 정차(차속 < 0.1 m/s, |dv/dt| < 0.05)가 3 s 이어지면
 *    그동안의 자이로 값(옆 축 gx·위 축 gz)은 참값이 0 이므로 그 평균을 영점으로 보고 뺀다(지수 평균 5 s). 정차 중에는 가속도계 pitch 가
 *    가감속에 흔들리지 않으므로 필터를 그 값으로 다시 맞춘다. 건물 밖 신호 대기·건물 입구 차단기에서 맞춰진다. 정차가 없으면 영점은 0 그대로(이전과 같다).
 * 경사로 구간: |pitch| ≥ 3° 가 2 s 이어지면 시작, |pitch| < 1° 가 2 s 이어지면 끝. 2 s 를 못 채우는 짧은 ± 쌍(과속방지턱)은 구간이 아니다.
 * 높이(11-14 바꿈): **경사 구간 안에서만** Δh = Σ v·sin(pitch)·dt 를 더한다(시작을 판정한 2 s 를 앞에 붙인다). 평지에 남는 작은 pitch 오차가
 *   긴 층 안 주행 내내 쌓이지 않게 — 주차 논문 5.6절: 높이 오차 퍼짐 전 구간 1.36 m, 경사 구간만 0.69 m. 전 구간 값(dhAllM)은 진단 로그에만 남긴다.
 *   층 수(11-14 바꿈, 주차 논문 5.6 '첫 주차 판까지의 깊이와 층고가 다른데 처음부터 3.0 m 로 나눈다'): x = 1 + (|Δh| − FIRST_DROP_M) ÷ FLOOR_H_M,
 *   층 = max(1, round(x))(내려가면 지하). x 가 정수에서 RANGE_FRAC 넘게 벗어나면 옆 층까지 범위. 3° 아래의 아주 완만한 경사로는 높이로 세지 못한다(알려진 한계).
 *   상수는 근거가 약하다: 첫 하강 높이는 공개 자료를 찾지 못했고(논문 부록 F), 4.5 m 는 모의실험 가정(첫 하강 3~6 m)의 한가운데, 3.3 m 는 그 생성기의 층고 어림값(가정 2.8~4.0 m, 산술 가운데 3.4 m 에 가까움)이다 — 실차 보정 필요.
 *   주차 논문 세션 재측정(이 상수 4.5·3.3·0.15, 개발 시드 0~9, 탐색, 대장 사용): 대표 층 일치 47.6 → 63.6 %, 범위 안 정답 67.7 → 78.9 %, 자신 있게 틀림 22.8 → 12.7 %(모든 경사로 종류에서 줄어듦),
 *   물리 층 일치도 크게 오름. 다만 세운 판의 표기가 물리 층보다 하나 큰 방문(22.4 %)에서는 일치 41.4 → 10.9 %, 자신 있게 틀림 22.1 → 42.2 %(표기 = 물리 층인 방문 70.3 % 는 51.7 → 86.3 %, 자신 있게 틀림 22.3 → 2.1 %) — 예전 ÷3.0 m 가 한 층 깊게 세던
 *   치우침이 표기 어긋남과 우연히 겹쳐 맞던 것이다. 차는 물리 깊이만 재므로 표기 어긋남은 사진(또는 주차장별 학습)으로만 바로잡는다.
 */
object InclineConst {
    const val ALPHA = 0.7                // 1 Hz 상보 필터 가중(자이로 쪽). 자이로가 없거나 치우쳐도 몇 초 안에 가속도 쪽으로 따라오게
    const val G = 9.80665
    const val RAMP_ON_DEG = 3.0
    const val RAMP_OFF_DEG = 1.0
    const val HOLD_S = 2.0
    const val FLOOR_H_M = 3.3            // 주차장 층고 대략값(모의실험 생성기의 어림값, 가정 2.8~4.0 m, 11-14 3.0 → 3.3) — 실차 보정 필요
    const val FIRST_DROP_M = 4.5         // 첫 경사로로 내려가는 높이(첫 하강, 지상 층·보 때문에 층고보다 깊다 — 모의실험 가정 3~6 m 의 가운데. 지하 1층이 상가면 첫 주차 판은 한 층 더 깊다) — 공개 근거 없음, 실차 보정 필요
    const val RANGE_FRAC = 0.15          // 높이로 센 층 수가 정수에서 이만큼 넘게 벗어나면 옆 층까지 범위(11-14 0.25 → 0.15: 같은 계산식에서 자신 있게 틀림이 줄었다)
    const val MIN_DH_M = 1.5             // 이보다 작은 높이 변화는 층 변화가 아니다
    const val CAL_TAU_S = 30.0           // 장착 기울기 학습의 처음 시상수
    const val CAL_MAX_S = 10800.0        // 시상수 상한 3시간(배운 시간만큼 늘어난다 — 30분만 배워도 1° 기운 길 90 s 가 영점을 약 0.05° 옮긴다)
    const val STILL_MS = 0.1             // 정차로 보는 차속(m/s)
    const val STILL_DVDT = 0.05          // 정차로 보는 차속 변화(m/s²)
    const val STILL_HOLD_S = 3.0         // 이만큼 정차가 이어진 뒤의 표본부터 자이로 영점에 쓴다(서는 순간의 흔들림을 뺀다)
    const val BIAS_TAU_S = 5.0           // 자이로 영점 지수 평균 시상수(첫 정차는 그 표본 하나로 바로 맞춘다)
}

/** 1초 동안의 관성 센서 평균. ay·az = 앞·위 가속도(m/s²), gx = 옆 축 각속도(rad/s, 앞이 들리는 쪽 +), gz = 위 축 각속도(rad/s, yaw) */
data class ImuSample(val ay: Double, val az: Double, val gx: Double?, val gz: Double?)

class InclineTracker {
    var pitchDeg = 0.0
        private set
    var offsetDeg = 0.0
        private set
    var calibratedS = 0.0
        private set
    /** 자이로 영점(rad/s): 정차 중에 맞춘 옆 축(gx)·위 축(gz) 값. 맞춘 적이 없으면 0 */
    var gxBias = 0.0
        private set
    var gzBias = 0.0
        private set
    /** 자이로 영점을 맞추는 데 쓴 정차 시간(s). 0 = 아직 못 맞춤 */
    var biasS = 0.0
        private set
    private var stillS = 0.0
    private var closedDh = 0.0
    /** 건물 안 높이 변화(m, 오르막 +): 닫힌 경사 구간들 + 지금 열린 경사 구간 */
    val dhM: Double get() = closedDh + (if (onRamp) rampDh else 0.0)
    /** 진단용: 건물 안 전 구간에서 더한 높이(예전 규칙). 층 계산에는 쓰지 않는다 */
    var dhAllM = 0.0
        private set
    var downRamps = 0
        private set
    var upRamps = 0
        private set
    var onRamp = false
        private set
    private var onS = 0.0
    private var offS = 0.0
    private var rampSign = 0
    private var rampDh = 0.0
    private var started = false

    /** 건물 안 집계를 새로 시작(입구). 영점(장착 기울기·자이로)은 그대로 둔다 */
    fun startInside() { closedDh = 0.0; dhAllM = 0.0; downRamps = 0; upRamps = 0; onRamp = false; onS = 0.0; offS = 0.0; rampSign = 0; rampDh = 0.0 }

    /** 지난 주행에서 배운 장착 기울기를 이어 쓴다(앱 전용 저장소). 값이 이상하면(±15° 밖·배운 시간 0 이하) 무시 */
    fun restoreMount(offset: Double, seconds: Double) {
        if (seconds <= 0 || seconds.isNaN() || offset.isNaN() || abs(offset) > 15.0) return
        offsetDeg = offset; calibratedS = seconds.coerceAtMost(InclineConst.CAL_MAX_S)
    }

    /** 자이로 위 축(yaw) 값에서 영점을 뺀 것. 자이로가 없으면 null — 회전 세기가 조향각 대신 쓸 때 */
    fun yawRate(s: ImuSample?): Double? = s?.gz?.let { it - gzBias }

    /**
     * 1초 표본 하나. inside = 건물 안(경사 구간 높이 적분), outside 이고 평지 주행이면 장착 기울기를 맞춘다. 정차 중이면 안팎 모두 자이로 영점을 맞춘다.
     * dvdt = 차속 변화(m/s²)
     */
    fun update(dt: Double, speedMs: Double, dvdt: Double, s: ImuSample, inside: Boolean) {
        if (dt <= 0 || dt > ParkingConst.MAX_STEP_S) return
        val raw = atan2(s.ay - dvdt, s.az) * 180.0 / PI
        val acc = raw - offsetDeg
        stillS = if (speedMs < InclineConst.STILL_MS && abs(dvdt) < InclineConst.STILL_DVDT) stillS + dt else 0.0
        val still = stillS >= InclineConst.STILL_HOLD_S
        if (still) {                                      // 서 있다: 자이로의 참값은 0 → 읽힌 값이 영점
            val k = if (biasS == 0.0) 1.0 else (dt / InclineConst.BIAS_TAU_S).coerceAtMost(1.0)
            s.gx?.let { gxBias += k * (it - gxBias) }
            s.gz?.let { gzBias += k * (it - gzBias) }
            biasS += dt
        }
        pitchDeg = if (!started || still) acc else {      // 정차 중에는 가속도계 pitch 가 깨끗하다 — 필터를 그 값으로 다시 맞춘다
            val gyro = s.gx?.let { pitchDeg + (it - gxBias) * 180.0 / PI * dt }
            if (gyro != null) InclineConst.ALPHA * gyro + (1 - InclineConst.ALPHA) * acc else acc
        }
        started = true
        if (!inside) {
            if (speedMs > 2.0 && abs(dvdt) < 0.3) {          // 평지 주행 표본으로 장착 기울기(영점) 학습
                val tau = calibratedS.coerceIn(InclineConst.CAL_TAU_S, InclineConst.CAL_MAX_S)
                val k = (dt / tau).coerceAtMost(1.0)
                offsetDeg += k * (raw - offsetDeg)
                calibratedS += dt
            }
            return
        }
        val p = pitchDeg
        val step = speedMs * sin(p * PI / 180.0) * dt
        dhAllM += step
        if (!onRamp) {
            if (abs(p) >= InclineConst.RAMP_ON_DEG) {
                onS += dt
                if (onS >= InclineConst.HOLD_S) { onRamp = true; offS = 0.0; rampSign = if (p < 0) -1 else 1; rampDh = speedMs * sin(p * PI / 180.0) * onS }
            } else onS = 0.0
        } else {
            rampDh += step
            if (abs(p) < InclineConst.RAMP_OFF_DEG) {
                offS += dt
                if (offS >= InclineConst.HOLD_S) closeRamp()
            } else offS = 0.0
        }
    }

    /** 경사 구간을 닫는다: 높이는 크기와 상관없이 더하고(반층 반 경사로도 쌓이게), 오르내림 수는 반 층 이상일 때만 센다 */
    private fun closeRamp() {
        if (abs(rampDh) >= InclineConst.MIN_DH_M / 2) { if (rampSign < 0) downRamps++ else upRamps++ }
        closedDh += rampDh
        onRamp = false; onS = 0.0; offS = 0.0; rampDh = 0.0
    }

    /** 주차(P) 때: 열린 구간을 닫는다 */
    fun finish() { if (onRamp) closeRamp() }

    /** 높이로 센 층 수의 연속값(내려가면 +): 첫 하강 FIRST_DROP_M, 그 뒤 FLOOR_H_M 마다 한 층. 높이 변화가 작으면 0 */
    fun floorsX(): Double {
        val d = -dhM
        if (abs(d) < InclineConst.MIN_DH_M) return 0.0
        val x = 1 + (abs(d) - InclineConst.FIRST_DROP_M) / InclineConst.FLOOR_H_M
        return if (d > 0) x else -x
    }

    /** 높이로 센 층 수(내려가면 +, 적어도 한 층). 높이 변화가 작으면 0 */
    fun floorsByHeight(): Int {
        val x = floorsX()
        if (x == 0.0) return 0
        val n = maxOf(1, Math.round(abs(x)).toInt())
        return if (x > 0) n else -n
    }

    /** 높이로 센 층 수가 정수에서 얼마나 벗어났나(0~0.5). 한 층보다 얕게 나와 1로 올린 경우는 1 과의 거리(0.5 까지) */
    fun heightFrac(): Double { val x = abs(floorsX()); return minOf(0.5, abs(x - maxOf(1, Math.round(x).toInt()))) }
}
