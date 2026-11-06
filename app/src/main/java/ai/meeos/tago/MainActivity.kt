package ai.meeos.tago

import ai.meeos.tago.app.Edition
import ai.meeos.tago.app.AppController
import ai.meeos.tago.app.AppLog
import ai.meeos.tago.app.DemoHooks
import ai.meeos.tago.app.Dialog
import ai.meeos.tago.app.GleoActions
import ai.meeos.tago.app.Region
import ai.meeos.tago.app.Services
import ai.meeos.tago.app.Texts
import ai.meeos.tago.car.CarSignals
import ai.meeos.tago.engine.LinkHub
import ai.meeos.tago.engine.LocalServer
import ai.meeos.tago.engine.MeeosEngine
import ai.meeos.tago.navi.NaviBridge
import ai.meeos.tago.net.ApiClient
import ai.meeos.tago.ui.TagoUiState
import ai.meeos.tago.ui.screens.TagoActions
import ai.meeos.tago.ui.screens.TagoScreen
import ai.meeos.tago.ui.theme.TagoTheme
import ai.meeos.tago.voice.VoiceAssistant
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Tago(타고) 차량 앱 — Android 생명주기와 인텐트만 맡는다.
 * 흐름은 app/AppController(픽업·함께 가기·응급·루틴), 화면은 ui/(Compose), 지도는 WebView(내장 서버 페이지).
 */
