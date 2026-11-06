package ai.meeos.tago.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection 한 번 쓰기(동기 — io 스레드에서만): 열기·타임아웃·머리글·본문·읽기, 그리고 성공·실패와 상관없이 disconnect.
 * 앱 안의 HTTP 는 모두 이 둘을 쓴다(ApiClient·TMAP 폴링·티맵 경로·연구 기록 업로드) — 연결 정리를 한 곳에서만 지킨다.
 */
object Http {
    /**
     * 본문을 읽어 돌려준다. 4xx·5xx 는 HttpURLConnection 이 던지는 그대로(IOException).
     * @param requireOk true 면 본문을 읽기 전에 상태가 200 인지 보고, 아니면 IllegalStateException("HTTP n")
     */
    fun text(url: String, method: String = "GET", body: ByteArray? = null, headers: Map<String, String> = emptyMap(),
             connectMs: Int, readMs: Int, requireOk: Boolean = false): String = open(url, method, body, headers, connectMs, readMs) { c ->
        if (requireOk && c.responseCode != 200) error("HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().use { it.readText() }
    }

    /** 본문은 읽지 않고 상태 코드만 */
    fun status(url: String, method: String, body: ByteArray?, headers: Map<String, String>, connectMs: Int, readMs: Int): Int =
        open(url, method, body, headers, connectMs, readMs) { it.responseCode }

    private fun <T> open(url: String, method: String, body: ByteArray?, headers: Map<String, String>, connectMs: Int, readMs: Int,
                         read: (HttpURLConnection) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            return read(c)
        } finally {
            c.disconnect()   // 실패(4xx·5xx·끊김)에서도 연결을 돌려준다
        }
    }
}
