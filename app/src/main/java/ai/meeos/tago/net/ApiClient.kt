package ai.meeos.tago.net

import android.os.Handler
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * 시연 서버(앱 내장 127.0.0.1:<LOCAL_PORT> — 길벗 8790·타고 8793 — 또는 PC 10.0.2.2:8790) HTTP 클라이언트.
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
    fun http(method: String, path: String, body: String?): String =
        Http.text(baseUrl + path, method, body?.toByteArray(), if (body != null) JSON_HEADER else emptyMap(), connectMs = TIMEOUT_MS, readMs = TIMEOUT_MS)

    private companion object {
        const val TIMEOUT_MS = 3000   // 연결·읽기 각각(내장 서버는 같은 기기라 짧게)
        val JSON_HEADER = mapOf("Content-Type" to "application/json; charset=utf-8")
    }
}