class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var services: Services
    private lateinit var app: AppController
    // 지도 WebView 는 웹 지도 페이지가 실제로 필요할 때만 만든다(네이티브 TMAP 지도·대기 화면에서는 WebView 와 렌더러 프로세스를 띄우지 않는다)
    private var webOrNull: WebView? = null
    private val web: WebView get() = webOrNull ?: createWebView().also { webOrNull = it }
    private val webGen = mutableStateOf(0)        // 렌더러가 죽어 WebView 를 새로 만들면 지도 슬롯도 새로 붙인다
    private val webNeeded = mutableStateOf(false)
    private var fgsOn = false
    // 시연·디버그: 앱 창을 다른 Pleos 디스플레이의 앱 창 크기(dp)로 줄여 그린다(adb ... --es frame 628x855). 에뮬레이터 화면 설정은 건드리지 않는다
    private val frameDp = mutableStateOf<Pair<Int, Int>?>(null)
    private var server: LocalServer? = null
    private var loadedUrl: String? = null
    private val uiState = mutableStateOf<TagoUiState?>(null)
    // 차 화면 지도: TMAP 앱키가 있으면 네이티브 TMAP SDK(순정 커서·티맵 노선), 없거나 인증에 실패하면 웹 지도(/car/)
    private var tmap: ai.meeos.tago.map.TmapCarMap? = null
    /** 지역 id → 거리 단위("km"·"mi"), 지역 프로필(assets regions/<id>.json units)에서 */
    private val unitsCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var tmapFailed = false
    private val nativeMap = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = AppLog(main, BuildConfig.DEMO_HOOKS)
        val region = Region.DAECHI
        val voice = VoiceAssistant(applicationContext, main, log)
        services = Services(
            main = main, io = io, log = log, texts = Texts(applicationContext, region),
            api = ApiClient(BuildConfig.SERVER_URL, io, main),
            car = CarSignals(this, main, log), voice = voice,
            navi = NaviBridge(applicationContext, main, log) { services.region },
            dialog = Dialog(voice) { app.render() },
        )
        if (BuildConfig.TMAP_KEY.isNotBlank()) {
            ai.meeos.tago.map.TmapCarMap.TMAP_KEY_FOR_ROUTE = BuildConfig.TMAP_KEY
            tmap = runCatching {
                ai.meeos.tago.map.TmapCarMap(this, BuildConfig.TMAP_KEY, BuildConfig.SERVER_URL, { services.log.d("[TMAP] $it") }) { tmapFailed = true; loadMap() }
            }.onFailure { services.log.d("TMAP 지도를 만들지 못해 웹 지도로: ${it.message}") }.getOrNull()
        }
        // 지역 프로필의 거리 단위(mi·km) — 도로 링크가 join 에 싣는다. startEmbeddedServer 가 io 스레드에서 프로필을 읽을 때 채우고,
        // 그 전에 불리면(PC 서버 모드) 한 번 읽어 둔다(프로필은 자산이라 바뀌지 않는다)
        services.regionUnits = { id -> unitsCache.getOrPut(id) {
            runCatching { org.json.JSONObject(assets.open("regions/$id.json").use { String(it.readBytes(), Charsets.UTF_8) }).optString("units", "km") }
                .getOrDefault("km").takeIf { it == "mi" } ?: "km"
        } }
        app = AppController(this, services, ::setMapUrl, ::dial)
        app.onUiState = { uiState.value = it; syncForegroundService() }
        // 말하는 동안 지도(WebView) 타이머를 멈추던 최적화는 뺐다: pauseTimers 는 파싱·fetch 콜백까지 멈춰 지도가 빈 채로 남는 일이 잦았고(실측),
        // 에뮬레이터 TTS 끊김은 그래도 남아 시연 영상은 PC 음성으로 재현한다. 지도는 다른 앱이 앞에 올 때(onPause)만 멈춘다

        if (BuildConfig.EMBEDDED_SERVER) startEmbeddedServer()
        services.car.start()
        services.navi.init()
        voice.init { services.region }             // app.start() 가 저장된 지역을 먼저 읽는다; 음성 서비스는 붙을 때 그 지역을 쓴다
        app.start()

        val actions = TagoActions(
            onMember = { name -> services.region.members.firstOrNull { it.name == name }?.let { app.startPickup(it) } },
            onRoadLink = { app.startRoad() },
            onAnswer = { app.answer(it) },
            onNavi = { app.pickup.routeToMeetingPoint() },
            onEmergency = { app.emergency.start() },
            onDial = { app.emergency.dialEmergency() },
            onEndEmergency = { app.emergency.end() },
            onRoadPrimary = { app.roadPrimary() },
            onEndRoad = { app.road.end() },
            onRegion = { app.setRegion(it) },
            onToggleDev = { app.toggleDevPanel() },
            onStudyOpen = { app.openStudy() },
            onStudyAge = { app.studyAge(it) },
            onStudyYears = { app.studyYears(it) },
            onStudyCar = { app.studyCar(it) },
            onStudyAgree = { app.studyAgree() },
            onStudyClose = { app.closeStudy() },
            onStudyExport = { exportStudy() },
            onStudyUpload = { app.studyUpload(it) },
            onStudyStop = { app.studyStop() },
            onChargeOpen = { app.road.openSurvey() },
            onChargePick = { app.road.surveyPick(it) },
            onChargeSkip = { app.road.surveySkip() },
            onChargeBack = { app.road.surveyBack() },
            onChargeClose = { app.road.surveyClose() },
        )
        app.requestGps = { runCatching { requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQ_STUDY_GPS) } }
        app.road.onCrashHelp = { app.emergency.start() }   // 충돌 뒤 무응답·[도움 필요] → 응급 카드(2차 사고 예방 모드)
        setContent {
            TagoTheme {
                val state = uiState.value
                // 가로↔세로 배치가 바뀌면 지도 슬롯이 다시 만들어진다 — 이미 붙어 있던 WebView 를 떼어 다시 넣는다
                // 차 화면 픽업 지도는 네이티브 TMAP(있을 때), 나머지(함께 가기 등)는 WebView
                val native = nativeMap.value
                val needWeb = webNeeded.value
                val gen = webGen.value
                val frame = frameDp.value
                if (state != null) Box(
                    Modifier.fillMaxSize().then(if (frame != null) Modifier.background(Color(0xFF7F7F7F)) else Modifier)
                ) {
                    Box(if (frame != null) Modifier.size(frame.first.dp, frame.second.dp).clipToBounds() else Modifier.fillMaxSize()) {
                        TagoScreen(state, actions) { mod ->
                            if (native || needWeb) {
                                val v: android.view.View = if (native) tmap!!.view else web
                                key(native, gen) { AndroidView(factory = { (v.parent as? android.view.ViewGroup)?.removeView(v); v }, modifier = mod) }
                            }
                        }
                    }
                }
            }
        }
        // 액티비티가 다시 만들어질 때(프로세스 복원 등) 처음 실행 인텐트를 또 처리하면 픽업이 저절로 새로 시작된다
        if (savedInstanceState == null) handleIntent(intent)
        // CCP Back 하드키: 앱을 끝내지 않고 뒤로 숨긴다(Pleos 규칙 — 끝내면 픽업 세션·내장 서버·아이 링크가 끊긴다)
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        requestRuntimePermissions()
    }

    /** 연구 기록: 화면을 누른 시각(경고를 본 단서). 누름을 가로채지 않고 기록만 한다 */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ::app.isInitialized) app.study.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STUDY_GPS) app.study.refreshSensors()
    }

    /** 연구 기록 내보내기: 쓰기 줄이 다 끝난 뒤 공유 시트(없으면 앱 외부 폴더에만 복사) */
    fun exportStudy() {
        val f = app.study.file ?: return
        app.study.afterWrites {
            if (!f.exists()) { app.studyToast(services.texts[R.string.toast_study_empty]); return@afterWrites }
            val (shared, copy) = ai.meeos.tago.study.StudyExport.share(this, f)
            services.log.d("연구 기록 내보내기: 공유=${shared} 복사=${copy != null}"); services.log.demo("연구 기록 파일 ${f.name} → ${copy?.path}")
            app.studyToast(if (shared) services.texts[R.string.toast_study_shared] else services.texts[R.string.toast_study_copied, "files/study/${f.name}"])
        }
    }

    /** 위험 권한은 설치만으로 주어지지 않는다(차 속도 CAR_SPEED, 사이렌 감지 마이크) — 없으면 한 번 묻는다 */
    private fun requestRuntimePermissions() {
        val want = buildList {
            add("android.car.permission.CAR_SPEED")
            if (Edition.roadLink) add(android.Manifest.permission.RECORD_AUDIO)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) runCatching { requestPermissions(want.toTypedArray(), REQ_RUNTIME) }
    }

    /** 포그라운드 서비스는 픽업·함께 가기가 진행 중일 때만(순정 내비가 앞에 와도 그 일을 이어가게). 아무 일도 없으면 내린다 */
    private fun syncForegroundService() {
        if (!::app.isInitialized) return
        val want = app.pickup.active || app.road.active
        if (want == fgsOn) return
        fgsOn = want
        if (want) runCatching { PickupService.start(this) }.onFailure { services.log.d("포그라운드 서비스 실패: ${it.message}"); fgsOn = false }
        else runCatching { PickupService.stop(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (BuildConfig.DEMO_HOOKS) {
            DemoHooks.apply(intent, app, this)
            intent.getStringExtra("gesture")?.let { g -> main.postDelayed({ tmap?.demoGesture(g) }, 500) }
            intent.getStringExtra("frame")?.let { f ->
                frameDp.value = Regex("""(\d{3,4})x(\d{3,4})""").matchEntire(f)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            }
        }
        GleoActions.handle(intent, app, this)
    }

    // 다른 앱(순정 내비 등)이 앞에 오면 지도(WebView)의 그리기·타이머를 멈춘다
    override fun onPause() {
        super.onPause()
        app.paused = true
        app.study.appVisible(false)
        webOrNull?.let { runCatching { it.onPause(); it.pauseTimers() } }
        tmap?.onPause()
    }

    override fun onResume() {
        super.onResume()
        app.paused = false
        app.study.appVisible(true)
        webOrNull?.let { runCatching { it.onResume(); it.resumeTimers() } }
        tmap?.onResume()
    }

    override fun onDestroy() {
        app.stop()
        tmap?.destroy()
        runCatching { server?.stop() }
        runCatching { PickupService.stop(this) }
        services.car.release()
        services.navi.release()
        services.voice.release()
        app.study.release()
        webOrNull?.let { w -> runCatching { (w.parent as? android.view.ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() } }
        webOrNull = null
        io.shutdown()
        super.onDestroy()
    }

    /** APK 하나로 시연: 만남 지점 엔진과 웹 페이지를 앱 안에서 띄운다 (PC 서버와 같은 API) */
    private fun startEmbeddedServer() {
        io.execute {
            runCatching {
                fun text(name: String) = assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
                // 지역 프로필마다 엔진 하나: 대치동(한국) + 현대차 해외 판매 상위 3개국(미국·인도·캐나다) 시연 동네
                val engines = Region.entries.map { it.id }.associateWith { id ->
                    val profile = text("regions/$id.json")
                    val pj = JSONObject(profile)
                    unitsCache.putIfAbsent(id, pj.optString("units", "km").takeIf { it == "mi" } ?: "km")   // 거리 단위는 여기(io)서 미리
                    MeeosEngine(text(pj.getString("map")), pj.optString("cctv").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { text(it) }.getOrNull() }, profile)
                }
                val page = assets.open("rider.html").use { it.readBytes() }
                // 함께 가기(차대차 링크) 엔진: 시연 경로 경부고속도로 하행. 픽업 APK에는 함께 가기 자산이 없다
                val link = if (Edition.roadLink) LinkHub(text("link/gyeongbu_route.json")) else null
                val linkPage = runCatching { assets.open("link/link.html").use { it.readBytes() } }.getOrNull()
                val worldPage = runCatching { assets.open("link/world.html").use { it.readBytes() } }.getOrNull()
                server = LocalServer(engines, page, link = link, linkPage = linkPage, worldPage = worldPage,
                    assetReader = { n -> runCatching { assets.open(n).use { it.readBytes() } }.getOrNull() },
                    allowLatest = BuildConfig.DEMO_HOOKS).also { it.start() }
                runOnUiThread { app.serverReady() }   // 대기 화면 지도(길벗: 경로 미리보기)는 서버가 뜬 뒤에 연다
                // 포그라운드 서비스는 픽업·함께 가기가 시작될 때 붙인다(syncForegroundService)
            }.onFailure { e -> services.log.d("내장 서버 시작 실패: ${e.message}") }
        }
    }

    /** 지도 WebView. 시연 서버 페이지만 연다 — 다른 주소로의 이동은 막고, 파일·콘텐츠 접근은 끈다 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        // Compose AndroidView 안에서 WebView 가 부모 크기를 채우도록(없으면 Chromium 레이아웃 뷰포트가 작게 잡힐 수 있다)
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true          // Leaflet 지도
        // 야간 모드에서 WebView 가 페이지 색을 뒤집는(force dark) 바람에 이미 어두운 지도가 밝게 나왔다(실측) — 끄고 페이지가 ?dark=1 로 직접 어둡게 한다
        if (android.os.Build.VERSION.SDK_INT >= 29) @Suppress("DEPRECATION") { settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF }
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return !url.startsWith(BuildConfig.SERVER_URL + "/")   // 서버 페이지가 아니면 열지 않는다
            }

            // 렌더러 프로세스가 죽거나 시스템이 회수해도 앱(내장 서버·픽업·아이 링크)은 살린다: 이 WebView 를 버리고 새로 만든다
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                services.log.d("지도 렌더러 종료(${if (detail.didCrash()) "오류" else "메모리 회수"}) — 지도를 다시 연다")
                runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
                if (webOrNull === view) webOrNull = null
                loadedUrl = null
                main.post { webGen.value++; loadMap() }
                return true
            }
        }
        // 지도 페이지의 JS 오류·로그를 앱 로그로(디버그 빌드만) — 지도가 비어 보일 때 원인을 바로 본다
        if (BuildConfig.DEBUG) webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                services.log.d("[지도] ${m.messageLevel()} ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})"); return true
            }
        }
        loadBlank(this)
    }

    private fun loadBlank(w: WebView = web) {
        w.loadDataWithBaseURL(null, "<html><body style='margin:0;background:transparent'></body></html>", "text/html", "UTF-8", null)
    }

    /** 컨트롤러가 정한 지도 페이지. 주/야간은 URL(dark=1)로 페이지에 알린다 — WebView 의 prefers-color-scheme 는 들쭉날쭉했다(실측) */
    private fun setMapUrl(url: String?) {
        mapBaseUrl = url
        loadMap()
    }

    private var mapBaseUrl: String? = null

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun loadMap() {
        // 픽업 차 화면(/car/<세션>)은 네이티브 TMAP 지도로: WebView 는 비워 두고(지도 페이지가 돌지 않게) 세션만 넘긴다
        val carSid = mapBaseUrl?.let { Regex("/car/([0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }
        val useNative = carSid != null && tmap != null && !tmapFailed
        nativeMap.value = useNative
        tmap?.show(if (useNative) carSid else null, isNight())
        webNeeded.value = !useNative && mapBaseUrl != null
        if (useNative) {
            if (loadedUrl != null) { loadedUrl = null; webOrNull?.let { loadBlank(it) } }
            return
        }
        val target = mapBaseUrl?.let { it + (if ('?' in it) "&" else "?") + "dark=" + (if (isNight()) "1" else "0") }
        if (target == loadedUrl) return
        loadedUrl = target
        if (target == null) webOrNull?.let { loadBlank(it) } else web.loadUrl(target)
    }

    /** 주/야간 전환(configChanges="uiMode"): Compose 는 스스로 다시 그리고, 지도 페이지만 테마에 맞춰 다시 연다 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        loadMap()
    }

    /** 전화 앱의 다이얼 화면만 연다(자동 발신 안 함). 열 수 없으면 false */
    private fun dial(number: String): Boolean {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        if (intent.resolveActivity(packageManager) == null) return false
        startActivity(intent)
        return true
    }

    private companion object {
        const val REQ_RUNTIME = 7      // 권한 요청 코드: 차 속도·마이크
        const val REQ_STUDY_GPS = 8    // 연구 기록: 차속 신호 없는 차의 GPS 속도
    }
}
