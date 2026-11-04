package ai.meeos.tago.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.graphics.Point
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.TextView
import com.skt.tmap.TMapInsets
import com.skt.tmap.TMapPoint
import com.skt.tmap.TMapView
import com.skt.tmap.overlay.TMapCircle
import com.skt.tmap.overlay.TMapMarkerItem
import com.skt.tmap.overlay.TMapPolyLine
import com.skt.tmap.overlay.TMapTrafficLine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 차 화면 지도 — 네이티브 TMAP Android SDK(TMapView). 웹 지도(server/rider.html 의 /car/)와 같은 내용을 그린다.
 *
 * - 내 차: SDK 의 현재 위치 아이콘(순정 커서: 흰 원 + 파란 화살표, VSM SDK 의 location_marker). 위치에 차 진행 방향을 실어 돌린다
 * - 노선: SDK 의 TMapTrafficLine(티맵과 같은 디자인 — 교통 색 + 진행 방향 화살표). 데이터는 티맵 자동차 경로 API(실시간 교통),
 *   못 받으면 엔진이 도로망으로 찾은 경로(carPath)를 '정보 없음' 색으로
 * - 우리 표시(docs/ui-design.md §9): 만날 곳 원형 퍽 + 라벨, 대안, 제외 ×, 경유, 아이(상대 = ink), 걷는 길 초록 점선,
 *   정차 금지 반경(만날 곳 70m 안, 확정 뒤엔 없음), 단속 CCTV(100m 안), 운영 중 버스전용차로, 범례(제안 중에만)
 *
 * 데이터는 웹 페이지처럼 앱 내장 서버 API(/api/map, /api/session)를 정차 중 2초·주행 중 4초마다 읽는다. TMapView 는 메인 스레드에서만 만진다.
 * 인증에 실패하면 onFailed 로 알려 웹 지도로 돌아가게 한다.
 */
