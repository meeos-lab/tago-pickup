package ai.meeos.tago.engine

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 앱 내장 시연 서버. server/meeos_server.py 와 같은 경로(/api/…, /m/<세션>, /car/<세션>)를 제공한다.
 * 차량 WebView는 127.0.0.1:8790 으로, PC 브라우저는 `adb forward tcp:8791 tcp:8790` 후 127.0.0.1:8791 로 접속한다.
 * 시연 전용 HTTP·무인증. 제품은 HTTPS 서버로 교체한다.
 */
class LocalServer(private val engines: Map<String, MeeosEngine>, private val page: ByteArray, private val port: Int = 8790,
                  private val link: LinkHub? = null, private val linkPage: ByteArray? = null, private val worldPage: ByteArray? = null,
                  /** 앱 에셋 읽기(경로 → 바이트, 없으면 null). /models/<이름>.glb 3D 모델에 쓴다 */
                  private val assetReader: ((String) -> ByteArray?)? = null,
                  /** /api/latest(가장 최근 세션 id)를 열지 — 시연 중계(pt_relay.py)만 쓰므로 시연 빌드에서만 켠다 */
                  private val allowLatest: Boolean = false) {

    /** 지역별 엔진(대치동·어바인·마컴·구루그람). 세션 id로 그 세션을 가진 엔진을 찾는다 */
    private val defaultEngine = engines["daechi"] ?: engines.values.first()
    private fun engineOf(sid: String?) = sid?.let { id -> engines.values.firstOrNull { synchronized(it) { it.sessions.containsKey(id) } } }
    private fun engineFor(query: Map<String, String>) = engineOf(query["sid"]) ?: engines[query["region"]] ?: defaultEngine

    // 처리 스레드는 필요한 만큼 늘리되 16개까지(접속을 마구 열어도 프로세스가 스레드로 가득 차지 않게). 16개가 모두 바쁘면 거절되어 접속을 닫는다
    private val pool = java.util.concurrent.ThreadPoolExecutor(0, 16, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue())
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        // adb forward 는 에뮬레이터 안의 루프백으로 들어오므로 루프백에만 연다
        val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        socket = ss
        // accept 루프는 처리 풀의 자리를 먹지 않게 따로 돈다
        Thread({
            // accept·execute에서 예외가 나도 루프를 끝내지 않는다(전에는 첫 예외에 멈춰 접속이 쌓였다). 닫힌 소켓일 때만 끝낸다
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) {
                    if (ss.isClosed) break
                    Log.w(TAG, "accept 실패, 계속", e); Thread.sleep(50); continue
                }
                runCatching { c.soTimeout = 5000 }          // 아무것도 보내지 않는 접속(브라우저 미리 연결 등)이 스레드를 붙잡지 않게
                try {
                    pool.execute { runCatching { handle(c) }.onFailure { Log.w(TAG, "요청 처리 실패", it) }; runCatching { c.close() } }
                } catch (e: Throwable) {
                    Log.w(TAG, "처리 스레드 실행 실패", e); runCatching { c.close() }
                }
            }
            Log.i(TAG, "내장 서버 종료")
        }, "meeos-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "내장 서버 시작 :$port · 지역 ${engines.keys} · 후보 ${engines.values.sumOf { it.cands.size }}곳")
    }

    fun stop() {
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        c.soTimeout = 5000
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS   // 한 바이트씩 흘리는 접속이 스레드를 붙들지 못하게 요청 전체 마감
        val input = BufferedInputStream(c.getInputStream())
        val requestLine = readLine(input, deadline) ?: return
        val (method, rawPath) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        var length = 0
        var chunked = false
        var headers = 0
        while (true) {
            val h = readLine(input, deadline) ?: break
            if (h.isEmpty()) break
            if (++headers > MAX_HEADERS) throw java.io.IOException("too many headers")
            val lower = h.lowercase()
            if (lower.startsWith("content-length:")) length = h.substringAfter(":").trim().toIntOrNull() ?: -1
            if (lower.startsWith("transfer-encoding:") && "chunked" in lower) chunked = true
        }
        val path = rawPath.substringBefore("?")
        if (ai.meeos.tago.BuildConfig.DEBUG) Log.d(TAG, "$method $path")
        val parts = path.split("/").filter { it.isNotEmpty() }
        fun decode(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)   // 잘못된 %-인코딩은 원문 그대로(접속을 끊지 않는다)
        val query = rawPath.substringAfter("?", "").split("&").filter { "=" in it }
            .associate { decode(it.substringBefore("=")) to decode(it.substringAfter("=")) }
        val out = c.getOutputStream()
        fun send(code: Int, bytes: ByteArray, type: String) {
            // 페이지와 API 가 같은 출처(127.0.0.1:포트)라 CORS 허용은 두지 않는다
            val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
        }
        fun json(code: Int, o: JSONObject) = send(code, o.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
        fun err(code: Int, m: String) = json(code, JSONObject().put("error", m))

        if (method == "OPTIONS") return send(200, ByteArray(0), "text/plain")
        if (chunked || length < 0 || length > MAX_BODY) return err(400, "body")
        if (parts == listOf("api", "session") && length > MAX_SESSION_BODY) return err(400, "body")   // 세션 생성 본문은 작다(가족 이름·종류·시각)
        val raw = if (length > 0) readN(input, length, deadline) else ByteArray(0)
        if (raw.size < length) return err(400, "short body")
        val body = String(raw, Charsets.UTF_8)
        try {
            route(method, path, parts, query, body, ::send, ::json, ::err)
        } catch (e: org.json.JSONException) {
            err(400, "bad request: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "처리 실패", e)
            err(500, "server error")
        }
    }

    private fun route(
        method: String, path: String, parts: List<String>, query: Map<String, String>, body: String,
        send: (Int, ByteArray, String) -> Unit, json: (Int, JSONObject) -> Unit, err: (Int, String) -> Unit,
    ) {
        // 도로 링크(차대차): /api/road/…·/api/link/… 는 LinkHub, /link/<그룹> 은 참가자 웹 페이지, /world 는 3D 관제 화면
        if (link != null && parts.size >= 2 && parts[0] == "api" && (parts[1] == "link" || parts[1] == "road")) {
            val b = if (method == "POST" && body.isNotBlank()) JSONObject(body) else JSONObject()
            val (code, o) = link.handle(method, parts, query, b)
            return json(code, o)
        }
        if (method == "GET" && parts.firstOrNull() == "link" && linkPage != null) return send(200, linkPage, "text/html; charset=utf-8")
        if (method == "GET" && parts.firstOrNull() == "world" && worldPage != null) return send(200, worldPage, "text/html; charset=utf-8")
        // 구급차 휴대폰(위치 신호): /ambulance?token=… → 에셋 link/ambulance.html (PC 서버와 같은 경로)
        if (method == "GET" && parts.firstOrNull() == "ambulance" && link != null) {
            val page = runCatching { assetReader?.invoke("link/ambulance.html") }.getOrNull()
            return if (page != null) send(200, page, "text/html; charset=utf-8") else err(404, "not found")
        }
        // 3D 관제 화면의 모델: /models/<이름>.glb → 에셋 link/models/<이름>.glb (이름은 소문자·숫자·하이픈만)
        if (method == "GET" && parts.firstOrNull() == "models") {
            val name = parts.getOrNull(1)
            // 모델 공용 텍스처: /models/Textures/<이름>.png
            val tex = parts.size == 3 && name == "Textures" && TEX_NAME.matches(parts[2])
            val rel = if (tex) "link/models/Textures/${parts[2]}" else if (parts.size == 2 && name != null && MODEL_NAME.matches(name)) "link/models/$name" else null
            val bytes = rel?.let { r -> runCatching { assetReader?.invoke(r) }.getOrNull() }
            return if (bytes != null) send(200, bytes, if (tex) "image/png" else "model/gltf-binary") else err(404, "not found")
        }
        if (method == "GET") {
            when {
                parts.size == 2 && parts[0] in setOf("m", "car", "f") -> send(200, page, "text/html; charset=utf-8")
                path == "/api/presets" -> json(200, engineFor(query).presets())
                path == "/api/map" -> json(200, engineFor(query).mapLayers())
                path == "/api/latest" && allowLatest -> engines.values.mapNotNull { e -> e.latestCreated() }.maxByOrNull { it.second }
                    ?.let { json(200, JSONObject().put("id", it.first)) } ?: err(404, "none")
                parts.size == 4 && parts[0] == "api" && parts[1] == "session" && parts[3] == "nearby" ->
                    engineOf(parts[2])?.nearby(parts[2], query["kind"] ?: "parking", query["from"])?.let { json(200, it) } ?: err(404, "not found")
                parts.size == 3 && parts[0] == "api" && parts[1] == "session" -> engineOf(parts[2])?.get(parts[2])?.let { json(200, it) } ?: err(404, "no session")
                else -> err(404, "not found")
            }
            return
        }
        if (method == "POST") {
            val b = if (body.isBlank()) JSONObject() else JSONObject(body)
            when {
                parts == listOf("api", "session") -> json(200, (engines[b.optString("region")] ?: defaultEngine).createSession(b))
                parts.size == 4 && parts[0] == "api" && parts[1] == "session" -> engineOf(parts[2])?.action(parts[2], parts[3], b)?.let { json(200, it) } ?: err(404, "not found")
                else -> err(404, "not found")
            }
            return
        }
        err(405, "method")
    }

    /** 한 줄(요청 줄·헤더). MAX_LINE 을 넘거나 마감을 지나면 끊는다(메모리·스레드를 붙드는 요청 방지) */
    private fun readLine(i: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            if (System.currentTimeMillis() > deadline) throw java.io.IOException("request too slow")
            val b = i.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buf.size() >= MAX_LINE) throw java.io.IOException("line too long")
            buf.write(b)
        }
    }

    /** n바이트를 읽는다. 스트림이 일찍 끝나면 읽은 만큼만 돌려준다 */
    private fun readN(i: InputStream, n: Int, deadline: Long): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            if (System.currentTimeMillis() > deadline) throw java.io.IOException("request too slow")
            val r = i.read(out, off, n - off)
            if (r <= 0) break
            off += r
        }
        return if (off == n) out else out.copyOf(off)
    }

    companion object {
        private const val TAG = "MeeosServer"
        private const val MAX_BODY = 64 * 1024
        private const val MAX_SESSION_BODY = 2 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 100
        private const val REQUEST_DEADLINE_MS = 10_000L
        private val MODEL_NAME = Regex("[a-z0-9-]+\\.glb")
        private val TEX_NAME = Regex("[a-z0-9-]+\\.png")
    }
}
