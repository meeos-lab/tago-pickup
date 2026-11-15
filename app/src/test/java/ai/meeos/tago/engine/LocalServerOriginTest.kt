package ai.meeos.tago.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내장 서버 쓰기 요청의 Origin 검사(C-5): 브라우저의 다른 사이트 페이지가 루프백 허브에 몰래 쓰지 못하게 */
class LocalServerOriginTest {
    @Test fun noOriginIsAllowed() = assertTrue("앱·curl·PC 중계는 Origin 이 없다", LocalServer.originOk(null))

    @Test fun loopbackOriginsAreAllowedAnyPort() {
        for (o in listOf("http://127.0.0.1:8790", "http://127.0.0.1:8791", "http://localhost:8790", "http://[::1]:8790", "http://localhost"))
            assertTrue(o, LocalServer.originOk(o))
    }

    @Test fun foreignOriginsAreRejected() {
        for (o in listOf("http://evil.example", "https://evil.example:8790", "null", "http://127.0.0.1.evil.example", "http://localhost.evil.example:8790", "file://", "", "garbage"))
            assertFalse(o, LocalServer.originOk(o))
    }
}