class TmapCarMap(
    private val ctx: Context,
    appKey: String,
    private val server: String,
    private val log: (String) -> Unit,
    private val onFailed: () -> Unit,
) {
    /**
     * 두 손가락 핀치는 SDK 대신 앱이 받는다(한 손가락 끌기는 SDK 그대로). 에뮬레이터의 핀치(Ctrl+드래그)는 두 번째 손가락을 화면 전체(2560)의
     * 가운데 대칭에 두어 두 손가락의 중심이 지도 밖(주행뷰 쪽)에 떨어지고, SDK 는 그 바깥 점을 기준으로 확대해 지도가 엉뚱한 곳으로 밀렸다(2026-10-31 실측).
     * 앱이 받으면 기준점을 고를 수 있다: 손가락 중심이 지도 안이면 그 점(실차), 밖이면 지도 가운데(에뮬레이터).
     */
    val view: FrameLayout = object : FrameLayout(ctx) {
        // 감지기 하나가 두 손가락 핀치와 "두 번 탭 뒤 누른 채 위아래 드래그"(퀵 스케일: 아래 = 확대, 위 = 축소 — 안드로이드 지도 표준, 마우스 하나로 되는 유일한 확대 제스처)를 판단한다
        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { pinch(d.scaleFactor, d.focusX, d.focusY); return true }
        }).apply { isQuickScaleEnabled = true }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (TOUCH_LOG && ev.actionMasked != MotionEvent.ACTION_MOVE || (TOUCH_LOG && ev.pointerCount > 1 && (ev.eventTime / 100) % 3 == 0L))
                log("터치 ${ev.actionMasked}/${ev.actionIndex} n=${ev.pointerCount} " + (0 until ev.pointerCount).joinToString(" ") { "${ev.getPointerId(it)}:${ev.getX(it).roundToInt()},${ev.getY(it).roundToInt()}" })
            if (ready && sid != null && !onControl(ev)) scaler.onTouchEvent(ev)   // 모든 이벤트를 먼저 감지기에(아래 intercept 가 isInProgress 를 본다)
            return super.dispatchTouchEvent(ev)
        }
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) onUserTouch()
            // 두 번째 손가락이 닿거나 확대 제스처가 시작되면 SDK 에서 가져온다(SDK 는 취소를 받아 끌기를 멈춘다). 그 전까지 한 손가락 끌기·탭은 SDK 그대로
            return ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN || scaler.isInProgress
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean { onUserTouch(); return true }
    }.apply { layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }
    private val map = TMapView(ctx)
    private val legend = TextView(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val net = HandlerThread("tago-tmap").apply { start() }
    private val netH = Handler(net.looper)
    private val dp = ctx.resources.displayMetrics.density * WEB_SCALE   // 우리 표시(퍽·라벨·범례·여백)는 웹 지도와 같은 크기: 차 WebView 는 CSS 1px = 1.375 px(실측)

    private var ready = false
    /** VSM 엔진(TMapView 비공개 필드, R8 이 이름을 지킨다): 핀치 배율·POI 설정에 쓴다 */
    private val engine: com.skt.tmap.vsm.map.MapEngine? by lazy {
        runCatching { TMapView::class.java.getDeclaredField("vsmMapEngine").apply { isAccessible = true }.get(map) as com.skt.tmap.vsm.map.MapEngine }
            .onFailure { log("VSM 엔진 접근 실패(핀치·POI 설정 없음): ${it.message}") }.getOrNull()
    }
    private var failed = false
    private var destroyed = false
    // 운전자가 지도를 만지면 자동 맞춤을 잠시 멈춘다(손으로 본 곳을 2~4 초마다 되돌리지 않게). 다시 맞추기 버튼으로 바로 돌아온다
    private var userAt = 0L
    private var moving = false
    // 지도 조작 버튼은 순정 내비 배치를 따른다(에뮬레이터 실측 2026-11-01): 오른쪽 아래 정사각 버튼 세로 열, 가운데 아래 "내 위치로" 글자 버튼
    private val recenter = TextView(ctx)         // 가운데 아래 "다시 맞추기"(아이콘 + 글, 80 높이) — 손으로 만진 뒤에만
    // 줌 슬라이더(오른쪽 아래): 에뮬레이터 핀치(Ctrl+드래그)는 두 번째 손가락이 화면 중심 대칭 = 다른 창(주행뷰·상태 패널)에 떨어져 지도에는
    // 손가락 하나만 온다(실측) — 핀치가 닿지 않으니 지도 앱식 막대(위로 올리면 확대)가 있어야 한다. 실차에서도 주행 중 한 손 조작에 맞다
    private val zoom = ZoomSlider()
    private var sdkZoom = 16f                    // SDK 가 알려 준 현재 줌(소수)
    private var paused = false
    private var polling = false
    private var busy = false
    private var sid: String? = null
    private var dark = false
    private var mapData: JSONObject? = null
    private var lang = "ko"
    private var lastState: JSONObject? = null

    // 그린 것의 서명(같으면 다시 그리지 않는다)
    private val markerSig = HashMap<String, String>()
    private var hazKey: String? = null
    private var routeKey: String? = null
    private var walkKey: String? = null
    private var busKey: String? = null
    private var fitKey: String? = null
    private var legendShown: Boolean? = null
    private val bitmaps = HashMap<String, Bitmap>()

    // 티맵 경로(실시간 교통): 목적지·경유지가 바뀔 때와 60초마다만 부른다(무료 한도 일 1,000건). 저장하지 않는다(약관: 24시간 넘게 쓰지 않음)
    private var tmKey: String? = null
    private var tmAt = 0L
    private var tmBusy = false
    private var tmRetryAt = 0L
    private var tmLines: List<Pair<Int, List<LL>>>? = null

    init {
        view.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        legend.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * dp)
            setPadding(px(12), px(8), px(12), px(8))
            visibility = android.view.View.GONE
        }
        view.addView(legend, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(px(10), px(10), 0, 0) })   // 왼쪽 위(아래는 로고·다시 맞추기·슬라이더)
        val d = ctx.resources.displayMetrics.density
        // 다시 맞추기: 순정 "⊕ 내 위치로"처럼 가운데 아래 글자 버튼(80 높이, 아이콘 32 + Label M bold), 직각, 누르면 95%
        recenter.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * d); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
            setPadding((32 * d).roundToInt(), 0, (32 * d).roundToInt(), 0); compoundDrawablePadding = (12 * d).roundToInt()
            contentDescription = "지도 다시 맞추기"
            visibility = android.view.View.GONE
            setOnTouchListener { v, e ->
                when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { v.scaleX = 0.95f; v.scaleY = 0.95f }; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.scaleX = 1f; v.scaleY = 1f } }
                false
            }
            setOnClickListener { userAt = 0L; fitKey = null; styleRecenter(); lastState?.let { if (ready) safeDraw(it) } }
        }
        view.addView(recenter, FrameLayout.LayoutParams(WRAP, (80 * d).roundToInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, (24 * d).roundToInt()) })
        // 줌 슬라이더: 오른쪽 아래(순정 내비의 볼륨·설정 열 자리), 여백 24
        zoom.visibility = android.view.View.GONE
        view.addView(zoom, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (24 * d).roundToInt(), (24 * d).roundToInt()) })
        map.setOnApiKeyListenerCallback(object : TMapView.OnApiKeyListenerCallback {
            override fun onSKTMapApikeySucceed() { log("TMAP 지도 인증 성공") }
            override fun onSKTMapApikeyFailed(msg: String?) { main.post { if (destroyed || failed) return@post; log("TMAP 지도 인증 실패: $msg — 웹 지도로"); failed = true; onFailed() } }
        })
        map.setOnMapReadyListener {
            main.post {
                if (destroyed) return@post
                ready = true
                map.setVisibleLogo(true)
                map.setMapType(if (dark) TMapView.MapType.NIGHT else TMapView.MapType.DEFAULT)
                tidyMap()
                map.setSightVisible(false)
                map.setCompassMode(false)            // 지도는 정북 위, 커서만 진행 방향으로 돈다
                runCatching { map.setRotateEnable(false); map.setTiltEnable(false) }   // 두 손가락 회전·기울임은 끈다(정북 고정 지도가 돌아가 버리지 않게)
                runCatching { map.setUserScrollZoomEnable(false) }                     // SDK 핀치는 끈다 — 두 손가락은 view 가 받아 pinch() 로
                runCatching { map.setTMapLogoPosition(TMapView.TMapLogoPosition.POSITION_BOTTOM_LEFT) }   // 오른쪽 아래는 슬라이더 자리
                runCatching { engine?.let { e -> zoom.zMin = max(10, e.minZoomLevel).toFloat(); zoom.zMax = kotlin.math.min(19, e.maxZoomLevel).toFloat(); log("줌 범위 ${e.minZoomLevel}~${e.maxZoomLevel} → ${zoom.zMin}~${zoom.zMax}") } }
                map.setOnZoomChangedListener { level -> main.post { sdkZoom = level.toFloat(); zoom.follow(sdkZoom) } }
                syncZoom()
                log("TMAP 지도 준비됨")
                lastState?.let { safeDraw(it) }
                kick()
            }
        }
        map.setSKTMapApiKey(appKey)
    }

    // ---------------------------------------------------------------- 생명주기

    /** 보일 세션(null = 숨김)과 주·야간. 세션이 바뀌면 그린 것을 모두 지운다 */
    // 앱키 확인이 네트워크 오류로 끝나면 SDK 가 성공·실패 콜백을 둘 다 부르지 않고, 지도 설정을 못 받으면 준비 신호도 오지 않는다(오프라인 시동).
    // 지도는 화면에 붙어야 준비되므로, 처음 보일 때부터 15초 안에 준비되지 않으면 웹 지도로
    private var watchArmed = false
    private val readyWatch = Runnable { if (!ready && !failed && !destroyed) { log("TMAP 지도가 15초 안에 준비되지 않아 웹 지도로"); failed = true; onFailed() } }

    fun show(sessionId: String?, night: Boolean) {
        if (sessionId != null && !ready && !failed && !watchArmed) { watchArmed = true; main.postDelayed(readyWatch, 15_000) }
        if (sessionId != sid) {
            sid = sessionId
            mapData = null; lastState = null
            clearAll()
        }
        setDark(night)
        kick()
    }

    fun setDark(night: Boolean) {
        if (night == dark) return
        dark = night
        bitmaps.clear(); markerSig.keys.filter { !it.startsWith("_") }.forEach { map.removeTMapMarkerItem(it) }; runCatching { map.removeTMapMarkerItem("car") }; markerSig.clear()
        legendShown = null
        // 테마를 바꾸면 스타일을 새로 읽으니 POI 설정을 다시(스타일이 늦게 붙는 경우까지 두 번)
        if (ready) { map.setMapType(if (dark) TMapView.MapType.NIGHT else TMapView.MapType.DEFAULT); main.postDelayed({ tidyMap() }, 500); main.postDelayed({ tidyMap() }, 2500) }
        syncZoom()
        styleRecenter()
        lastState?.let { if (ready) safeDraw(it) }
    }

    fun onResume() { paused = false; runCatching { map.onResume() }; kick() }
    fun onPause() { paused = true; runCatching { map.onPause() } }
    fun destroy() { destroyed = true; paused = true; main.removeCallbacksAndMessages(null); runCatching { map.onDestroy() }; net.quitSafely() }

    /** 그리기 예외가 앱을 죽이지 않게(SDK 엔진 상태가 바뀌는 순간 등) */
    private fun safeDraw(st: JSONObject) {
        if (destroyed) return
        runCatching { draw(st) }.onFailure { log("지도 그리기 실패(다음 갱신에 다시): ${it.message}") }
    }

    /** 지도 가운데 기준으로 배율 적용(슬라이더·+·−). 손 조작으로 쳐서 자동 맞춤을 20초 멈추고 다시 맞추기 버튼을 띄운다 */
    private fun zoomBy(factor: Float, animate: Boolean) {
        if (!ready || moving || destroyed || sid == null) return
        val w = view.width; val h = view.height
        if (w == 0 || h == 0) return
        onUserTouch()
        runCatching { engine?.setMapScale(factor, Point(w / 2, h / 2), animate) }.onFailure { log("확대·축소 실패: ${it.message}") }
    }

    /** 줌 슬라이더는 지도가 준비되고 세션이 있을 때, 달리는 중이 아닐 때만. 테마가 바뀌면 다시 그린다 */
    private fun syncZoom() {
        val on = ready && sid != null && !moving && !destroyed
        zoom.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        if (on) zoom.invalidate()
    }

    /** 터치가 조작 버튼(슬라이더·다시 맞추기) 위인지 — 그 터치는 핀치·두 번 탭 감지기에 넣지 않는다 */
    private fun onControl(ev: MotionEvent): Boolean = listOf(zoom, recenter).any { v ->
        v.visibility == android.view.View.VISIBLE && Rect().also(v::getHitRect).contains(ev.x.roundToInt(), ev.y.roundToInt())
    }

    /**
     * 줌 슬라이더(지도 앱의 세로 막대, 폭 80): 위 + 상자(80), 트랙(240) 위의 손잡이 — 위로 올리면 확대·내리면 축소 —, 아래 − 상자(80).
     * 순정 룩(직각·흑백·1px 선·손잡이는 ink 채움). 손잡이 자리는 줌 레벨(zMin~zMax)에 비례하고, SDK 줌이 바뀌면(자동 맞춤·핀치·두 번 탭) 따라온다.
     * + / − 는 한 레벨씩(애니메이션), 트랙은 누른 자리로 바로 가고 끄는 대로 연속으로 바뀐다
     */
    private inner class ZoomSlider : android.view.View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val box = 80 * d; private val gap = 12 * d; private val track = 240 * d; private val knobH = 40 * d
        var zMin = 10f; var zMax = 19f   // 10 = 도시, 19 = 골목(전국까지 빼면 만날 곳 지도에 쓸모없다)
        private var z = 16f
        private var dragging = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 44 * d }
        private val trackTop get() = box + gap
        private val trackBottom get() = box + gap + track
        init { contentDescription = "지도 확대·축소" }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(box.roundToInt(), (box * 2 + gap * 2 + track).roundToInt())
        override fun onDraw(c: Canvas) {
            val fill = if (dark) Color.parseColor("#1C1C1C") else Color.WHITE
            val line = if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#D4D4D4")
            val ink = if (dark) Color.parseColor("#F2F2F2") else Color.BLACK
            val sw = max(1f, d)
            fun panel(y0: Float, y1: Float) {
                p.style = Paint.Style.FILL; p.color = fill; c.drawRect(0f, y0, box, y1, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = line; c.drawRect(sw / 2, y0 + sw / 2, box - sw / 2, y1 - sw / 2, p)
            }
            panel(0f, box); panel(trackTop, trackBottom); panel(trackBottom + gap, trackBottom + gap + box)
            t.color = ink
            c.drawText("+", box / 2, box / 2 - (t.descent() + t.ascent()) / 2, t)
            c.drawText("\u2212", box / 2, trackBottom + gap + box / 2 - (t.descent() + t.ascent()) / 2, t)
            // 트랙 가운데 세로 선 + 손잡이
            p.style = Paint.Style.FILL; p.color = line; c.drawRect(box / 2 - sw, trackTop + knobH / 2, box / 2 + sw, trackBottom - knobH / 2, p)
            val ky = trackBottom - knobH / 2 - ((z - zMin) / (zMax - zMin)).coerceIn(0f, 1f) * (track - knobH)
            p.color = ink; c.drawRect(8 * d, ky - knobH / 2, box - 8 * d, ky + knobH / 2, p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!ready || moving || sid == null) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true); onUserTouch()
                    when { e.y < trackTop -> step(1f); e.y > trackBottom -> step(-1f); else -> { dragging = true; dragTo(e.y) } }
                }
                MotionEvent.ACTION_MOVE -> if (dragging) dragTo(e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; follow(sdkZoom) }
            }
            return true
        }
        private fun dragTo(y: Float) {
            val f = ((trackBottom - knobH / 2 - y) / (track - knobH)).coerceIn(0f, 1f)
            setZoom(zMin + f * (zMax - zMin), animate = false)
        }
        private fun step(dz: Float) = setZoom((z + dz).roundToInt().toFloat().coerceIn(zMin, zMax), animate = true)
        private fun setZoom(nz: Float, animate: Boolean) {
            if (kotlin.math.abs(nz - z) < 0.02f) return
            val factor = Math.pow(2.0, (nz - z).toDouble()).toFloat()
            z = nz; invalidate(); zoomBy(factor, animate)
        }
        /** SDK 줌이 바뀌었을 때: 끄는 중이 아니면 손잡이를 맞춘다 */
        fun follow(level: Float) { if (!dragging) { z = level.coerceIn(zMin, zMax); invalidate() } }
    }

    private fun onUserTouch() {
        if (!ready || sid == null) return
        userAt = System.currentTimeMillis()
        if (recenter.visibility != android.view.View.VISIBLE) { recenter.visibility = android.view.View.VISIBLE; styleRecenter() }
    }

    private fun userBrowsing() = userAt > 0 && System.currentTimeMillis() - userAt < 20_000

    /**
     * 시험 훅(디버그·시연 빌드, MainActivity --es gesture in|out|drag): 에뮬레이터 핀치를 그대로 흉내 낸다 —
     * 첫 손가락은 지도 가운데, 두 번째 손가락은 "화면 전체" 중심의 대칭점(지도 밖). in = 벌리기, out = 오므리기, drag = 한 손가락 위로 끌기
     */
    fun demoGesture(kind: String) {
        val w = view.width; val h = view.height
        if (w == 0 || h == 0) return
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val dm = ctx.resources.displayMetrics
        val scx = dm.widthPixels / 2f - loc[0]; val scy = dm.heightPixels / 2f - loc[1]   // 화면 중심(지도 좌표계)
        val ax0 = w / 2f; val ay0 = h / 2f
        val steps = 20; val dx = if (kind == "in") 150f else if (kind == "out") -150f else 0f; val dy = if (kind == "drag") -200f else 0f
        val two = kind != "drag"
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, t: Long, ax: Float, ay: Float): MotionEvent {
            val n = if (two) 2 else 1
            val props = Array(n) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(n) { MotionEvent.PointerCoords().apply { if (it == 0) { x = ax; y = ay } else { x = 2 * scx - ax; y = 2 * scy - ay }; pressure = 1f; size = 1f } }
            return MotionEvent.obtain(t0, t, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val seq = ArrayList<MotionEvent>()
        if (kind == "dtap" || kind == "dtapup") {   // 두 번 탭 뒤 누른 채 세로 드래그(아래 = 확대, 위 = 축소): 탭(60ms) → 200ms 뒤 다시 눌러 끌기
            val ddy = if (kind == "dtap") 250f else -250f
            seq += MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, ax0, ay0, 0)
            seq += MotionEvent.obtain(t0, t0 + 60, MotionEvent.ACTION_UP, ax0, ay0, 0)
            val t1 = t0 + 200
            seq += MotionEvent.obtain(t1, t1, MotionEvent.ACTION_DOWN, ax0, ay0, 0)
            for (i in 1..steps) seq += MotionEvent.obtain(t1, t1 + i * 20L, MotionEvent.ACTION_MOVE, ax0, ay0 + ddy * i / steps, 0)
            seq += MotionEvent.obtain(t1, t1 + steps * 20L + 20, MotionEvent.ACTION_UP, ax0, ay0 + ddy, 0)
            seq.forEach { e -> main.postDelayed({ view.dispatchTouchEvent(e); e.recycle() }, e.eventTime - t0) }
            log("시험 제스처 $kind"); return
        }
        // 첫 손가락만으로 DOWN
        seq += MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, ax0, ay0, 0)
        if (two) seq += ev(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t0 + 10, ax0, ay0)
        for (i in 1..steps) seq += ev(MotionEvent.ACTION_MOVE, t0 + 10 + i * 20L, ax0 + dx * i / steps, ay0 + dy * i / steps)
        val tEnd = t0 + 10 + steps * 20L + 20
        if (two) seq += ev(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), tEnd, ax0 + dx, ay0 + dy)
        seq += MotionEvent.obtain(t0, tEnd + 10, MotionEvent.ACTION_UP, ax0 + dx, ay0 + dy, 0)
        seq.forEachIndexed { i, e -> main.postDelayed({ view.dispatchTouchEvent(e); e.recycle() }, i * 20L) }
        log("시험 제스처 $kind: 지도 ${w}×${h}, 화면 중심(지도 좌표) ${scx.roundToInt()},${scy.roundToInt()}")
    }

    /** 핀치 한 틱: 배율(1.0 근처)을 기준점에 적용. 기준점은 손가락 중심이 지도 안(가장자리 10% 안쪽)이면 그 점, 아니면 지도 가운데 */
    private fun pinch(factor: Float, fx: Float, fy: Float) {
        if (!ready || moving || destroyed || sid == null) return
        val w = view.width; val h = view.height
        if (w == 0 || h == 0) return
        val inside = fx > w * 0.1f && fx < w * 0.9f && fy > h * 0.1f && fy < h * 0.9f
        val at = if (inside) Point(fx.roundToInt(), fy.roundToInt()) else Point(w / 2, h / 2)
        if (TOUCH_LOG) log("핀치 ×%.3f @%d,%d(%s) 줌 %d".format(factor, at.x, at.y, if (inside) "손가락" else "가운데", map.zoomLevel))
        runCatching { engine?.setMapScale(factor, at, false) }.onFailure { log("핀치 실패: ${it.message}") }
    }

    private fun styleRecenter() {
        if (userAt == 0L) { recenter.visibility = android.view.View.GONE; return }
        val d = ctx.resources.displayMetrics.density
        val ink = if (dark) Color.parseColor("#F2F2F2") else Color.BLACK
        recenter.background = GradientDrawable().apply {
            setColor(if (dark) Color.parseColor("#1C1C1C") else Color.WHITE)
            setStroke(max(1, d.roundToInt()), if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#D4D4D4"))
        }
        recenter.setTextColor(ink)
        recenter.text = if (lang == "en") "Re-center" else "다시 맞추기"
        val icon = ctx.resources.getIdentifier("ic_navigation", "drawable", ctx.packageName).takeIf { it != 0 }?.let { ctx.getDrawable(it)?.mutate() }
        icon?.apply { setBounds(0, 0, (32 * d).roundToInt(), (32 * d).roundToInt()); setTint(ink) }
        recenter.setCompoundDrawables(icon, null, null, null)
    }

    private fun clearAll() {
        hazKey = null; routeKey = null; walkKey = null; busKey = null; fitKey = null; legendShown = null
        tmKey = null; tmLines = null; tmAt = 0L
        if (!ready) return
        runCatching {
            map.removeAllTMapMarkerItem(); map.removeAllTMapCircle(); map.removeAllTMapPolyLine(); map.removeAllTMapTrafficLine()
        }
        markerSig.clear()
        legend.visibility = android.view.View.GONE
    }

    // ---------------------------------------------------------------- 데이터 읽기

    private fun kick() {
        if (polling) return
        polling = true
        main.post(pollTick)
    }

    private val pollTick = object : Runnable {
        override fun run() {
            val s = sid
            if (s == null || paused || failed) { polling = false; return }
            if (!busy) {
                busy = true
                netH.post {
                    val md = mapData ?: runCatching { JSONObject(get("$server/api/map?sid=$s")) }.getOrNull()
                    val st = runCatching { JSONObject(get("$server/api/session/$s")) }.getOrNull()
                    main.post {
                        busy = false
                        if (s != sid || destroyed) return@post
                        if (md != null && mapData == null) { mapData = md; lang = md.optJSONObject("region")?.optString("lang", "ko") ?: "ko" }
                        if (st != null) { lastState = st; if (ready && mapData != null) safeDraw(st) }
                    }
                }
            }
            val nowMoving = (lastState?.optJSONObject("car")?.optDouble("speed", 0.0) ?: 0.0) > 0.5
            if (nowMoving != moving && ready) {
                // 주행 중에는 지도를 손으로 움직이지 않는다(보기 전용, 자동 맞춤이 따라간다). 멈추면 다시 연다
                moving = nowMoving
                runCatching { map.setUserScrollMoveEnable(!moving) }   // 핀치는 pinch() 가 moving 을 본다
                syncZoom()
                if (moving) { userAt = 0L; styleRecenter() }
            }
            main.postDelayed(this, if (nowMoving) 4000 else 2000)
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- 그리기

    private fun t(ko: String, en: String) = if (lang == "en") en else ko

    private data class Pick(val id: String, val ll: LL, val name: String, val icon: String, val cls: String, val label: String)

    private fun draw(s: JSONObject) {
        val md = mapData ?: return
        val status = s.optString("status")
        val confirmed = s.optString("confirmed").takeIf { it.isNotEmpty() && it != "null" }
        val prop = s.optJSONObject("proposal")
        val tgt = s.optJSONObject("target")
        val boardedLike = status == "boarded" || status == "dropped"
        val arrivedLike = status == "arrived" || boardedLike

        // 만날 곳 후보: 확정 뒤엔 확정 하나, 제안 중엔 추천·대안
        val picks = ArrayList<Pick>()
        if (confirmed != null && tgt != null) ll(tgt.opt("curb"))?.let { picks += Pick(tgt.optString("id"), it, tgt.optString("name"), "pin", "best", t("만남 확정 · ", "Meeting · ") + tgt.optString("name")) }
        else {
            prop?.optJSONObject("best")?.let { b -> ll(b.opt("curb"))?.let { picks += Pick(b.optString("id"), it, b.optString("name"), b.optString("icon", "pin"), "best", t("추천 · ", "Suggested · ") + b.optString("name")) } }
            prop?.optJSONObject("alt")?.let { a -> ll(a.opt("curb"))?.let { picks += Pick(a.optString("id"), it, a.optString("name"), a.optString("icon", "pin"), "alt", t("대안 · ", "Alt · ") + a.optString("name")) } }
        }
        val target = picks.firstOrNull()

        // 차: 도착·탑승 뒤에는 만날 곳에 서 있다
        val cp = s.optJSONObject("carPos")
        var carLL = LL(cp?.optDouble("lat") ?: md.centerLL().lat, cp?.optDouble("lon") ?: md.centerLL().lon)
        if (arrivedLike && target != null) carLL = target.ll
        val heading = cp?.optDouble("heading", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        // 내 차 = SDK 에 든 순정 커서 그림(VSM SDK location_marker: 흰 원 + 파란 화살표)을 마커로, 진행 방향으로 돌린다.
        // SDK 의 현재 위치 레이어(setIconVisibility)는 기본 그림이 작은 파란 공이고 회전을 쓰지 않아(실측) 쓰지 않는다
        val car = Mk("car", carLL, cursor(), 0.5f, 0.5f, 3000f, rotation = heading.toFloat())   // 엔진 마커 회전 = 정북에서 시계 방향 각도(실측: 0 위 · 90 오른쪽 · 180 아래 · 270 왼쪽)

        val markers = ArrayList<Mk>()
        // 제외 지점(만날 곳 120m 안, 가까운 3곳) — 제안 중에만
        if (confirmed == null) {
            val ex = prop?.optJSONArray("excluded")
            if (ex != null) (0 until ex.length()).map { ex.getJSONObject(it) }.filter { it.optDouble("dist", 1e9) <= 120 }.sortedBy { it.optDouble("dist") }.take(3).forEach { e ->
                ll(e.opt("curb"))?.let { markers += Mk("ex:${e.optString("id")}", it, pin("tago_pin_excluded", 24, SMALL_PIN), 0.5f, PIN_TIP, 100f) }
            }
        }
        for (p in picks) {
            if (p.cls == "best") {
                // 목적지(추천·확정 만날 곳) = SDK 에 든 '도착' 핀(tmap end.png). 핀 끝(아래 가운데)이 점, 라벨은 핀 위
                val pin = sdkPin("end")
                markers += Mk("cand:${p.id}:${p.cls}", p.ll, pin, 0.5f, 1f, 500f)
                markers += Mk("lbl:${p.id}:${p.cls}", p.ll, tag(p.label, p.cls, gap = (pin.height / dp).roundToInt() + 4, above = true), 0.5f, 1f, 2000f)
            } else {
                // 대안은 목적지가 아니라 후보 — 후보 종류(편의점·카페…)별 v9 핀(SDK 도착 핀보다 조금 작게), 라벨은 핀 끝 아래
                markers += Mk("cand:${p.id}:${p.cls}", p.ll, place(p.icon), 0.5f, PIN_TIP, 450f)   // 핀 끝이 점
                markers += Mk("lbl:${p.id}:${p.cls}", p.ll, tag(p.label, p.cls, gap = 8, above = false), 0.5f, 0f, 2000f)
            }
        }
        // 경유(포장 매장)
        val via = s.optJSONObject("via")
        val viaLL = via?.let { LL(it.optDouble("lat"), it.optDouble("lon")) }
        if (via != null && viaLL != null) {
            val pin = sdkPin("pass")   // 경유지 = SDK 에 든 '경유' 핀(tmap pass.png)
            markers += Mk("via", viaLL, pin, 0.5f, 1f, 600f)
            markers += Mk("lbl:via", viaLL, tag(t("경유 · ", "Stop · ") + via.optString("name"), "alt", gap = (pin.height / dp).roundToInt() + 4, above = true), 0.5f, 1f, 2000f)
        }
        // 기다리는 사람(차 화면에서는 상대 = ink)
        val rider = s.optJSONObject("rider")
        val riderLL = rider?.takeIf { !it.isNull("lat") }?.let { LL(it.optDouble("lat"), it.optDouble("lon")) }
        if (riderLL != null && !boardedLike) {
            val type = s.optJSONObject("member")?.optString("type") ?: "adult"
            markers += Mk("rider", riderLL, pin("tago_person_" + (if (type == "senior") "senior" else if (type == "child") "child" else "adult"), 48, PLACE_PIN), 0.5f, PIN_TIP, 900f)
        }

        // 정차 금지 반경·단속 CCTV: 만날 곳 주변만, 확정 뒤엔 없음
        val focus = if (confirmed != null) emptyList() else listOfNotNull(target?.ll ?: riderLL)
        drawHazards(md, focus, markers)
        setMarkers(markers, car)

        // 노선
        val carPts: List<LL> = if (target != null && !arrivedLike) (lls(s.optJSONArray("carPath")).takeIf { it.size > 1 } ?: listOf(carLL, target.ll)) else emptyList()
        drawRoute(carPts, carLL, target?.ll, if (!arrivedLike) viaLL else null, heading)
        // 걷는 길(아이 → 만날 곳): 초록 점선
        val walk: List<LL> = if (target != null && !boardedLike && riderLL != null) (lls(s.optJSONArray("walkPath")).takeIf { it.size > 1 } ?: listOf(riderLL, target.ll)) else emptyList()
        drawWalk(walk)
        drawBusLanes(md, s)

        // 범례: 제안 중에만
        showLegend(md, confirmed == null)

        // 맞춤: 목적지가 바뀌면, 또는 차가 화면 가장자리(10% 안쪽)를 벗어나면
        val pts = arrayListOf(carLL)
        riderLL?.let { pts += it }
        target?.let { pts += it.ll }
        if (viaLL != null && !arrivedLike) pts += viaLL
        val fk = (target?.let { it.id + (if (confirmed != null) "c" else "") } ?: "") + (viaLL?.toString() ?: "")
        if (fk != fitKey) { fitKey = fk; userAt = 0L; styleRecenter(); fit(pts) }
        else if (!userBrowsing() && !inView(carLL, 0.1)) { if (userAt != 0L) { userAt = 0L; styleRecenter() }; fit(pts) }
    }

    // 정차 금지 반경(만날 곳 70m 안, 반경 100m 미만 규칙만)과 단속 CCTV(100m 안)
    private fun drawHazards(md: JSONObject, focus: List<LL>, markers: ArrayList<Mk>) {
        val key = focus.joinToString("|") { "%.5f,%.5f".format(it.lat, it.lon) } + "|" + dark
        val near = { p: LL, r: Double -> focus.any { dist(it, p) <= r } }
        if (key != hazKey) {
            hazKey = key
            map.removeAllTMapCircle()
            if (focus.isNotEmpty()) {
                val rules = md.optJSONArray("rules") ?: JSONArray()
                var n = 0
                for (i in 0 until rules.length()) {
                    val r = rules.getJSONObject(i)
                    val radius = r.optDouble("radius", 0.0)
                    if (radius >= 100) continue
                    val color = LAYER_COLOR[r.optString("layer")] ?: Color.GRAY
                    val pts = md.optJSONArray(r.optString("layer")) ?: continue
                    for (j in 0 until pts.length()) {
                        val p = pts.optJSONObject(j) ?: continue
                        val ll = LL(p.optDouble("lat"), p.optDouble("lon"))
                        if (!near(ll, 70.0)) continue
                        map.addTMapCircle(TMapCircle().apply {
                            id = "hz${n++}"
                            centerPoint = TMapPoint(ll.lat, ll.lon)
                            this.radius = radius
                            lineColor = color; lineAlpha = 230; circleWidth = 2 * dp
                            areaColor = color; areaAlpha = 31   // 12%
                        })
                    }
                }
            }
        }
        if (focus.isNotEmpty()) {
            val cctv = md.optJSONArray("cctv") ?: JSONArray()
            for (j in 0 until cctv.length()) {
                val p = cctv.optJSONObject(j) ?: continue
                val ll = LL(p.optDouble("lat"), p.optDouble("lon"))
                if (near(ll, 100.0)) markers += Mk("cam:$j", ll, pin(if (p.optBoolean("night")) "tago_pin_cctv_night" else "tago_pin_cctv", 24, SMALL_PIN), 0.5f, PIN_TIP, 90f)
            }
        }
    }

    private fun drawRoute(carPts: List<LL>, carLL: LL, to: LL?, via: LL?, heading: Double) {
        // 티맵 노선(실시간 교통)
        if (to != null && carPts.isNotEmpty()) {
            val key = "$to|${via ?: ""}"
            if (key != tmKey) { tmKey = key; tmLines = null; tmAt = 0L; tmRetryAt = 0L }
            val now = System.currentTimeMillis()
            val due = tmAt == 0L || (moving && now - tmAt > 60_000)
            if (!tmBusy && due && now >= tmRetryAt) {
                tmBusy = true; tmAt = now
                val from = carLL
                netH.post {
                    val lines = runCatching { fetchTmapRoute(from, to, via, heading) }.onFailure { log("티맵 노선을 못 받아 엔진 경로로: ${it.message}") }.getOrNull()
                    if (lines != null) log("티맵 노선: " + lines.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second.size } }.toString() + " (혼잡도 → 점 수)")
                    main.post {
                        tmBusy = false
                        if (destroyed) return@post
                        if (lines == null) tmRetryAt = System.currentTimeMillis() + 5 * 60_000   // 한도 초과(429)·오프라인이면 5분 쉰다 — 그동안 엔진 경로
                        if (tmKey == key && lines != null) { tmLines = lines; lastState?.let { safeDraw(it) } }
                    }
                }
            }
        } else { tmKey = null; tmLines = null }
        val lines: List<Pair<Int, List<LL>>> = tmLines ?: if (carPts.size > 1) listOf(0 to carPts) else emptyList()
        val key = lines.joinToString("|") { (c, p) -> "$c:${p.size}:${p.first()}:${p.last()}" }
        if (key == routeKey) return
        routeKey = key
        map.removeAllTMapTrafficLine()
        if (lines.isEmpty()) return
        val tl = TMapTrafficLine("route")
        tl.trafficLineList = ArrayList(lines.map { (c, p) -> TMapTrafficLine.TrafficLine(c, ArrayList(p.map { TMapPoint(it.lat, it.lon) })) })
        tl.isShowTraffic = true      // 티맵과 같은 교통 색
        tl.isShowIndicator = true    // 진행 방향 화살표
        tl.setLineWidth(9)
        tl.setOutLineWidth(2)
        map.addTrafficLine(tl)
    }

    /** 티맵 자동차 경로 API(교통 포함). 혼잡도 0 정보없음 · 1 원활 · 2 서행 · 3 지체 · 4 정체 — SDK TrafficLine 은 0~3 이라 4 는 3 으로(공식 샘플과 같다) */
    private fun fetchTmapRoute(from: LL, to: LL, via: LL?, heading: Double): List<Pair<Int, List<LL>>> {
        val body = JSONObject().apply {
            put("startX", from.lon); put("startY", from.lat); put("endX", to.lon); put("endY", to.lat)
            put("reqCoordType", "WGS84GEO"); put("resCoordType", "WGS84GEO"); put("trafficInfo", "Y"); put("searchOption", 0)
            put("angle", ((heading % 360 + 360) % 360).roundToInt()); put("directionOption", 1)   // 차가 보는 방향 우선(유턴 경로 방지)
            if (via != null) put("passList", "${via.lon},${via.lat}")
        }
        val c = URL("https://apis.openapi.sk.com/tmap/routes?version=1").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("appKey", TMAP_KEY_FOR_ROUTE ?: ""); c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("accept", "application/json")
        val text = try { c.outputStream.use { it.write(body.toString().toByteArray()) }; if (c.responseCode != 200) error("HTTP ${c.responseCode}"); c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val feats = JSONObject(text).optJSONArray("features") ?: error("빈 응답")
        val out = ArrayList<Pair<Int, List<LL>>>()
        for (i in 0 until feats.length()) {
            val g = feats.getJSONObject(i).optJSONObject("geometry") ?: continue
            if (g.optString("type") != "LineString") continue
            val cs = g.getJSONArray("coordinates").let { a -> (0 until a.length()).map { LL(a.getJSONArray(it).getDouble(1), a.getJSONArray(it).getDouble(0)) } }
            if (cs.size < 2) continue
            val code = IntArray(cs.size - 1)
            g.optJSONArray("traffic")?.let { tr -> for (k in 0 until tr.length()) { val r = tr.getJSONArray(k); for (x in r.getInt(0) until minOf(r.getInt(1), code.size)) code[x] = minOf(r.getInt(2), 3) } }
            var start = 0
            for (x in 1..code.size) if (x == code.size || code[x] != code[start]) { out += code[start] to cs.subList(start, x + 1); start = x }
        }
        if (out.isEmpty()) error("경로 없음")
        return out
    }

    private fun drawWalk(walk: List<LL>) {
        val key = if (walk.size > 1) "${walk.size}:${walk.first()}:${walk.last()}" else ""
        if (key == walkKey) return
        walkKey = key
        map.removeTMapPolyLine("walk")
        if (walk.size < 2) return
        map.addTMapPolyLine(TMapPolyLine("walk", ArrayList(walk.map { TMapPoint(it.lat, it.lon) })).apply {
            lineColor = GREEN; lineWidth = 4 * dp; lineAlpha = 255
            pathEffect = intArrayOf((2 * dp).roundToInt(), (8 * dp).roundToInt())
            outLineColor = GREEN; outLineWidth = 0f; outLineAlpha = 0
        })
    }

    // 버스전용차로: 차 화면은 지금 운영 중인 것만(청록)
    private fun drawBusLanes(md: JSONObject, s: JSONObject) {
        val clock = s.optJSONObject("clock")
        val (hh, mm) = (clock?.optString("hhmm") ?: "22:00").split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 22 }) to (it.getOrElse(1) { 0 }) }
        val hour = hh + mm / 60.0
        val wk = clock?.optBoolean("weekday", true) ?: true
        val key = "$hour|$wk"
        if (key == busKey) return
        busKey = key
        val lanes = md.optJSONArray("busLanes") ?: return
        for (i in 0 until lanes.length()) map.removeTMapPolyLine("bus$i")
        for (i in 0 until lanes.length()) {
            val b = lanes.getJSONObject(i)
            val spans = b.optJSONArray("spans") ?: JSONArray()
            val on = wk && (0 until spans.length()).any { val sp = spans.getJSONArray(it); sp.getDouble(0) <= hour && hour < sp.getDouble(1) }
            if (!on) continue
            val pts = lls(b.optJSONArray("coords")); if (pts.size < 2) continue
            map.addTMapPolyLine(TMapPolyLine("bus$i", ArrayList(pts.map { TMapPoint(it.lat, it.lon) })).apply {
                lineColor = Color.parseColor("#0B8F8F"); lineWidth = 9 * dp; lineAlpha = 140; outLineAlpha = 0; outLineWidth = 0f
            })
        }
    }

    private fun showLegend(md: JSONObject, show: Boolean) {
        if (legendShown == show) return
        legendShown = show
        if (!show) { legend.visibility = android.view.View.GONE; return }
        val rules = md.optJSONArray("rules") ?: JSONArray()
        val sb = SpannableStringBuilder()
        (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optDouble("radius", 999.0) < 100 }.take(3).forEachIndexed { i, r ->
            // 좁은 지도(세로 분할·Connect-L)에서 줄은 항목 사이에서만 바뀐다: 항목 안은 줄바꿈 없는 공백(NBSP)
            if (i > 0) sb.append("   ")
            val st = sb.length; sb.append("●$NBSP"); sb.setSpan(ForegroundColorSpan(LAYER_COLOR[r.optString("layer")] ?: Color.GRAY), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("${LAYER_NAME[r.optString("layer")]?.let { if (lang == "en") it.second else it.first } ?: r.optString("layer")} ${r.optDouble("radius").roundToInt()}m".nb())
        }
        if (lang != "en") {   // 공공데이터·OSM(엔진 도로·후보 데이터, ODbL) 출처. 바탕 지도 출처는 SDK 의 TMAP 로고
            sb.append("\n"); val st = sb.length
            sb.append(listOf("소화전 서울시 소방재난본부", "CCTV 강남구청", "길·장소 © OpenStreetMap").joinToString(" · ") { it.nb() })
            sb.setSpan(AbsoluteSizeSpan((12 * dp).roundToInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        legend.text = sb
        legend.setTextColor(if (dark) Color.parseColor("#9E9E9E") else Color.parseColor("#6E6E6E"))
        legend.background = GradientDrawable().apply { setColor(if (dark) Color.parseColor("#1C1C1C") else Color.WHITE); setStroke(max(1, dp.roundToInt()), if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#D4D4D4")) }
        legend.visibility = android.view.View.VISIBLE
    }

    // ---------------------------------------------------------------- 마커

    private class Mk(val id: String, val ll: LL, val icon: Bitmap, val ax: Float, val ay: Float, val priority: Float, val rotation: Float = 0f) {
        val sig get() = "%.6f,%.6f,%.0f,%d".format(ll.lat, ll.lon, rotation, System.identityHashCode(icon))
    }

    /** 원하는 마커와 지금 지도를 맞춘다. 겹침은 priority 로 정하고(addMarker 참고), 넣는 순서도 아래 → 위로 맞춰 둔다.
     *  그래서 차 말고 하나라도 바뀌면 모두 지우고 아래(단속·제외)부터 위(퍽 → 라벨) 순서로 다시 넣고, 커서는 늘 맨 마지막(맨 위)에 둔다. 차만 움직이면 커서만 바꾼다 */
    private fun setMarkers(list: List<Mk>, car: Mk) {
        val sorted = list.sortedBy { it.priority }
        val sig = sorted.joinToString("|") { it.id + "@" + it.sig }
        if (sig != markerSig["_all"]) {
            markerSig.keys.filter { !it.startsWith("_") }.forEach { map.removeTMapMarkerItem(it) }
            markerSig.keys.retainAll { it.startsWith("_") }
            for (m in sorted) { addMarker(m); markerSig[m.id] = m.sig }
            markerSig["_all"] = sig
            markerSig.remove("_car")
        }
        if (car.sig != markerSig["_car"]) {
            if (markerSig.containsKey("_car")) map.removeTMapMarkerItem(car.id)
            addMarker(car); markerSig["_car"] = car.sig
        }
    }

    private fun addMarker(m: Mk) = map.addTMapMarkerItem(TMapMarkerItem().apply {
        // Mk.priority 는 '클수록 위'. 엔진은 값이 작을수록 위에 그린다(실측: 90 인 단속 아이콘이 2000 인 라벨을 덮었다) → 뒤집어 넘긴다
        id = m.id; icon = m.icon; setTMapPoint(TMapPoint(m.ll.lat, m.ll.lon, m.rotation)); setPosition(m.ax, m.ay); priority = 10000f - m.priority; setAnimation(false)
    })

    /** 순정 커서 그림(VSM SDK 에 함께 든 location_marker 148×148 @xhdpi: 흰 원 + 파란 화살표 + 그림자) */
    /** SDK 에 든 경로 핀(tmap-sdk res/drawable: start 출발 · end 도착 · pass 경유, 30×39 원본). 차 화면에서 보이게 순정 커서와 같은 비율로 키운다 */
    private fun sdkPin(name: String): Bitmap = bitmaps.getOrPut("sdk:$name") {
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        val src = (if (id != 0) BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) else null)
            ?: return@getOrPut puck("pin", 48, "best")
        val k = 1.6f * dp / WEB_SCALE   // 원본 30×39 → 약 48×62(밀도 1.0 기준)
        Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
    }

    /** SDK 에 없는 마커 = 사용자 v9 세트(res/drawable-nodpi/tago_place_*·tago_person_*·tago_pin_*.png, 기준 크기의 4배·투명, 단색 물방울 + 흰 실루엣).
     *  모든 핀은 끝점이 위치(앵커 0.5, PIN_TIP). base = 그림의 기준 칸(48, 단속·제외 24), scale = 차 화면 배율(SDK 도착 핀이 1.6) */
    private fun pin(res: String, base: Int, scale: Float): Bitmap = bitmaps.getOrPut("v9:$res:$scale") {
        val id = ctx.resources.getIdentifier(res, "drawable", ctx.packageName)
        val src = (if (id != 0) BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) else null)
            ?: return@getOrPut puck("pin", base, "other")
        val px = (base * scale * dp / WEB_SCALE).roundToInt()
        Bitmap.createScaledBitmap(src, px, px, true).also { if (it !== src) src.recycle() }
    }

    /** 대안 후보 핀: 엔진 후보의 icon(종류) → v9 그림. 없는 종류는 원형 퍽(ORBIT 글리프) */
    private fun place(icon: String): Bitmap {
        val name = PLACE_ICON[icon] ?: return puck(icon, 44, "alt")
        return pin("tago_place_$name", 48, PLACE_PIN)
    }

    private fun cursor(): Bitmap = bitmaps.getOrPut("cursor") {
        val id = ctx.resources.getIdentifier("location_marker", "drawable", ctx.packageName)
        (if (id != 0) BitmapFactory.decodeResource(ctx.resources, id) else null) ?: puck("navigation", 44, "best")
    }

    /** 원형 퍽(docs/ui-design.md §9): kind = other(ink) · best(green 테) · sure(green 채움) · alt/via(orange 테) · no(제외) · cam(단속) */
    private fun puck(icon: String, sizeDp: Int, kind: String): Bitmap = bitmaps.getOrPut("p:$icon:$sizeDp:$kind:$dark") {
        val face = if (dark) Color.parseColor("#1C1C1C") else Color.WHITE
        val (fill, ring, ringDp, glyph) = when (kind) {
            "best" -> Quad(face, GREEN, 4f, GREEN)
            "sure" -> Quad(GREEN, face, 3f, Color.WHITE)
            "alt", "via" -> Quad(face, ORANGE, 4f, ORANGE)
            "no" -> Quad(face, GRAY9E, 2f, GRAY9E)
            "cam" -> Quad(face, GRAY9E, 2f, Color.parseColor("#6E6E6E"))
            else -> Quad(if (dark) Color.parseColor("#E8E8E8") else Color.BLACK, face, 3f, if (dark) Color.parseColor("#111111") else Color.WHITE)
        }
        val sz = (sizeDp * dp).roundToInt()
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = sz / 2f
        p.color = ring; c.drawCircle(r, r, r, p)
        p.color = fill; c.drawCircle(r, r, r - ringDp * dp, p)
        val resId = ctx.resources.getIdentifier("ic_$icon", "drawable", ctx.packageName).takeIf { it != 0 }
            ?: ctx.resources.getIdentifier("ic_pin", "drawable", ctx.packageName)
        ctx.getDrawable(resId)?.mutate()?.let { d ->
            d.setTint(glyph)
            val g = (sz * 0.56f).roundToInt(); val o = (sz - g) / 2
            d.setBounds(o, o, o + g, o + g); d.draw(c)
        }
        b
    }

    /** 라벨(직각 태그): 흰 면 + 뜻 색 테두리(추천·확정 green, 대안 orange) + 진한 글. 퍽 쪽으로 gap 만큼 투명 여백을 둬 앵커(아래/위 가운데)가 점에 오게 한다 */
    private fun tag(text: String, cls: String, gap: Int, above: Boolean): Bitmap = bitmaps.getOrPut("t:$text:$cls:$gap:$above:$dark") {
        val border = when (cls) { "best" -> if (dark) Color.parseColor("#00E074") else GREEN; "alt" -> ORANGE; else -> if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#D4D4D4") }
        val ink = when (cls) { "best" -> if (dark) Color.parseColor("#00E074") else Color.parseColor("#0E6B3F"); "alt" -> if (dark) ORANGE else Color.parseColor("#8A4B00"); else -> if (dark) Color.parseColor("#F2F2F2") else Color.BLACK }
        val bw = if (cls == "best" || cls == "alt") 2 * dp else 1 * dp
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18 * dp; typeface = Typeface.DEFAULT_BOLD; color = ink }
        val fm = tp.fontMetrics
        val w = (tp.measureText(text) + 2 * 10 * dp + 2 * bw).roundToInt()
        val h = ((fm.descent - fm.ascent) + 2 * 4 * dp + 2 * bw).roundToInt()
        val g = (gap * dp).roundToInt()
        val b = Bitmap.createBitmap(w, h + g, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val top = if (above) 0f else g.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = border; c.drawRect(RectF(0f, top, w.toFloat(), top + h), p)
        p.color = if (dark) Color.parseColor("#1C1C1C") else Color.WHITE; c.drawRect(RectF(bw, top + bw, w - bw, top + h - bw), p)
        c.drawText(text, bw + 10 * dp, top + bw + 4 * dp - fm.ascent, tp)
        b
    }

    // ---------------------------------------------------------------- 맞춤

    private fun fit(pts: List<LL>) {
        if (pts.size < 2) { map.setCenterPoint(pts[0].lat, pts[0].lon); map.zoomLevel = 16; return }
        val bounds = map.getBoundsFromPoints(ArrayList(pts.map { TMapPoint(it.lat, it.lon) })) ?: return
        // 여백: 좌우 150(긴 라벨이 잘리지 않게)·위아래 60, 왼쪽 아래 범례 자리
        map.fitBounds(bounds, TMapInsets(px(150), px(60), px(150), px(60)))
        main.postDelayed({ if (map.zoomLevel > 17) map.zoomLevel = 17 }, 600)   // 너무 가까이 붙지 않게(웹 지도 최대 줌과 같다)
    }

    private fun inView(p: LL, inset: Double): Boolean = runCatching {
        val lt = map.leftTopPoint; val rb = map.rightBottomPoint
        val dy = (lt.latitude - rb.latitude) * inset; val dx = (rb.longitude - lt.longitude) * inset
        p.lat <= lt.latitude - dy && p.lat >= rb.latitude + dy && p.lon >= lt.longitude + dx && p.lon <= rb.longitude - dx
    }.getOrDefault(true)

    // ---------------------------------------------------------------- 도우미

    private fun px(v: Int) = (v * dp).roundToInt()

    /** 순정 내비처럼 가게 아이콘을 끄고 지명만 남긴다. TMapView 에는 없는 VSM 엔진 설정(getViewSetting)이라 실패해도 지도는 그대로 */
    private fun tidyMap() = runCatching {
        val engine = engine ?: error("VSM 엔진 없음")
        // POI 를 통째로 끄면(setShowPOI(false)) 지명까지 사라져 지도가 비어 보였다(실측). 가게 아이콘만 0 으로 줄이고 지명(아파트·건물·역·공원)은 남긴다.
        // 차 화면은 멀리서 보니 지명 글자를 1.4배(웹 지도와 같다)
        engine.viewSetting.apply { setShowPOI(true); setShowRoadName(true); setPOIIconScale(0f); setPOICaptionScale(1.4f) }
        log("가게 아이콘 끔, 지명 1.4배")
    }.onFailure { log("POI 정리 실패(지도는 그대로): ${it.message}") }
    private fun JSONObject.centerLL(): LL = optJSONArray("center")?.let { LL(it.getDouble(0), it.getDouble(1)) } ?: LL(37.4985, 127.0605)

    data class LL(val lat: Double, val lon: Double) { override fun toString() = "%.6f,%.6f".format(lat, lon) }
    private data class Quad(val a: Int, val b: Int, val c: Float, val d: Int)

    companion object {
        /** 경로 API 를 부를 앱키 — MainActivity 가 BuildConfig.TMAP_KEY 로 넣는다 */
        var TMAP_KEY_FOR_ROUTE: String? = null
        private const val WEB_SCALE = 1.375f
        private val TOUCH_LOG = ai.meeos.tago.BuildConfig.DEMO_HOOKS   // 터치·핀치 진단 로그(디버그·시연 빌드)
        private const val NBSP = ' '
        // 한글은 음절마다 줄이 바뀔 수 있어("교차/로") 어절 안의 공백뿐 아니라 글자 사이에도 줄바꿈 금지(WORD JOINER)를 넣는다
        private fun String.nb() = replace(' ', NBSP).toList().joinToString("⁠")
        private const val PIN_TIP = 172f / 192f   // v9 핀 끝의 세로 위치(192 그림의 172 px = 0.896, 작은 핀도 같은 비율)
        private const val PLACE_PIN = 1.4f        // 대안 후보·사람 핀: 48 기준 → 67 px(SDK 도착 핀 1.6 보다 조금 작게)
        private const val SMALL_PIN = 1.6f        // 단속·제외 핀: 24 기준 → 38 px

        /** 엔진 후보 종류(icon) → v9 그림 이름(tago_place_*). 지금 데이터에 있는 종류 + 앞으로 넣을 종류(엔진 kind 이름과 v9 이름 둘 다) */
        private val PLACE_ICON = mapOf(
            "convenience" to "store", "store" to "store", "cafe" to "cafe", "pharmacy" to "pharmacy", "parking" to "parking", "subway" to "subway",
            "academy" to "school_back_gate",
            "fast_food" to "fastfood", "fastfood" to "fastfood", "bakery" to "bakery", "bank" to "bank", "library" to "library",
            "community_centre" to "community_center", "community_center" to "community_center",
            "police" to "police_box", "police_box" to "police_box", "hospital" to "hospital", "clinic" to "clinic",
            "church" to "church", "cathedral" to "cathedral", "temple" to "temple",
            "fuel" to "gas_station", "gas_station" to "gas_station", "taxi" to "taxi_stand", "taxi_stand" to "taxi_stand",
            "senior_center" to "senior_center", "welfare_center" to "welfare_center",
            "bus_station" to "bus_terminal", "bus_terminal" to "bus_terminal", "train_station" to "train_station",
            "supermarket" to "mart", "mart" to "mart", "department_store" to "department_store",
            "park_entrance" to "park_entrance", "playground" to "playground", "sports_centre" to "gym", "gym" to "gym",
            "swimming_pool" to "swimming_pool", "apartment_gate" to "apartment_gate", "school_back_gate" to "school_back_gate",
            "public_parking" to "public_parking",
        )
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val GREEN = Color.parseColor("#02C265")
        private val ORANGE = Color.parseColor("#FF8A00")
        private val GRAY9E = Color.parseColor("#9E9E9E")
        private val LAYER_COLOR = mapOf("hydrants" to Color.parseColor("#F62E24"), "busStops" to Color.parseColor("#FF8A00"), "crossings" to Color.parseColor("#7A5CFF"),
            "signals" to Color.parseColor("#C2185B"), "stops" to Color.parseColor("#8E44AD"), "schools" to Color.parseColor("#D4A20A"))
        private val LAYER_NAME = mapOf("hydrants" to ("소화전" to "Fire hydrant"), "busStops" to ("버스정류장" to "Bus stop"), "crossings" to ("횡단보도" to "Crosswalk"),
            "signals" to ("교차로" to "Signal"), "stops" to ("교차로(정지)" to "Intersection"), "schools" to ("어린이보호구역(추정)" to "School"))

        private fun ll(v: Any?): LL? = (v as? JSONArray)?.takeIf { it.length() >= 2 }?.let { LL(it.getDouble(0), it.getDouble(1)) }
        private fun lls(a: JSONArray?): List<LL> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { ll(a.opt(it)) }

        /** 두 점 사이 거리(m), 하버사인 */
        fun dist(a: LL, b: LL): Double {
            val rad = PI / 180; val s1 = sin((b.lat - a.lat) * rad / 2); val s2 = sin((b.lon - a.lon) * rad / 2)
            val h = s1 * s1 + cos(a.lat * rad) * cos(b.lat * rad) * s2 * s2
            return 6371000 * 2 * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
