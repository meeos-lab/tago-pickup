package ai.meeos.tago.road

import ai.meeos.tago.road.RoadLinkController.Companion.isHardBrake
import ai.meeos.tago.road.RoadLinkController.Companion.isSwerve
import ai.meeos.tago.road.RoadLinkController.Companion.isYield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도로 링크 감지기 판정식(40 A-04·A-13b): 쿨다운·보고는 RoadLinkController, 여기서는 조건만 */
class RoadDetectorsTest {
    @Test fun hardBrake() {
        assertTrue(isHardBrake(20f, 16f, 15f))     // 1초에 4 m/s 감속, 다음 1초도 낮게
        assertFalse(isHardBrake(20f, 18f, 17f))    // 2 m/s — 약하다
        assertFalse(isHardBrake(4f, 0f, 0f))       // 18 km/h 아래에서는 보지 않는다
        assertFalse(isHardBrake(20f, 16f, 17.5f))  // 다음 1초에 다시 빨라짐 = 순간 출렁임
    }

    @Test fun yieldNeedsSlowdownAndRightMove() {
        assertTrue(isYield(25f, 21f, rightSignal = true, steer = null))
        assertTrue(isYield(25f, 21f, rightSignal = false, steer = 12f))
        assertFalse(isYield(25f, 21f, rightSignal = false, steer = -12f))   // 왼쪽(음수)은 비킴이 아니다
        assertFalse(isYield(25f, 23f, rightSignal = true, steer = null))    // 2 m/s 감속 — 약하다
        assertFalse(isYield(8f, 4f, rightSignal = true, steer = null))      // 시속 30 아래
    }

    @Test fun swerveWithoutBrake() {
        assertTrue(isSwerve(0f, 65f, 20f, brake = false))
        assertTrue(isSwerve(10f, -55f, 20f, brake = null))                  // 브레이크 신호를 못 읽어도
        assertFalse(isSwerve(0f, 65f, 20f, brake = true))                   // 밟았으면 회피가 아니라 제동
        assertFalse(isSwerve(0f, 40f, 20f, brake = false))                  // 60° 미만
        assertFalse(isSwerve(0f, 65f, 10f, brake = false))                  // 40 km/h 아래
        assertFalse(isSwerve(null, 65f, 20f, brake = false))
    }
}
