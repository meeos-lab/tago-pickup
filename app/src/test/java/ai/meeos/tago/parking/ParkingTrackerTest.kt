package ai.meeos.tago.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToInt

/** 주차 층 추정(새 기능 H, 방법 A): 합성 나선 경사로 궤적으로 회전 세기를 시험하고, 못 맞추는 경우(오판 표)도 시험으로 고정한다 */
class ParkingTrackerTest {
    private val LAT = 37.40; private val LON = 127.10
    private var now = 1_000_000L
    private lateinit var tr: ParkingTracker

    /** 반지름 R m 원을 속도 v 로 도는 데 필요한 조향 휠 각(도) */
    private fun wheelFor(r: Double): Double = atan(ParkingConst.WHEELBASE_M / r) * 180 / PI * ParkingConst.STEER_RATIO

    private fun sec(n: Int, speed: Double, steer: Double?, fix: GpsFix?, parked: Boolean = false, reversing: Boolean = false): ParkRecord? {
        var rec: ParkRecord? = null
        repeat(n) { now += 1000; tr.tick(now, fix, speed, steer, parked, reversing)?.let { rec = it } }
        return rec
    }

    private val good get() = GpsFix(LAT, LON, 8.0, 1.0)

    /** 건물 밖에서 달리다 입구로 들어가 GPS 가 끊긴 상태까지 */
    private fun enterLot() {
        sec(20, 8.0, 0.0, good)               // 좋은 GPS, 시속 29 km
        sec(10, 4.0, 0.0, null)               // 끊김 8초 넘김 → 건물 안
        assertEquals(ParkingTracker.Phase.INSIDE, tr.phase)
    }

    /** 한 층: 반지름 7 m·시속 11 km 로 한 바퀴(약 14.7 s) 돌고 직진 5초 */
    private fun floorRamp(turns: Double = 1.0, dir: Int = 1) {
        val v = 3.0; val r = 7.0
        val t = (2 * PI * r * turns / v).roundToInt()
        sec(t, v, dir * wheelFor(r), null)
        sec(5, v, 0.0, null)
    }

    private fun park(): ParkRecord = sec(10, 3.0, 0.0, null).let { sec(2, 0.0, 0.0, null, parked = true)!! }

    private fun setup(ranges: FloorRangeProvider = NoFloorRange) { tr = ParkingTracker(ranges); now = 1_000_000L; rampsDone = 0 }
    private var rampsDone = 0

    @Test fun spiralB1ToB5() {
        for (n in 1..5) {
            setup(); enterLot(); repeat(n) { floorRamp() }
            val r = park()
            assertEquals("B$n", floorLabel(r.floor)); assertEquals(Confidence.MEDIUM, r.confidence)
            assertEquals(LAT to LON, r.entrance)
        }
    }

    @Test fun continuousSpiralThreeTurnsIsOneRunButThreeFloors() {
        setup(); enterLot(); floorRamp(turns = 3.0)
        assertEquals(-3, park().floor)
    }

    @Test fun oppositeDirectionGoesBackUp() {
        setup(); enterLot(); repeat(2) { floorRamp(dir = 1) }; floorRamp(dir = -1)   // 내려 2, 올라 1 → B1
        assertEquals(-1, park().floor)
    }

    @Test fun downThenUpSameAmountIsUnknown() {
        setup(); enterLot(); floorRamp(dir = 1); floorRamp(dir = -1)
        val r = park(); assertNull(r.floor)           // 순 0 층 = 입구 층 근처 — 모른다고 한다
    }

    @Test fun upMoreThanDownIsAboveEntrance() {
        setup(); enterLot(); floorRamp(dir = 1); repeat(2) { floorRamp(dir = -1) }   // 아래 1, 위 2 → 순 위 1 = 지상 2층
        assertEquals(2, park().floor)
    }

