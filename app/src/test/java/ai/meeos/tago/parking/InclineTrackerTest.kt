package ai.meeos.tago.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 차 관성 센서 경로(있을 때만 쓰는 것)의 순수 계산: 합성 pitch·속도 시계열 — 나선·일자·반층·과속방지턱·가감속 가짜 pitch */
class InclineTrackerTest {
    private val g = InclineConst.G
    private var mountDeg = 2.0          // 헤드유닛 장착 기울기(영점으로 빼야 하는 값)
    private var gyroBiasDeg = 0.0       // 자이로 옆 축 영점(°/s) — 정차 중에 맞춰야 하는 값
    private lateinit var tr: InclineTracker
    private var prevPitch = 0.0

    /** 진짜 pitch(도)·속도·가속으로 1초 표본을 만들어 먹인다 */
    private fun feed(sec: Int, pitch: Double, v: Double, a: Double = 0.0, inside: Boolean = true, gyro: Boolean = true) {
        repeat(sec) {
            val th = (pitch + mountDeg) * PI / 180
            val gx = if (gyro) (pitch - prevPitch + gyroBiasDeg) * PI / 180 else null      // 이번 1초의 기울기 변화(rad/s) + 영점
            prevPitch = pitch
            tr.update(1.0, v, a, ImuSample(a + g * sin(th), g * cos(th), gx, 0.0), inside)
        }
    }

    /** 지하 n 층까지 나선으로 내려감: 첫 경사로 11 s(약 4.6 m, 첫 판까지 깊다) + 그 뒤 8 s(약 3.3 m)씩, 층 안 20 s. pitch 를 바꾸면 오르막 */
    private fun floors(n: Int, pitch: Double = -8.0, gyro: Boolean = true) { repeat(n) { i -> feed(if (i == 0) 11 else 8, pitch, 3.0, gyro = gyro); feed(20, 0.0, 3.0, gyro = gyro) } }

    private fun calibrated(): InclineTracker { tr = InclineTracker(); prevPitch = 0.0; mountDeg = 2.0; gyroBiasDeg = 0.0; feed(120, 0.0, 10.0, inside = false); tr.startInside(); return tr }

    @Test fun calibrationLearnsTheMountingTilt() {
        calibrated(); assertEquals(mountDeg, tr.offsetDeg, 0.3); assertTrue(tr.calibratedS >= 100)
    }

    @Test fun spiralDownThreeFloors() {
        calibrated()
        floors(3)                                                    // 첫 경사로 약 4.6 m + 3.3 m 씩 + 층 안 20 s
        tr.finish()
        assertEquals(3, tr.downRamps); assertEquals(0, tr.upRamps); assertEquals(3, tr.floorsByHeight())
    }

    @Test fun straightRampOneFloorIsCountedUnlikeTurning() {
        calibrated(); feed(6, -10.0, 3.0); feed(10, 0.0, 3.0); tr.finish()
        assertEquals(1, tr.downRamps); assertEquals(1, tr.floorsByHeight())
    }

    @Test fun splitLevelTwoHalfRampsMakeOneFloor() {
        calibrated(); repeat(2) { feed(4, -7.0, 3.0); feed(10, 0.0, 3.0) }; tr.finish()
        assertEquals(1, tr.floorsByHeight())                       // 반 층 둘 = 한 층(높이로 센다)
    }

    @Test fun speedBumpIsIgnored() {
        calibrated(); repeat(3) { feed(1, 6.0, 2.0); feed(1, -6.0, 2.0); feed(10, 0.0, 3.0) }; tr.finish()
        assertEquals(0, tr.downRamps + tr.upRamps); assertEquals(0, tr.floorsByHeight())
    }

    @Test fun brakingIsNotAFakeSlope() {
        calibrated(); feed(3, 0.0, 6.0, a = -2.0); feed(3, 0.0, 6.0, a = 2.0); feed(5, 0.0, 6.0); tr.finish()
        assertEquals(0, tr.downRamps + tr.upRamps); assertEquals(0, tr.floorsByHeight())
    }

    @Test fun withoutGyroTheAccelerometerAloneWorks() {
        tr = InclineTracker(); prevPitch = 0.0; feed(120, 0.0, 10.0, inside = false, gyro = false); tr.startInside()
        floors(2, gyro = false); tr.finish()
        assertEquals(2, tr.floorsByHeight())
    }

    @Test fun goingUpIsNegative() {
        calibrated(); floors(2, pitch = 8.0); tr.finish()
        assertEquals(2, tr.upRamps); assertEquals(-2, tr.floorsByHeight())
    }

    @Test fun flatLoopHasNoHeight() {
        calibrated(); feed(60, 0.0, 3.0); tr.finish()
        assertEquals(0, tr.floorsByHeight()); assertFalse(tr.onRamp)
    }

    @Test fun startInsideResetsCountsButKeepsZero() {
        calibrated(); feed(7, -8.0, 3.0); feed(5, 0.0, 3.0); val off = tr.offsetDeg
        tr.startInside(); assertEquals(0.0, tr.dhM, 1e-9); assertEquals(0, tr.downRamps); assertEquals(off, tr.offsetDeg, 1e-9)
    }

    // ---------------------------------------------------------------- 11-14 주차 논문 반영: 높이는 경사 구간에서만, 정차 중 자이로 영점

