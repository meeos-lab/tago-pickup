package ai.meeos.tago.engine

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 에뮬레이터 없이 두 엔진 대조(check_link_parity.py)를 돌리는 JVM 허브: 앱의 LinkHub 를 LocalServer 와 같은 경로 규칙으로 연다.
 *  평소 단위 시험에서는 건너뛴다. 켜기(저장소 루트):
 *    ./gradlew :app:testDebugUnitTest -Pmeeos.edition=gilbeot -Pmeeos.linkServe=8799 --tests '*LinkHubServe*'   (배경 실행)
 *    python server/link/check_link_parity.py http://127.0.0.1:8798 http://127.0.0.1:8799 ko
 *    curl -X POST http://127.0.0.1:8799/__stop   (끝내기, 안 부르면 30분 뒤 스스로 끝난다)
 *  한계: JVM 의 org.json(20240303)은 안드로이드 org.json 과 같은 구현이 아니라 숫자 표기가 다를 수 있다 — 기기 대조를 대신하지 않는다(80 7.3). */
class LinkHubServe {
    @Test fun serve() {
        val port = System.getProperty("meeos.linkServe").orEmpty().toIntOrNull()
        assumeTrue("meeos.linkServe 가 없으면 건너뛴다", port != null)
        val hub = LinkHub(MeeosEngineTest.server("link/gyeongbu_route.json"))
        val done = CountDownLatch(1)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", port!!), 0)
        fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        http.createContext("/") { ex ->
            val raw = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val path = raw.substringBefore("?")
            val parts = path.split("/").filter { it.isNotEmpty() }
            val query = raw.substringAfter("?", "").split("&").filter { "=" in it }
                .associate { decode(it.substringBefore("=")) to decode(it.substringAfter("=")) }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val (code, o) = try {                       // 오류 처리는 LocalServer 와 같다(JSON 오류 400, 그 밖 500)
                when {
                    parts == listOf("__stop") -> { done.countDown(); 200 to JSONObject().put("ok", true) }
                    parts.size >= 2 && parts[0] == "api" && (parts[1] == "link" || parts[1] == "road") ->
                        hub.handle(ex.requestMethod, parts, query, if (ex.requestMethod == "POST" && body.isNotBlank()) JSONObject(body) else JSONObject())
                    else -> 404 to JSONObject().put("error", "not found")
                }
            } catch (e: org.json.JSONException) {
                400 to JSONObject().put("error", "bad request: ${e.message}")
            } catch (e: Exception) {
                e.printStackTrace()
                500 to JSONObject().put("error", "server error")
            }
            val bytes = o.toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        println("LinkHubServe :$port")
        done.await(30, TimeUnit.MINUTES)
        http.stop(0)
    }
}
