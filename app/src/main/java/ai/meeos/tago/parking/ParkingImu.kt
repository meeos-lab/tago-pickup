package ai.meeos.tago.parking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 차 관성 센서(가속도·자이로) 런타임 탐지 — 있으면 쓰고, 없거나 값이 죽어 있으면 null 만 돌려준다(기존 회전 세기 그대로, 회귀 0).
 *
 * 차 관성 센서는 Pleos Vehicle SDK 2.3.2 목록에 없다. 실차 헤드유닛에서 안드로이드 SensorManager 로 읽히는지는 **미확인** — 에뮬레이터에서는
 * 가상 센서 값 주입(`adb emu sensor set acceleration …`)으로만 시험했다. 휴대폰 센서는 쓰지 않는다(사용자 결정 11-13).
 * 고르는 순서: 자동차용 한정 축(LIMITED_AXES) → 표준 → 보정 전(UNCALIBRATED, 값 − 추정 바이어스). TYPE_HEADING 은 있는지만 기록한다.
 * '살아 있음' = 표본이 계속 오고, 값이 실제로 변한다(1초 평균들의 표준편차가 문턱 위). 한 번 살아 있으면 표본이 5초 끊길 때까지 유지.
 * 축 가정(장착 방향 미확인): 자동차용 센서 축 x 오른쪽·y 앞·z 위.
 */
class ParkingImu(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var acc: Sensor? = null
    private var gyro: Sensor? = null
    var hasHeading = false
        private set
    private val lock = Any()
    private var aSum = DoubleArray(3); private var aN = 0
    private var gSum = DoubleArray(3); private var gN = 0
    private val ayHist = ArrayDeque<Double>()
    private val gzHist = ArrayDeque<Double>()
    private var lastSampleAt = 0L
    var alive = false
        private set

    /** 찾은 센서 이름 한 줄(로그용, 기기 정보만) */
    fun describe(): String = "가속도 ${acc?.let { typeName(it.type) } ?: "없음"} · 자이로 ${gyro?.let { typeName(it.type) } ?: "없음"} · 방위 ${if (hasHeading) "있음" else "없음"}"

    fun start() {
        val m = sm ?: return
        acc = pick(m, listOf(TYPE_ACC_LIMITED, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, TYPE_ACC_LIMITED_UNCAL))
        gyro = pick(m, listOf(TYPE_GYRO_LIMITED, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED, TYPE_GYRO_LIMITED_UNCAL))
        hasHeading = runCatching { m.getDefaultSensor(TYPE_HEADING) != null }.getOrDefault(false)
        acc?.let { runCatching { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
        gyro?.let { runCatching { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    }

    fun stop() { runCatching { sm?.unregisterListener(this) } }

    private fun pick(m: SensorManager, types: List<Int>): Sensor? = types.firstNotNullOfOrNull { t -> runCatching { m.getDefaultSensor(t) }.getOrNull() }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        val uncal = e.sensor.type in setOf(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_GYROSCOPE_UNCALIBRATED, TYPE_ACC_LIMITED_UNCAL, TYPE_GYRO_LIMITED_UNCAL)
        fun at(i: Int): Double = (v.getOrNull(i) ?: 0f).toDouble() - (if (uncal) (v.getOrNull(i + 3) ?: 0f).toDouble() else 0.0)   // 보정 전 값은 [3..5] 가 바이어스 추정
        synchronized(lock) {
            if (e.sensor == acc) { for (i in 0..2) aSum[i] += at(i); aN++ } else if (e.sensor == gyro) { for (i in 0..2) gSum[i] += at(i); gN++ }
            lastSampleAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 1초마다(주차 틱): 지난 1초 평균. 살아 있지 않으면 null(그 경우 추적기는 관성 센서 없이 돈다) */
    fun sample(): ImuSample? {
        val (a, g) = synchronized(lock) {
            val a = if (aN > 0) DoubleArray(3) { aSum[it] / aN } else null
            val g = if (gN > 0) DoubleArray(3) { gSum[it] / gN } else null
            aSum = DoubleArray(3); aN = 0; gSum = DoubleArray(3); gN = 0
            a to g
        }
        if (android.os.SystemClock.elapsedRealtime() - lastSampleAt > 5000) { alive = false; ayHist.clear(); gzHist.clear(); return null }
        if (a == null) return null
        ayHist.addLast(a[1]); if (ayHist.size > 20) ayHist.removeFirst()
        g?.let { gzHist.addLast(it[2]); if (gzHist.size > 20) gzHist.removeFirst() }
        if (!alive && ayHist.size >= 5 && (std(ayHist) > 0.02 || (gzHist.size >= 5 && std(gzHist) > 0.002))) alive = true
        return if (alive) ImuSample(a[1], a[2], g?.get(0), g?.get(2)) else null
    }

    private fun std(xs: Collection<Double>): Double { val m = xs.average(); return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size) }

    companion object {
        // API 33 의 자동차용 센서 종류(상수 이름이 없는 compileSdk 에서도 쓰려고 숫자로)
        const val TYPE_ACC_LIMITED = 38
        const val TYPE_GYRO_LIMITED = 39
        const val TYPE_ACC_LIMITED_UNCAL = 40
        const val TYPE_GYRO_LIMITED_UNCAL = 41
        const val TYPE_HEADING = 42
        fun typeName(t: Int) = when (t) {
            Sensor.TYPE_ACCELEROMETER -> "ACCELEROMETER"; Sensor.TYPE_GYROSCOPE -> "GYROSCOPE"
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "ACCELEROMETER_UNCALIBRATED"; Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "GYROSCOPE_UNCALIBRATED"
            TYPE_ACC_LIMITED -> "ACCELEROMETER_LIMITED_AXES"; TYPE_GYRO_LIMITED -> "GYROSCOPE_LIMITED_AXES"
            TYPE_ACC_LIMITED_UNCAL -> "ACCELEROMETER_LIMITED_AXES_UNCALIBRATED"; TYPE_GYRO_LIMITED_UNCAL -> "GYROSCOPE_LIMITED_AXES_UNCALIBRATED"
            else -> "type $t"
        }
    }
}