    @Test fun longFlatDriveDoesNotPileUpHeight() {
        calibrated(); mountDeg = 2.8                                // 영점을 맞춘 뒤 0.8° 틀어짐(짐·타이어) — 평지에서도 0.8° 오르막으로 보인다(끝 문턱 1° 아래)
        feed(90, 0.0, 3.0); feed(7, -8.0, 3.0); feed(90, 0.0, 3.0); tr.finish()   // 층 안 긴 평지 + 경사로 한 번 + 긴 평지
        assertEquals(1, tr.downRamps); assertEquals(1, tr.floorsByHeight())
        assertTrue("전 구간을 더하면 평지 오차가 쌓여 내려간 높이를 덮는다(예전 규칙)", tr.dhAllM > 0.0)
    }

    @Test fun gyroZeroIsLearnedWhileStopped() {
        calibrated(); gyroBiasDeg = 0.5                             // 영점이 0.5 °/s 틀어진 자이로(주차 논문 표 10 의 악조건)
        feed(5, 0.0, 0.0)                                           // 입구 차단기 앞 정차 5 s
        assertEquals(0.5, Math.toDegrees(tr.gxBias), 0.01); assertTrue(tr.biasS > 0)
        floors(3); tr.finish()
        assertEquals(3, tr.downRamps); assertEquals(3, tr.floorsByHeight())
    }

    @Test fun withoutAStopAShiftedGyroMiscounts() {                 // 정차가 한 번도 없으면 영점을 맞추지 못한다 — 맞추지 않으면 틀린다는 것을 시험으로 고정
        calibrated(); gyroBiasDeg = 0.5
        floors(3); tr.finish()
        assertEquals(0.0, tr.biasS, 1e-9); assertTrue(tr.floorsByHeight() != 3)
    }

    @Test fun yawZeroIsLearnedWhileStopped() {
        calibrated()
        repeat(5) { tr.update(1.0, 0.0, 0.0, ImuSample(g * sin(mountDeg * PI / 180), g * cos(mountDeg * PI / 180), 0.0, 0.01), inside = true) }
        assertEquals(0.01, tr.gzBias, 1e-9); assertEquals(0.02, tr.yawRate(ImuSample(0.0, g, 0.0, 0.03))!!, 1e-9)
    }

    @Test fun slowCreepIsNotAStop() {                               // 0.1 m/s 이상으로 기어가면 정차가 아니다 — 영점을 맞추지 않는다
        calibrated(); gyroBiasDeg = 0.5; feed(10, 0.0, 0.5); assertEquals(0.0, tr.biasS, 1e-9)
    }

    // ---------------------------------------------------------------- 11-14 건물 밖 도로 기울기(주차 논문 표 10 마지막 줄)

    @Test fun stopOnASlopeDoesNotSwallowTheSlope() {                // 기운 길 위 정차: 자이로 영점만 맞추고, 그 기울기를 장착 영점으로 삼키지 않는다
        calibrated(); val off = tr.offsetDeg
        feed(10, 5.0, 0.0, inside = false)
        assertEquals(off, tr.offsetDeg, 1e-9); assertEquals(5.0, tr.pitchDeg, 0.3); assertEquals(0.0, tr.gxBias, 1e-6)
    }

    @Test fun aTiltedRoadBarelyMovesALongLearnedMount() {            // 30분 배운 장착 영점(저장소에서 이어 씀)은 1° 기운 길 90 s 에 0.05° 남짓만 움직인다
        tr = InclineTracker(); prevPitch = 0.0; mountDeg = 2.0; gyroBiasDeg = 0.0
        tr.restoreMount(2.0, 1800.0)
        feed(90, 1.0, 10.0, inside = false); tr.startInside()
        assertEquals(2.0, tr.offsetDeg, 0.06)
        floors(3); tr.finish()
        assertEquals(3, tr.floorsByHeight())
    }

    @Test fun aFreshMountLearnsTheTiltedRoad() {                      // 처음 쓰는 차(배운 것 없음)는 그 길의 기울기를 상당 부분 삼킨다(90 s 뒤 약 65 %) — 저장이 필요한 까닭
        tr = InclineTracker(); prevPitch = 0.0; mountDeg = 2.0; gyroBiasDeg = 0.0
        feed(90, 1.0, 10.0, inside = false)
        assertEquals(2.65, tr.offsetDeg, 0.05)
    }

    @Test fun restoreMountIgnoresNonsense() {
        tr = InclineTracker(); tr.restoreMount(40.0, 100.0); assertEquals(0.0, tr.offsetDeg, 1e-9)
        tr.restoreMount(1.0, 0.0); assertEquals(0.0, tr.calibratedS, 1e-9)
        tr.restoreMount(1.5, 99999.0); assertEquals(1.5, tr.offsetDeg, 1e-9); assertEquals(InclineConst.CAL_MAX_S, tr.calibratedS, 1e-9)
    }

    // ---------------------------------------------------------------- 11-14 층 계산식(첫 판 깊이 분리 + 범위 문턱 0.15)

    @Test fun firstDeckCountsAsASingleFloor() {                      // 첫 판까지 4.6 m 는 한 층(예전 ÷3.0 m 는 B2 로 셌다)
        calibrated(); floors(1); tr.finish()
        assertEquals(1, tr.floorsByHeight()); assertTrue(tr.heightFrac() < InclineConst.RANGE_FRAC)
    }

    @Test fun halfwayBetweenFloorsIsFlaggedForARange() {             // 첫 판 + 반 층쯤(약 6.3 m) → 정수에서 멀다
        calibrated(); feed(15, -8.0, 3.0); feed(20, 0.0, 3.0); tr.finish()
        assertTrue(tr.heightFrac() > InclineConst.RANGE_FRAC)
    }
}
