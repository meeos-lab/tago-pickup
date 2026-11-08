package ai.meeos.tago.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 차 안 경로 이탈 판정식(11-08): 정확도 ≤ 30 m·고정 10 s 안일 때만 횡거리 ≥ 30 m 로 true/false, 아니면 보내지 않음(null) */
class OffRouteSensorTest {
    @Test fun decideRule() {
        assertEquals(false, OffRouteSensor.decide(5.0, 1000, 12.0))
        assertEquals(true, OffRouteSensor.decide(5.0, 1000, 70.0))
        assertEquals(true, OffRouteSensor.decide(30.0, 10_000, 30.0))
        assertNull("정확도 모름", OffRouteSensor.decide(null, 1000, 70.0))
        assertNull("정확도 30 m 보다 나쁨", OffRouteSensor.decide(31.0, 1000, 70.0))
        assertNull("고정이 오래됨", OffRouteSensor.decide(5.0, 10_001, 70.0))
        assertNull("횡거리 못 구함", OffRouteSensor.decide(5.0, 1000, Double.NaN))
    }
}