    @Test fun straightRampIsUnknownNotB1() {
        setup(); enterLot(); sec(40, 3.0, 5.0, null)        // 직선 경사로: 조향 거의 없음
        val r = park(); assertNull(r.floor); assertEquals(Confidence.LOW, r.confidence)
        assertNotNull(r.entrance)                             // 입구는 그래도 안다
    }

    @Test fun flatLotCornersAreIgnored() {
        setup(); enterLot()
        repeat(4) { sec(4, 3.0, 150.0, null); sec(5, 3.0, 0.0, null) }   // 모퉁이 90° 안팎을 네 번(같은 쪽)
        assertNull(park().floor)
    }

    @Test fun uTurnsAreIgnored() {
        setup(); enterLot()
        repeat(3) { sec(4, 3.0, wheelFor(4.0), null); sec(5, 3.0, 0.0, null) }     // 반지름 4 m 유턴(약 170~180°)
        assertNull(park().floor)
    }

    /** 오판 표 — 알려진 한계: 평면 주차장에서 같은 쪽으로 한 바퀴 돌면 한 층으로 센다 */
    @Test fun knownFalsePositive_flatLotFullLoop() {
        setup(); enterLot(); floorRamp(1.0)
        assertEquals("평면 주차장의 한 바퀴 = 1층으로 오판(기압·피치 없이는 가를 수 없다)", -1, park().floor)
    }

    /** 오판 표 — 한 층에 반 바퀴(180°)씩인 지그재그 경사로는 세지 못한다(층 모름) */
    @Test fun knownLimit_halfTurnSwitchbackIsNotCounted() {
        setup(); enterLot()
        repeat(4) { floorRamp(turns = 0.5, dir = if (it % 2 == 0) 1 else -1) }
        assertNull(park().floor)
    }

    @Test fun lowConfidenceWhenTurnIsNotWholeFloors() {
        setup(); enterLot(); floorRamp(turns = 1.4)          // 500° 쯤 = 1.4 층 — 정수배에서 벗어남
        val r = park(); assertEquals(-1, r.floor); assertEquals(Confidence.LOW, r.confidence)
    }

