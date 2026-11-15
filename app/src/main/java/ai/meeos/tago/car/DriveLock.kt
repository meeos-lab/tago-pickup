package ai.meeos.tago.car

/**
 * 주행 상태 잠금 판정(차량 앱 검증 V-01, 2026-11-13). 정차 전용 화면(설정·오픈소스 고지·충전 설문·충전 신고 버튼)을 열어도 되는지 정한다.
 * 기어를 아직 못 읽었거나(콜드 스타트), 읽기가 계속 실패해 마지막 읽기가 오래됐으면 **모르는 것이므로 주행 중으로 본다**(잠금 쪽).
 * 기어는 1 s 마다 읽으므로 GEAR_STALE_MS 동안 새 값이 없으면 읽기가 막힌 것이다. 순수 함수라 단위 시험(DriveLockTest)이 규칙을 고정한다.
 */
object DriveLock {
    const val GEAR_STALE_MS = 5_000L

    /** true = 주행 중이거나 모름(정차 전용 화면을 열지 않는다). gearName: VehicleGear.name, null = 한 번도 못 읽음. gearAgeMs: 마지막 읽기 후 경과(null = 못 읽음) */
    fun lockedOut(gearName: String?, gearAgeMs: Long?): Boolean {
        if (gearName == null || gearAgeMs == null || gearAgeMs > GEAR_STALE_MS) return true
        return gearName == "GEAR_DRIVE"
    }
}
