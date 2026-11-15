package ai.meeos.tago.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V-01: 기어를 모르면 주행 중으로 본다(정차 전용 화면 잠금) */
class DriveLockTest {
    @Test fun unknownGearIsLocked() = assertTrue(DriveLock.lockedOut(null, null))
    @Test fun neverReadIsLockedEvenWithName() = assertTrue(DriveLock.lockedOut("GEAR_PARK", null))
    @Test fun driveIsLocked() = assertTrue(DriveLock.lockedOut("GEAR_DRIVE", 200))
    @Test fun parkFreshIsOpen() = assertFalse(DriveLock.lockedOut("GEAR_PARK", 900))
    @Test fun neutralAndReverseFreshAreOpenLikeBefore() {
        assertFalse(DriveLock.lockedOut("GEAR_NEUTRAL", 0)); assertFalse(DriveLock.lockedOut("GEAR_REVERSE", 1000))
    }
    @Test fun staleParkIsLocked() {
        assertFalse(DriveLock.lockedOut("GEAR_PARK", DriveLock.GEAR_STALE_MS))        // 경계: 5 s 까지는 신선
        assertTrue(DriveLock.lockedOut("GEAR_PARK", DriveLock.GEAR_STALE_MS + 1))     // 읽기가 막힌 뒤의 낡은 Park 는 믿지 않는다
    }
}
