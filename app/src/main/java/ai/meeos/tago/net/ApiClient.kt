package ai.meeos.tago.net

import android.os.Handler
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

/**
 * 시연 서버(앱 내장 127.0.0.1:8790 또는 PC 10.0.2.2:8790) HTTP 클라이언트.
 * 요청은 io 스레드에서, 콜백은 메인 스레드에서 부른다. 제품에서는 HTTPS(TLS 1.2+) 도메인 + Authorization 토큰으로 바꾼다.
 */
class ApiClient(private val baseUrl: String, private val io: Executor, private val main: Handler) {

    /** 비동기 요청. 응답 JSON 을 onOk 로, 실패를 onError 로 (둘 다 메인 스레드) */
    fun request(method: String, path: String, body: JSONObject? = null, onError: (Throwable) -> Unit = {}, onOk: (JSONObject) -> Unit) {
        io.execute {
            runCatching { JSONObject(http(method, path, body?.toString())) }
                .onSuccess { r -> main.post { onOk(r) } }
                .onFailure { e -> main.post { onError(e) } }
        }
    }

    fun post(path: String, body: JSONObject, onError: (Throwable) -> Unit = {}, onOk: (JSONObject) -> Unit) = request("POST", path, body, onError, onOk)
    fun get(path: String, onError: (Throwable) -> Unit = {}, onOk: (JSONObject) -> Unit) = request("GET", path, null, onError, onOk)

    /** 동기 요청(io 스레드에서만 부를 것) */
    fun http(method: String, path: String, body: String?): String {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = 3000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