    @Test fun highwayTunnelIsNotAnEntrance() {
        setup(); sec(20, 25.0, 0.0, good); sec(30, 25.0, 0.0, null)          // 시속 90 km 터널: GPS 끊겨도 저속 아님
        assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase)
    }

    @Test fun shortGpsGlitchIsNotAnEntrance() {
        setup(); sec(20, 8.0, 0.0, good); sec(5, 4.0, 0.0, null); sec(5, 4.0, 0.0, good)
        assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase)
    }

    @Test fun poorAccuracyCountsAsLost() {
        setup(); sec(20, 8.0, 0.0, good); sec(10, 4.0, 0.0, GpsFix(LAT, LON, 150.0, 1.0))
        assertEquals(ParkingTracker.Phase.INSIDE, tr.phase)
    }

    @Test fun staleFixCountsAsLost() {
        setup(); sec(20, 8.0, 0.0, good); sec(10, 4.0, 0.0, GpsFix(LAT, LON, 8.0, 30.0))
        assertEquals(ParkingTracker.Phase.INSIDE, tr.phase)
    }

    @Test fun noGoodFixBeforeMeansNoEntrance() {
        setup(); sec(30, 4.0, 0.0, null)                       // 처음부터 GPS 없음 — 입구를 모른다
        assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase)
    }

    @Test fun leavingTheBuildingDropsTheRecord() {
        setup(); enterLot(); floorRamp(); park()
        sec(12, 5.0, 0.0, good)
        assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase); assertNull(tr.record)
    }

    @Test fun parkOutdoorsMakesNoRecord() {
        setup(); sec(20, 8.0, 0.0, good); val r = sec(5, 0.0, 0.0, good, parked = true)
        assertNull(r); assertNull(tr.record)
    }

    @Test fun reversingIntoTheSlotAddsNoFloor() {
        setup(); enterLot(); floorRamp()
        sec(6, 1.0, 400.0, null, reversing = true)             // 후진 주차 몇 초
        assertEquals(-1, park().floor)
    }

    @Test fun standingStillWithWheelTurnedIsNotIntegrated() {
        setup(); enterLot(); sec(60, 0.0, 400.0, null)
        assertNull(park().floor)
    }

    @Test fun longPauseIsNotIntegrated() {
        setup(); enterLot()
        now += 600_000; tr.tick(now, null, 3.0, wheelFor(7.0), false, false)   // 앱이 10분 멈췄다 돌아옴 — 그 구간은 적분 안 함
        sec(1, 3.0, wheelFor(7.0), null)
        assertNull(park().floor)
    }

    @Test fun buildingRangeDoesNotClipTheEstimate() {               // 대장으로 자르지 않는다 — 범위(대장 끝 층 ~ 추정), 대표 후보는 대장 끝 층
        setup { _, _ -> FloorRange(basement = 2, above = 8) }; enterLot(); repeat(4) { floorRamp() }
        val r = park(); assertEquals(-2, r.floor); assertEquals(-4, r.alt); assertEquals(Confidence.LOW, r.confidence); assertTrue(r.bldgConflict)
    }

    @Test fun buildingWithoutBasementMeansTheFirstRampGoesUp() {
        setup { _, _ -> FloorRange(basement = 0, above = 5) }; enterLot(); floorRamp()
        assertEquals(2, park().floor)                          // 입구 = 1층, 한 번 올라 2층
    }

    @Test fun missingBuildingDataIsSkipped() {
        setup { _, _ -> null }; enterLot(); floorRamp()
        assertEquals(-1, park().floor)
        setup { _, _ -> throw IllegalStateException("키 없음") }; enterLot(); floorRamp()
        assertEquals(-1, park().floor)                         // 공급자가 던져도 추정은 그대로
    }

    @Test fun movingAfterParkInvalidatesTheOldRecord() {
        setup(); enterLot(); floorRamp(); park()
        sec(6, 3.0, 0.0, null)
        assertNull(tr.record)
    }

    @Test fun userFixAndPillarEdits() {
        setup(); enterLot(); floorRamp(); val r = park()
        assertEquals(-1, r.floor)
        tr.setFloor(-2, "user", now); assertEquals(-2, tr.record!!.floor); assertEquals("user", tr.record!!.floorSource); assertEquals(-1, tr.record!!.estFloor)
        tr.setPillar("C-12"); assertEquals("C-12", tr.record!!.pillar)
        tr.clear(); assertNull(tr.record); assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase)
        assertNull(tr.setFloor(-1, "user", now)); assertNull(tr.setPillar("A-1"))   // 기록이 없으면 만들지 않는다
    }

    @Test fun stepFloorSkipsZeroAndClamps() {
        assertEquals(-2, stepFloor(-3, up = true)); assertEquals(-4, stepFloor(-3, up = false))
        assertEquals(1, stepFloor(-1, up = true)); assertEquals(-1, stepFloor(1, up = false))
        assertEquals(2, stepFloor(1, up = true)); assertEquals(1, stepFloor(2, up = false))
        assertEquals(1, stepFloor(null, up = true)); assertEquals(-1, stepFloor(null, up = false))
        assertEquals(-ParkingConst.MAX_FLOORS, stepFloor(-ParkingConst.MAX_FLOORS, up = false)); assertEquals(ParkingConst.MAX_FLOORS, stepFloor(ParkingConst.MAX_FLOORS, up = true))
    }

    @Test fun labels() {
        assertEquals("B3", floorLabel(-3)); assertEquals("2층", floorLabel(2)); assertEquals("2F", floorLabel(2, ko = false))
        assertNull(floorLabel(null)); assertNull(floorLabel(0))
    }

    @Test fun estimateBeforeParkingIsAvailable() {
        setup(); enterLot(); floorRamp(); floorRamp()
        // run 이 끝난 직후(직진 5초)라 두 층이 이미 세어져 있다
        val e = tr.estimate(); assertEquals(-2, e.floor); assertTrue(e.rampRuns == 2)
    }
    // ---------------------------------------------------------------- 11-13 2차: 경사로 무늬·범위·하향등·출차 역산·늦게 온 건물 정보

    @Test fun fractionalTurnShowsARange() {
        setup(); enterLot(); floorRamp(turns = 1.4)
        val r = park(); assertEquals(-1, r.floor); assertEquals(-2, r.alt); assertEquals(Confidence.LOW, r.confidence)
    }

    @Test fun wholeTurnsHaveNoRange() { setup(); enterLot(); repeat(2) { floorRamp() }; assertNull(park().alt) }

    /** 층 안에서 큰 블록(반지름 25 m)을 한 바퀴 — 경사로 무늬가 아니라 층으로 세지 않고 범위에만 */
    @Test fun wideLoopIsNotARampButWidensTheRange() {
        setup(); enterLot(); floorRamp()
        val v = 3.0; val r = 25.0
        sec((2 * PI * r / v).roundToInt(), v, wheelFor(r), null); sec(5, v, 0.0, null)
        val rec = park(); assertEquals(-1, rec.floor); assertEquals(-2, rec.alt); assertEquals(Confidence.LOW, rec.confidence)
    }

    @Test fun wideLoopAloneIsUnknown() {
        setup(); enterLot()
        val v = 3.0; val r = 25.0
        sec((2 * PI * r / v).roundToInt(), v, wheelFor(r), null); sec(5, v, 0.0, null)
        assertNull(park().floor)
    }

    @Test fun wobblySteeringIsNotARamp() {
        setup(); enterLot()
        repeat(40) { i -> sec(1, 3.0, if (i % 2 == 0) 120.0 else 520.0, null) }   // 조향이 크게 들쭉날쭉
        sec(5, 3.0, 0.0, null)
        assertNull(park().floor)
    }

    @Test fun fastTurningIsNotARamp() {
        setup(); enterLot()
        val v = 7.0; val r = 7.0
        sec((2 * PI * r / v).roundToInt(), v, wheelFor(r), null); sec(5, v, 0.0, null)   // 시속 25 km 로 도는 회전은 경사로로 보지 않는다
        assertNull(park().floor)
    }

    @Test fun lowBeamTurningOnSpeedsUpTheEntrance() {
        setup()
        sec(20, 8.0, 0.0, good)
        repeat(3) { now += 1000; tr.tick(now, good, 8.0, 0.0, false, false, lowBeamOn = false) }
        val poor = GpsFix(LAT, LON, 45.0, 1.0)                          // 정확도가 나빠짐(아직 '끊김'은 아님)
        repeat(5) { now += 1000; tr.tick(now, poor, 4.0, 0.0, false, false, lowBeamOn = true) }
        assertEquals("하향등 켜짐 + GPS 나빠짐 4 s → 입구", ParkingTracker.Phase.INSIDE, tr.phase)
    }

    @Test fun poorGpsWithoutBeamIsNotEnough() {
        setup(); sec(20, 8.0, 0.0, good)
        repeat(10) { now += 1000; tr.tick(now, GpsFix(LAT, LON, 45.0, 1.0), 4.0, 0.0, false, false, lowBeamOn = false) }
        assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase)
    }

    @Test fun beamAlreadyOnIsNoClue() {
        setup()
        repeat(20) { now += 1000; tr.tick(now, good, 8.0, 0.0, false, false, lowBeamOn = true) }   // 처음부터 켜져 있음(밤)
        repeat(6) { now += 1000; tr.tick(now, GpsFix(LAT, LON, 45.0, 1.0), 4.0, 0.0, false, false, lowBeamOn = true) }
        assertEquals(ParkingTracker.Phase.OUTSIDE, tr.phase)
    }

    @Test fun exitCountsBackToZeroWhenTheEstimateWasConsistent() {
        setup(); enterLot(); repeat(2) { floorRamp(dir = 1) }; park()
        sec(6, 3.0, 0.0, null)                                          // 출발
        repeat(2) { floorRamp(dir = -1) }                               // 올라 나옴
        sec(12, 5.0, 0.0, good)                                         // 건물 밖
        assertEquals(ExitCheck(-2, 0), tr.lastExit)
    }

    @Test fun exitResidualFlagsAMismatch() {
        setup(); enterLot(); repeat(2) { floorRamp(dir = 1) }; park()
        sec(6, 3.0, 0.0, null); floorRamp(dir = -1)                     // 한 바퀴만 돌고 나옴(직선 경사로가 섞였거나 추정이 틀림)
        sec(12, 5.0, 0.0, good)
        assertEquals(ExitCheck(-2, 1), tr.lastExit)
    }

    @Test fun buildingInfoArrivingLateReclampsTheRecord() {
        setup(); enterLot(); repeat(4) { floorRamp() }
        assertEquals(-4, park().floor)
        tr.setRange(FloorRange(basement = 2, above = 10))
        assertEquals(-2, tr.record!!.floor); assertEquals(-4, tr.record!!.alt); assertEquals(Confidence.LOW, tr.record!!.confidence); assertTrue(tr.record!!.bldgConflict)
        tr.setFloor(-3, "ocr", now); tr.setRange(FloorRange(basement = 1, above = 10))
        assertEquals("사진·고침으로 정한 층은 건물 정보가 늦게 와도 바꾸지 않는다", -3, tr.record!!.floor)
    }
    // ---------------------------------------------------------------- 차 관성 센서가 있을 때(있으면 쓰는 경로)

    private fun imu(pitchDeg: Double, yawRad: Double? = 0.0) = ImuSample(InclineConst.G * kotlin.math.sin(pitchDeg * PI / 180), InclineConst.G * kotlin.math.cos(pitchDeg * PI / 180), null, yawRad)   // 옆 축 자이로는 이 시험에서 쓰지 않는다(InclineTrackerTest 가 본다)

    /** yawRad 를 안 주면 조향·속도에서 실제 차가 낼 yaw 각속도(자이로 값)를 만든다 */
    private fun secI(n: Int, speed: Double, steer: Double?, fix: GpsFix?, pitch: Double, yawRad: Double? = null, parked: Boolean = false): ParkRecord? {
        var rec: ParkRecord? = null
        val yaw = yawRad ?: steer?.let { speed * kotlin.math.tan(it / ParkingConst.STEER_RATIO * PI / 180) / ParkingConst.WHEELBASE_M } ?: 0.0
        repeat(n) { now += 1000; tr.tick(now, fix, speed, steer, parked, false, imu = imu(pitch, yaw))?.let { rec = it } }
        return rec
    }

    private fun enterLotI() { secI(60, 8.0, 0.0, good, 0.0); secI(10, 4.0, 0.0, null, 0.0); assertEquals(ParkingTracker.Phase.INSIDE, tr.phase) }

    /** 나선 한 층: 회전(조향) + 내려감(pitch −8°, 첫 경사로 11 s 약 4.6 m — 첫 판까지 깊다, 그 뒤 8 s 약 3.3 m) */
    private fun rampI(dir: Int = 1, pitch: Double = -8.0, turns: Double = 1.0) {
        val v = 3.0; val r = 7.0; val total = (2 * PI * r * turns / v).roundToInt(); val down = if (rampsDone++ == 0) 11 else 8
        secI(down, v, dir * wheelFor(r), null, pitch); secI(total - down, v, dir * wheelFor(r), null, 0.0); secI(5, v, 0.0, null, 0.0)
    }

    private fun parkI(): ParkRecord = secI(10, 3.0, 0.0, null, 0.0).let { secI(2, 0.0, 0.0, null, 0.0, parked = true)!! }

    @Test fun imuAgreesWithTurnsGivesMedium() {
        setup(); enterLotI(); repeat(3) { rampI() }
        val r = parkI(); assertEquals(-3, r.floor); assertNull(r.alt); assertEquals(Confidence.MEDIUM, r.confidence); assertTrue(tr.imuUsed)
    }

    @Test fun imuCountsAStraightRampThatTurnsCannot() {
        setup(); enterLotI(); secI(6, 3.0, 5.0, null, -10.0); secI(20, 3.0, 5.0, null, 0.0)   // 일자 경사로: 회전 없음
        val r = parkI(); assertEquals(-1, r.floor); assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test fun imuSeesNoHeightInAFlatLoop() {
        setup(); enterLotI(); secI(15, 3.0, wheelFor(7.0), null, 0.0); secI(5, 3.0, 0.0, null, 0.0)   // 평면 한 바퀴(회전만으로는 B1 오판)
        val r = parkI(); assertEquals(Confidence.LOW, r.confidence)                                 // 높이 변화가 없어 단정하지 않는다
    }

    @Test fun imuAndTurnsDisagreeShowARange() {
        setup(); enterLotI(); repeat(2) { rampI() }; rampI(pitch = 0.0)    // 세 바퀴 돌았지만 내려간 것은 두 층
        val r = parkI(); assertEquals(-2, r.floor); assertEquals(-3, r.alt); assertEquals(Confidence.LOW, r.confidence)
    }

    @Test fun yawGyroCountsTurnsWithoutSteering() {
        setup(); enterLotI()
        val v = 3.0; val r = 7.0; val yaw = v / r
        repeat(2) { i -> val d = if (i == 0) 11 else 8; secI(d, v, null, null, -8.0, yawRad = yaw); secI(15 - d, v, null, null, 0.0, yawRad = yaw); secI(5, v, null, null, 0.0, yawRad = 0.0) }
        val rec = parkI(); assertEquals(-2, rec.floor); assertEquals(2, tr.estimate().rampRuns)   // 조향각 없이 yaw 자이로로 두 바퀴
    }

    @Test fun withoutImuNothingChanges() {
        setup(); enterLot(); repeat(2) { floorRamp() }
        val r = park(); assertEquals(-2, r.floor); assertEquals(false, tr.imuUsed)
    }

    // ---------------------------------------------------------------- 11-14 주차 논문 반영: 대장은 약한 단서

    @Test fun labelOneAboveBuilding_heightDeeperThanRegisterIsNotCut() {
        // 현장 표기가 물리 층보다 하나 큰 건물 꼴: 차가 잰 높이는 세 층, 대장은 지하 2층 — 예전에는 B2 로 잘랐다(주차 논문 5.6: 이런 건물에서 일치 44.1 → 36.2 %)
        setup { _, _ -> FloorRange(basement = 2, above = 10) }; enterLotI(); repeat(3) { rampI() }
        val r = parkI()
        assertEquals(-2, r.floor); assertEquals(-3, r.alt); assertEquals(Confidence.LOW, r.confidence); assertTrue(r.bldgConflict); assertEquals(2, r.bldgBasement)   // 추정 B3 를 지우지 않는다
    }

    @Test fun registerAgreeingChangesNothing() {
        setup { _, _ -> FloorRange(basement = 3, above = 10) }; enterLotI(); repeat(3) { rampI() }
        val r = parkI(); assertEquals(-3, r.floor); assertNull(r.alt); assertEquals(Confidence.MEDIUM, r.confidence); assertEquals(false, r.bldgConflict)
    }

    @Test fun registerWithoutBasementNeverMakesFloorZero() {         // 지하가 없다고 적힌 건물에서 내려갔다: 다른 끝은 입구 층(1층), 0 층은 없다
        setup { _, _ -> FloorRange(basement = 0, above = 5) }; enterLotI(); rampI(turns = 1.0)
        val r = parkI(); assertEquals(1, r.floor); assertEquals(-1, r.alt); assertTrue(r.bldgConflict)
    }
}
