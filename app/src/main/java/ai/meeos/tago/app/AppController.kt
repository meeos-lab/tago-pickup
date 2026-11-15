package ai.meeos.tago.app

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.emergency.EmergencyController
import ai.meeos.tago.emergency.EmergencyGps
import ai.meeos.tago.navi.NaviBridge
import ai.meeos.tago.pickup.PickupController
import ai.meeos.tago.pickup.RoutineScheduler
import ai.meeos.tago.road.RoadLinkController
import ai.meeos.tago.ui.AlertTone
import ai.meeos.tago.ui.AlertUi
import ai.meeos.tago.ui.BarUi
import ai.meeos.tago.ui.CamUi
import ai.meeos.tago.ui.StatusUi
import ai.meeos.tago.ui.BinColor
import ai.meeos.tago.ui.CardLine
import ai.meeos.tago.ui.CardTone
import ai.meeos.tago.ui.CardUi
import ai.meeos.tago.ui.MarkerKind
import ai.meeos.tago.ui.MemberUi
import ai.meeos.tago.ui.RoadStripUi
import ai.meeos.tago.ui.StripMarker
import ai.meeos.tago.ui.Panel
import ai.meeos.tago.ui.QuestionUi
import ai.meeos.tago.ui.RearCamUi
import ai.meeos.tago.ui.RegionUi
import ai.meeos.tago.ui.TagoUiState
import ai.meeos.tago.ui.VoiceUi
import ai.meeos.tago.voice.AnswerParser
import android.content.Context
import org.json.JSONObject

/**
 * 앱의 머리: 컨트롤러(픽업·함께 가기·응급·루틴)를 잇고, 1초 틱을 돌리고, 운전자 말을 나눠 주고, 화면 상태(TagoUiState)를 만든다.
 * 화면은 onUiState 로 받은 상태만 그린다. Android 생명주기·인텐트는 MainActivity 가 맡는다.
 */
/** 실제 위치로 쓸 시스템 위치의 최대 나이(s). 차에서는 순정 내비가 계속 갱신한다(에뮬레이터는 고정 위치) */
private const val HERE_MAX_AGE_S = 600.0
/** 일행의 주차 한 줄에 "가까이"를 붙이는 간격(m) — 경로 위 간격이 모두 이 안일 때만 */
private const val PARK_NEAR_M = 150

class AppController(context: Context, val s: Services, private val onMapUrl: (String?) -> Unit, dial: (String) -> Boolean) {
    val pickup = PickupController(s, ::setMap)
    val road = RoadLinkController(context, s, ::setMap).also { it.onDestChanged = { setMap(null) }; it.here = { hereSnap } }   // 첫 화면에서 목적지를 바꾸면 지도에 깃발
    /** 목적지 검색 칸이 열렸나·적고 있는 검색어(화면 상태만 — 검색은 [검색]을 누를 때) */
    var destOpen = false; private set
    var destQuery = ""; private set
    fun destOpenSearch() { if (!s.car.stoppedKnown) return; destOpen = true; render() }   // 정차(P) 때만 연다(V-01 기어 D 잠금 그대로) — D 면 흐린 칸 + 까닭 한 줄
    // ---- 실제 위치(11-14 오후): 시스템이 아는 마지막 위치(순정 내비가 늘 갱신). 정확도 200 m 안·HERE_MAX_AGE_S 안만. 기기 안에서만 쓰고 서버로 보내지 않는다
    private val hereGps = ai.meeos.tago.parking.ParkingGps(context)
    @Volatile private var hereSnap: ai.meeos.tago.parking.GpsFix? = null
    private fun readHere(): ai.meeos.tago.parking.GpsFix? = runCatching { hereGps.fix() }.getOrNull()?.takeIf { (it.accuracyM ?: 0.0) <= 200.0 && it.ageS <= HERE_MAX_AGE_S }
    private fun geoKm(a: Double, b: Double, c: Double, d: Double): Double {   // 하버사인(km)
        val r = Math.PI / 180; val s1 = Math.sin((c - a) * r / 2); val s2 = Math.sin((d - b) * r / 2)
        return 2 * 6371.0 * Math.asin(Math.sqrt(s1 * s1 + Math.cos(a * r) * Math.cos(c * r) * s2 * s2))
    }
    /** 내장 서버 /api/app/<이름>(서버 스레드에서 부른다 — 스냅숏만 읽는다) */
    fun appApi(name: String): org.json.JSONObject? = when (name) {
        "here" -> hereSnap?.let { org.json.JSONObject().put("ok", true).put("lat", it.lat).put("lon", it.lon).put("acc", it.accuracyM ?: org.json.JSONObject.NULL).put("age", it.ageS.toInt()) }
            ?: org.json.JSONObject().put("ok", false).put("wait", t[R.string.here_wait]).put("on", road.realLocEnabled)
        "myroute" -> road.realRoute?.let { p -> val d = road.dest
            org.json.JSONObject().put("ok", true).put("name", d?.name ?: "").put("to", org.json.JSONArray().put(d?.lat ?: 0.0).put(d?.lon ?: 0.0))
                .put("pts", org.json.JSONArray().also { a -> p.points.forEach { q -> a.put(org.json.JSONArray().put(q.lat).put(q.lon)) } }).put("distM", p.distM).put("timeS", p.timeS) }
            ?: org.json.JSONObject().put("ok", false)
        "pos" -> org.json.JSONObject().put("ok", road.active).put("s", road.linkPos).put("t", s.epoch())   // 내 자리·허브에 보내는 시각 — 시험 도구(맞은편 차 넣기)가 쓴다
        "ff" -> org.json.JSONObject().put("ok", true).put("rate", road.ff)   // 빠르게 감기 배율(시연 훅) — 지도 페이지가 멀리서 보게
        else -> null
    }
    fun destQueryChange(q: String) { destQuery = q.take(30); destVoiceNote = null; render() }
    // 말로 검색 실패 한 줄(말소리가 들리지 않았어요 등)은 그 한 번에만: 글로 찾거나 판을 닫으면 지운다(11-15 사용자 "이건 왜 뜨는거임" — 전엔 다시 열어도 남았다)
    fun destSearch() { destVoiceNote = null; road.searchDest(destQuery) }
    fun destPick(i: Int) { road.pickDest(i); if (road.destCands == null) destOpen = false; render() }   // 골랐을 때만 닫는다(경로 밖 줄은 열린 채 까닭 한 줄) — 닫은 뒤 다시 그린다
    fun destClear() { road.clearDest() }
    fun destClose() { destOpen = false; destVoiceNote = null; destVoiceStop(); road.closeDestSearch() }

    // ---- 말로 검색(11-14 사용자 "검색에 stt 기능도 넣어줘"): 첫 화면 검색 칸의 마이크. 질문 답과 같은 Pleos STT 를 한 번 듣고(VoiceAssistant.listenOnce),
    // 들은 말을 검색 칸에 넣어 바로 검색한다. 글자 입력과 같은 규칙 — 정차(P)에서만(V-01). 다시 누르면 멈춘다
    var destListening = false; private set
    private var destVoiceNote: String? = null
    /** [pcm] 은 시연·시험 훅(stt_wav)만 넘긴다: 마이크 대신 그 녹음을 같은 인식 엔진에 넣는다. 화면의 마이크 버튼은 늘 null(실제 마이크) */
    /** 시연 판 마이크 단추용 녹음 샘플(/data/local/tmp 안 이름, 16 kHz 모노 16비트 PCM). 시연 훅 mic_sample 로 바꾸거나 "off" 로 끈다 */
    var micSample: String? = "mic_sample.pcm"
    fun destVoice(pcm: ByteArray? = null) {
        if (!s.car.stoppedKnown) return
        if (destListening) { destVoiceStop(); render(); return }
        // 시연 판(11-15 사용자 "녹음버튼 누르면 녹음 샘플 틀게해줘 그걸로 stt해서 판정하고"): 에뮬레이터엔 PC 마이크 소리가 안 들어오므로 마이크 단추를 누르면
        // 녹음 샘플을 스피커로 틀면서 같은 소리를 인식 엔진에 넣는다(판정은 실제 엔진). 샘플이 없거나 제품 빌드면 실제 마이크
        val sample = if (pcm == null && BuildConfig.DEMO_HOOKS) micSample?.let { java.io.File("/data/local/tmp", it) }?.takeIf { it.isFile && it.length() in 1..2_000_000 } else null
        destOpen = true; destVoiceNote = null
        val onText: (String) -> Unit = { text -> destListening = false; s.log.d("말로 검색 인식: $text"); destQuery = text.take(30); render(); destSearch() }
        val onFail: (ai.meeos.tago.voice.VoiceAssistant.OnceFail) -> Unit = { why ->
            destListening = false
            destVoiceNote = t[when (why) {
                ai.meeos.tago.voice.VoiceAssistant.OnceFail.SILENT -> R.string.dest_voice_silent
                ai.meeos.tago.voice.VoiceAssistant.OnceFail.NO_MATCH -> R.string.dest_voice_nomatch
                ai.meeos.tago.voice.VoiceAssistant.OnceFail.MIC -> R.string.dest_voice_mic
                ai.meeos.tago.voice.VoiceAssistant.OnceFail.BUSY -> R.string.dest_voice_busy
                ai.meeos.tago.voice.VoiceAssistant.OnceFail.DOWN -> R.string.dest_voice_down
                ai.meeos.tago.voice.VoiceAssistant.OnceFail.ERROR -> R.string.dest_voice_nomatch
            }]
            s.log.d("말로 검색 실패: $why")
            // 말이 없어 듣기가 끝났으면 음성으로도 알린다(11-15 사용자 "아무말 안하면 tts 알림해줘") — 화면 한 줄과 같은 말
            if (why == ai.meeos.tago.voice.VoiceAssistant.OnceFail.SILENT) s.voice.say(t[R.string.dest_voice_silent], key = "dest_silent")
            render()
        }
        if (sample != null) s.log.d("시연 마이크: 녹음 샘플 ${sample.name} 재생 + 인식")
        destListening = !s.dialog.awaiting && (when {
            sample != null -> s.voice.listenOnceAudio(sample.readBytes(), onText, onFail, play = true)
            pcm == null -> s.voice.listenOnce(onText, onFail)
            else -> s.voice.listenOnceAudio(pcm, onText, onFail)
        })
        if (s.dialog.awaiting) destVoiceNote = t[R.string.dest_voice_busy]   // 질문의 답을 기다리는 중이면 듣지 않는다(답이 먼저)
        render()
    }
    private fun destVoiceStop() { if (destListening) { destListening = false; s.voice.stopListening() } }

    // ---- 주변 차 연결(일반 주행) / 일행 나누기 — 11-14 사용자 "함께가기가 메인이 아니라 서로 모르는 차들 차대차 링크가 핵심".
    // 주변 차 연결 = 지금의 도로 연결(road.start): 주행을 시작하면 단추 없이 연결한다(자동 연결). 일행 = 지금의 가족 그룹(road.startGroup): 선택.
    // 허브 요청은 그대로이고 부르는 시점과 화면 이름만 다르다
    /** 주행 시 자동 연결(설정·첫 화면 스위치, 기기에 저장, 기본 켬). 실제 연결은 [linkConsent] 가 참일 때만 */
    var autoLink = true; private set
    /** 첫 실행 동의(익명으로 위치·위험 정보를 주고받는다는 한 장)를 받았나. 기기에 저장 */
    var linkConsent = false; private set
    private var consentLater = false      // 이번 실행에서 [나중에]를 눌렀다 — 동의 판을 다시 띄우지 않는다(스위치·[지금 연결]을 누르면 다시)
    private var consentAsk = false        // 스위치·[지금 연결]·[일행 만들기](btn_convoy)를 눌러 동의 판을 다시 띄웠다
    private var autoHold = false          // 달리는 중에 손으로 [연결 끊기]를 눌렀다 — 다음 정차까지 자동으로 다시 켜지 않는다
    private var pendingConvoy = false     // [일행 만들기](btn_convoy)를 연결 전에 눌렀다 — 연결되는 대로 일행을 만든다
    private val linkEdition get() = BuildConfig.ROAD_LINK && !BuildConfig.PICKUP

    /** 자동 연결 켬/끔. 켜려는데 동의 전이면 동의 판을 띄운다(동의하면 켜진다) */
    fun setAutoLink(on: Boolean) {
        if (on && !linkConsent) { consentAsk = true; render(); return }
        autoLink = on
        prefs.edit().putBoolean("autoLink", on).apply()
        render()
    }
    /** 동의 판의 답. 동의 = 저장하고 자동 연결을 켠다(미뤄 둔 [일행 만들기](btn_convoy)가 있으면 이어서). 나중에 = 이번 실행에서는 판을 닫는다 */
    fun consent(agree: Boolean) {
        consentAsk = false
        if (agree) {
            linkConsent = true; autoLink = true
            prefs.edit().putBoolean("linkConsent", true).putBoolean("autoLink", true).apply()
            if (pendingConvoy) startConvoy()
        } else { consentLater = true; pendingConvoy = false }
        render()
    }
    /** 설정 [동의 거두기](11-15, 매니패스트 S-AKRXXU): 동의를 지우고 저장, 연결 중이면 끊는다. 이번 실행에서는 동의 판을 다시 띄우지 않고 다음 실행(또는 스위치·[지금 연결])에서 다시 묻는다 */
    fun withdrawConsent() {
        if (!linkConsent) return
        linkConsent = false; consentAsk = false; consentLater = true; pendingConvoy = false
        prefs.edit().putBoolean("linkConsent", false).apply()
        if (road.active) road.end()
        render()
    }
    /** 시연 훅 link_consent: 동의 판을 거치지 않고 동의 상태를 정한다(시연 녹화·시험용) */
    fun demoConsent(on: Boolean) { linkConsent = on; consentAsk = false; consentLater = !on; prefs.edit().putBoolean("linkConsent", on).apply(); render() }
    /** [지금 연결](정차 중 손으로 켜기): 동의 전이면 동의 판부터 */
    fun linkNow() { if (linkEdition && !linkConsent) { consentAsk = true; render(); return }; autoHold = false; road.start(pickup.active) }
    /** [연결 끊기]: 달리는 중에 끊었으면 다음 정차까지 자동 연결을 쉰다 */
    fun unlink() { if (!s.car.stoppedKnown) autoHold = true; pendingConvoy = false; road.end() }
    /** [일행 만들기](btn_convoy): 연결 전이면 먼저 연결하고, 연결되는 대로 일행을 만든다. 일행이 이미 있으면 그대로 */
    fun startConvoy() {
        if (!linkEdition || emergency.active) return
        if (!linkConsent) { pendingConvoy = true; consentAsk = true; render(); return }
        if (road.hasGroup) return
        if (road.convoy != null) { pendingConvoy = false; road.startGroup() } else { pendingConvoy = true; road.start(pickup.active) }
    }
    /** 1초 틱: 자동 연결(기어 D 또는 움직임) · 미뤄 둔 일행 만들기 */
    private fun linkTick() {
        if (!linkEdition) return
        if (s.car.stoppedKnown) autoHold = false
        if (pendingConvoy && road.convoy != null) { pendingConvoy = false; if (!road.hasGroup) road.startGroup() }
        if (autoLink && linkConsent && !autoHold && !road.active && !emergency.active && !pickup.active && (s.car.driving || s.car.speedMs >= 1f)) road.startAuto()
    }

    // ---- 설정(11-14 사용자 "설정 버튼도 만드렁줘"): 실제로 동작하는 것만. 화면이 톱니로 여닫고, 값은 여기서 바꾼다
    /** 내 실제 위치 쓰기(켬/끔, 기기에 저장). 끄면 시연 경로 출발 자리 기준(예전 시연 훅 real_loc 과 같은 동작) */
    fun setRealLoc(on: Boolean) {
        if (!s.car.stoppedKnown || road.active) return
        road.realLocEnabled = on
        prefs.edit().putBoolean("realLoc", on).apply()
        road.onDestChanged()
        render()
    }
    /** 주차 위치 기억(새 기능 H): 입구 판단·나선 경사로 층 추정·기둥 사진 글자. 허브 그룹이 있으면 사진을 받아 온다 */
    val parking = ai.meeos.tago.parking.ParkingController(context, s) { road.convoy?.let { c -> c.optString("group").takeIf { it.isNotEmpty() }?.let { g -> g to c.getString("me") } } }
        .also { p -> road.parkText = { p.card()?.big } }   // 가족 휴대폰 그룹 화면에 주차 글(층·기둥만)
    /** 응급 카드의 차 안 GPS 위치(마지막으로 아는 위치만 읽는다) */
    val emergencyGps = EmergencyGps(context)
    val emergency = EmergencyController(s, pickup, dial, { emergencyGps.coords() }) { road.placeLines() }
    val routine = RoutineScheduler(s, pickup) { emergency.active }
    /** 연구 기록(경고 반응 로그). 빌드 플래그 STUDY_LOG 가 꺼진 빌드(출품 release)에서는 화면에도 없고 아무것도 기록하지 않는다 */
    val study = ai.meeos.tago.study.ResponseLog(context, s)
    var studyOpen = false
    var licensesOpen = false   // 오픈소스 고지 화면(B-62)
        private set
    private var studyAge = ""
    private var studyYears = 5
    private var studyCar = ""
    /** 차속 신호가 없는 차에서 동의했을 때 위치 권한(GPS 속도)을 묻는다 — MainActivity 가 잇는다 */
    var requestGps: () -> Unit = {}

    var onUiState: (TagoUiState) -> Unit = {}
    /** 다른 앱(순정 내비 등)이 앞에 있으면 주기 렌더를 쉰다(판정·전송은 계속) */
    var paused = false
    var devPanel = false
        private set
    private var toast: String? = null
    private var transientHeard: String? = null       // 시연 인텐트로 넣은 말을 잠깐 보여 준다
    private var tickCount = 0
    private val rearDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private var rearWarnAt = 0L
    private var lastTick = System.currentTimeMillis()
    private val prefs = context.getSharedPreferences(NaviBridge.PREFS, Context.MODE_PRIVATE)
    private val startedAt = android.os.SystemClock.elapsedRealtime()

    private val t get() = s.texts

    fun start() {
        s.render = ::render
        s.toast = ::showToast
        pickup.nearby.emergencyActive = { emergency.active }
        s.voice.shouldListen = { s.dialog.awaiting }
        s.voice.beforeListen = { road.releaseMicForListening() }
        s.voice.onListeningChanged = { render() }
        s.voice.onUtterance = { handleUtterance(it) }
        s.log.onChange = { if (devPanel) render() }
        if (BuildConfig.STUDY_LOG) {
            road.study = study
            s.voice.onSpeakingChanged = { study.speaking(it) }
            study.restore()
        }
        // 언리얼 → 앱: 후방 카메라 프레임·뒤 센서가 오면(서버 스레드) 60ms 안에 한 번만 다시 그린다
        ai.meeos.tago.engine.RearCamFeed.onUpdate = { if (rearDirty.compareAndSet(false, true)) s.main.postDelayed({ rearDirty.set(false); if (s.car.reversing) render() }, 25) }
        Region.of(prefs.getString("region", null))?.let { applyRegion(it) }
        if (prefs.contains("realLoc")) road.realLocEnabled = prefs.getBoolean("realLoc", true)   // 설정에서 끈 값(11-14). 저장 전이면 기본(켬) 그대로
        autoLink = prefs.getBoolean("autoLink", true); linkConsent = prefs.getBoolean("linkConsent", false)   // 주변 차 연결: 자동 연결 스위치·첫 실행 동의(11-14)
        parking.start()
        s.main.postDelayed(tick, 1000)
        render()
    }

    fun stop() {
        s.main.removeCallbacks(tick)
        ai.meeos.tago.engine.RearCamFeed.onUpdate = null   // 정적 객체가 이 컨트롤러(→ Activity)를 붙잡고 있지 않게
        road.stop()
        parking.stop()
        study.close()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = (now - lastTick) / 1000.0
            lastTick = now
            tickCount++
            s.car.readTick()                                  // 차 신호(기어·와이퍼·좌석·벨트·도어)는 1초마다 읽는다
            run {   // 후진 중 뒤 센서(언리얼)가 60cm 안이면 6초에 한 번 말한다
                val feed = ai.meeos.tago.engine.RearCamFeed
                val c = feed.center
                if (s.car.reversing && feed.sensorFresh(now) && c != null && c < 0.6f && now - rearWarnAt > 6000) { rearWarnAt = now; s.say(t[R.string.say_rear_obstacle]) }
            }
            pickup.tick(dt, tickCount)
            road.manageSiren()
            road.tick(dt, s.voice.speaking)
            linkTick()                                        // 주변 차 연결: 주행을 시작하면 자동 연결(동의·설정이 켜져 있을 때), 미뤄 둔 일행 만들기
            playTick()                                        // 시연 주행 재생 막대: 고른 배속을 안내가 끝나면 다시 건다
            hereSnap = if (road.realLocEnabled) readHere() else null
            runCatching { parking.tick() }.onFailure { s.log.d("주차 위치 기억 오류: ${it.javaClass.simpleName}") }   // 주차 위치 기억: 입구(GPS 끊김)·경사로 회전·기어 P (그 기능이 있는 판만). 보조 기능이라 어떤 오류도 본 기능을 멈추지 않게
            study.tick(road.view, road.active)                // 연구 기록: 1 Hz 표본(경고 전 30초 고리 + 진행 중 경고 묶음)
            if (studyOpen && (s.car.locked || road.active || pickup.active || emergency.active)) studyOpen = false   // 설정 화면은 정차 중·대기에서만
            if (licensesOpen && (s.car.locked || road.active || pickup.active || emergency.active)) licensesOpen = false
            if (destListening && (!s.car.stoppedKnown || road.active)) destVoiceStop()   // 말로 검색도 정차(P)에서만 — 기어를 바꾸면 멈춘다
            if (BuildConfig.PICKUP && tickCount % 10 == 0) routine.check()   // 요일 루틴은 픽업 기능
            // 화면이 가려져 있거나 말하는 중이면 그리지 않는다(판정·전송은 계속). 응급 카드는 경과 시간 때문에 매초
            if (!paused && (emergency.active || !s.voice.speaking)) render()
            s.main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ 운전자 입력

    /** 운전자 말(온디바이스 STT 또는 시연 인텐트). 응급 > 주문 받아쓰기 > 휴게소 > 함께 가기 > 가족 묶기 > 근처 > 예/아니오 */
    fun handleUtterance(text: String) {
        val u = AnswerParser.Utterance(text)
        if (BuildConfig.EMERGENCY && AnswerParser.isEmergency(u)) return emergency.start()
        if (s.dialog.pending?.onText != null) { s.dialog.text(text); return }
        if (road.active && s.dialog.pending?.kind == Question.Kind.CHARGER && AnswerParser.isBroken(u) && AnswerParser.yesNo(u) != false) return s.dialog.answer(true)
        if (road.active && !s.dialog.awaiting && AnswerParser.isChargerReport(u)) return road.chargeVoiceReport()
        if (road.active && !s.dialog.awaiting && AnswerParser.isRestStop(u)) return road.restTogether()
        if (BuildConfig.ROAD_LINK && !pickup.active && !road.hasGroup && AnswerParser.isTogether(u)) return road.startGroup()   // "함께 가자" = 일행 만들기(연결 전이면 먼저 연결)
        if (BuildConfig.ROAD_LINK && !road.active && !pickup.active && AnswerParser.isStartRoad(u)) return road.start(pickup.active)
        if (road.active && !road.hasGroup && AnswerParser.isGroup(u)) return road.startGroup()
        if (BuildConfig.PICKUP && AnswerParser.isToilet(u)) return pickup.nearby.ask("toilet")
        if (BuildConfig.PICKUP && AnswerParser.isDriveThru(u)) return pickup.nearby.ask("drivethru")
        if (BuildConfig.PICKUP && AnswerParser.isTakeout(u)) return pickup.nearby.ask("takeout")
        if (!s.dialog.awaiting) return
        when (AnswerParser.yesNo(u)) {
            true -> s.dialog.answer(true)
            false -> s.dialog.answer(false)
            null -> s.log.d("인식했지만 답이 아님: $text")
        }
    }

    /** 시연: 에뮬레이터에는 실제 마이크 입력이 없어, 인식된 말을 인텐트로 넣어 같은 처리 경로를 탄다 */
    fun demoUtterance(text: String) {
        transientHeard = text
        s.main.postDelayed({ if (transientHeard == text) { transientHeard = null; render() } }, 4000)
        render()
        handleUtterance(text)
    }

    fun answer(yes: Boolean) = s.dialog.answer(yes)

    /** 픽업 시작. 응급 중이거나 픽업이 없는 판(길벗)이면 시작하지 않는다(false) */
    fun startPickup(member: Member): Boolean {
        if (!BuildConfig.PICKUP || emergency.active) return false
        pickup.start(member)
        return true
    }
    fun startRoad() = road.start(pickup.active)
    fun roadPrimary() { if (road.hasGroup) road.restTogether() else road.startGroup() }
    fun toggleDevPanel() { devPanel = !devPanel; render() }

    // ------------------------------------------------------------ 연구 기록(동의·참가자 정보)

    fun openStudy() { if (BuildConfig.STUDY_LOG && !s.car.locked && !road.active && !pickup.active && !emergency.active) { studyOpen = true; render() } }
    fun closeStudy() { studyOpen = false; render() }
    fun openLicenses() { if (!s.car.locked && !road.active && !pickup.active && !emergency.active) { studyOpen = false; licensesOpen = true; render() } }
    fun closeLicenses() { licensesOpen = false; render() }
    private fun licensesUi() = ai.meeos.tago.ui.LicensesUi(t[R.string.sl_licenses], ai.meeos.tago.ui.LicenseList.items(BuildConfig.ROAD_LINK),
        listOf(t[R.string.lic_note_driver], t[R.string.lic_note_sdk], t[R.string.lic_note_data], t[R.string.lic_note_file]), t[R.string.btn_close],
        // 개인정보 고지(11-15, 차대차 판만 — 매니패스트 대조): 근거 docs/dev/83 1~3절, 거기 없는 말은 넣지 않는다
        privacyTitle = if (linkEdition) t[R.string.priv_title] else null,
        privacy = if (linkEdition) listOf(t[R.string.priv_send], t[R.string.priv_use], t[R.string.priv_third], t[R.string.priv_keep], t[R.string.priv_not], t[R.string.priv_withdraw]) else emptyList(),
        licTitle = if (linkEdition) t[R.string.lic_title] else null)
    fun studyAge(id: String) { if (id in ai.meeos.tago.study.ResponseLog.AGE_BANDS) { studyAge = id; render() } }
    fun studyYears(d: Int) { studyYears = (studyYears + d).coerceIn(0, 70); render() }
    fun studyCar(id: String) { if (id in ai.meeos.tago.study.ResponseLog.CAR_TYPES) { studyCar = id; render() } }
    fun studyAgree() {
        if (studyAge.isEmpty() || studyCar.isEmpty()) return
        study.start(ai.meeos.tago.study.ResponseLog.Profile(studyAge, studyYears, studyCar, true, System.currentTimeMillis(), "screen"))
        if (study.needsGps()) requestGps()
        studyOpen = false
        showToast(t[R.string.toast_study_started])
    }
    /** 시연 훅 study_sim: 동의 화면을 거치지 않고 세션을 연다(에뮬레이터 검증용 — 머리 줄의 consent_source 가 demo_hook) */
    fun studyDemo(age: String, years: Int, car: String) {
        if (age !in ai.meeos.tago.study.ResponseLog.AGE_BANDS || car !in ai.meeos.tago.study.ResponseLog.CAR_TYPES) { s.log.d("study_sim 형식: age=30s,years=8,car=sedan"); return }
        study.start(ai.meeos.tago.study.ResponseLog.Profile(age, years.coerceIn(0, 70), car, true, System.currentTimeMillis(), "demo_hook"))
        render()
    }
    fun studyStop() { study.stop(); studyOpen = false; showToast(t[R.string.toast_study_stopped]) }
    fun studyUpload(id: String) { study.setUpload(id == "on"); render() }
    fun studyToast(text: String) = showToast(text)

    private fun studyUi(): ai.meeos.tago.ui.StudyUi {
        val ages = ai.meeos.tago.study.ResponseLog.AGE_BANDS.map { RegionUi(it, t[ageLabel(it)]) }
        val cars = ai.meeos.tago.study.ResponseLog.CAR_TYPES.map { RegionUi(it, t[carLabel(it)]) }
        val sid = study.sessionId?.take(6) ?: ""
        val status = if (!study.active) null else if (study.warningsOpen > 0) t[R.string.sl_study_on_open, sid, study.warningsDone, study.warningsOpen] else t[R.string.sl_study_on, sid, study.warningsDone]
        return ai.meeos.tago.ui.StudyUi(
            active = study.active,
            consentTitle = t[R.string.study_consent_title],
            consentLines = listOf(R.string.study_consent_purpose, R.string.study_consent_what, R.string.study_consent_not, R.string.study_consent_where, R.string.study_consent_stop).map { t[it] },
            ageTitle = t[R.string.study_age_title], ageRows = listOf(ages.take(4), ages.drop(4)), ageSel = studyAge,
            yearsTitle = t[R.string.study_years_title], yearsLabel = t[R.string.study_years, studyYears],
            carTitle = t[R.string.study_car_title], carItems = cars, carSel = studyCar,
            agreeLabel = t[R.string.btn_study_agree], agreeEnabled = studyAge.isNotEmpty() && studyCar.isNotEmpty(), declineLabel = t[R.string.btn_study_decline],
            status = status, exportLabel = t[R.string.btn_study_export], uploadTitle = t[R.string.study_upload_title],
            uploadItems = listOf(RegionUi("off", t[R.string.study_upload_off]), RegionUi("on", t[R.string.study_upload_on])), uploadSel = if (study.uploadOn) "on" else "off",
            uploadNote = if (study.uploadUrl.isEmpty()) t[R.string.study_upload_no_url] else t[R.string.study_upload_note],
            stopLabel = t[R.string.btn_study_stop], closeLabel = t[R.string.btn_study_close],
        )
    }

    private fun ageLabel(id: String) = when (id) {
        "10s" -> R.string.study_age_10s; "20s" -> R.string.study_age_20s; "30s" -> R.string.study_age_30s; "40s" -> R.string.study_age_40s
        "50s" -> R.string.study_age_50s; "60s" -> R.string.study_age_60s; else -> R.string.study_age_70plus
    }
    private fun carLabel(id: String) = when (id) { "sedan" -> R.string.study_car_sedan; "suv" -> R.string.study_car_suv; "van_truck" -> R.string.study_car_van_truck; else -> R.string.study_car_other }

    /** 지역을 바꾼다(대기 화면에서만): 가족 목록·버튼 글·음성 언어를 그 지역 것으로 */
    fun setRegion(id: String) {
        val r = Region.of(id) ?: return
        if (pickup.active || road.active || emergency.active || s.dialog.awaiting || r == s.region) return   // 질문 중에 바꾸면 말과 글이 섞인다
        applyRegion(r)
        prefs.edit().putString("region", r.id).apply()
        s.say(t[R.string.say_region_switched, r.label])
        showToast(t[R.string.toast_region, t.ro(r.label)])
    }

    private fun applyRegion(r: Region) {
        s.region = r
        s.texts.setRegion(r)
        s.voice.setRegion(r)
        render()
    }

    private fun showToast(text: String) {
        toast = text
        render()
        s.main.postDelayed({ if (toast == text) { toast = null; render() } }, 3000)
    }

    // ------------------------------------------------------------ 화면 상태

    fun render() = onUiState(build())

    /** 운전자가 한눈에 볼 것만: 지금 상태 한 줄, 보조 한 줄, 이 상태에서 누를 버튼 (docs/ui-design.md §6) */
    private fun build(): TagoUiState {
        val region = s.region
        val m = pickup.member
        val st = pickup.lastState
        val tName = pickup.targetName()
        val best = st?.optJSONObject("proposal")?.optJSONObject("best")
        val q = s.dialog.pending
        val emg = emergency.active
        val linkStale = road.linkStale()   // 허브 응답이 10초 넘게 없다(V-03): 낡은 안내를 현재처럼 그리지 않는다
        val cv = road.view
        val hzList = cv?.optJSONArray("hazards")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val topHz = hzList?.firstOrNull { it.has("say") && it.optString("type") == "SIREN" }   // 긴급차량 양보가 가장 먼저
            ?: hzList?.firstOrNull { it.has("say") && it.optString("type") in setOf("INCIDENT", "BLOCKED", "HARD_BRAKE", "DEBRIS") }
        val adv = cv?.optJSONObject("ahead")?.optJSONObject("advice")
        val rainAhead = cv?.optJSONObject("ahead")?.optJSONObject("rain")
        val nb = pickup.nearby
        val offer = nb.offer
        val status = pickup.status
        var showMembers = false
        var showNavi = false
        val studyPanel = studyOpen && !emg && !road.active && !pickup.active
        val licensesPanel = licensesOpen && !studyPanel && !emg && !road.active && !pickup.active
        val chargePanel = road.active && !emg && road.survey != null && !s.car.locked
        val (head, sub) = when {
            emg -> t[R.string.hl_emergency] to t[R.string.sl_emergency, region.emergencyNumber]
            linkStale -> t[R.string.hl_link_lost] to t[R.string.sl_link_lost, road.linkAgeSec()]
            licensesPanel -> if (linkEdition) t[R.string.set_licenses] to t[R.string.sl_notice] else t[R.string.hl_licenses] to t[R.string.sl_licenses]   // 차대차 판: 개인정보 고지가 함께(11-15)
            studyPanel && study.active -> t[R.string.hl_study_on] to (studyUi().status ?: "")
            studyPanel -> t[R.string.hl_study_consent] to t[R.string.sl_study_consent]
            chargePanel -> t[R.string.hl_charge_survey] to t[R.string.sl_charge_survey]
            road.active && q?.kind == Question.Kind.CHARGER -> t[R.string.hl_charger_ask] to t[R.string.sl_charger_ask]
            road.active && q?.kind == Question.Kind.REST -> t[R.string.hl_rest_ask] to
                (cv?.optJSONObject("group")?.optJSONObject("rest")?.let { "${it.optString("by")} · ${it.optString("name")} ${t.km(it.optInt("dist"))}" } ?: "")
            road.active && topHz?.optString("type") == "SIREN" -> {   // 구급차 위치 신호(beacon) · 길 비킴만(yieldOnly) · 마이크
                val bc = topHz.optBoolean("beacon"); val yo = topHz.optBoolean("yieldOnly")
                val veh = t[when (topHz.optString("vtype")) { "fire" -> R.string.veh_fire; "police" -> R.string.veh_police; else -> R.string.veh_ambulance }]   // 비컨 차종(진로 양보 판단은 차종과 무관, 문구만 다르다)
                (if (bc) t[R.string.hl_siren_ev, t.km(topHz.optInt("ahead")), veh] else t[if (yo) R.string.hl_siren_yield else R.string.hl_siren, t.km(topHz.optInt("ahead"))]) to
                    ((if (bc) t[R.string.sl_siren_beacon, veh] else if (yo) t[R.string.sl_siren_yield, topHz.optInt("yielded")] else t[R.string.sl_siren, topHz.optInt("cars")]) +
                        if (topHz.isNull("eta")) "" else t[R.string.sl_siren_eta, maxOf(10, (topHz.optInt("eta") + 5) / 10 * 10)])
            }
            road.active && topHz != null -> t[R.string.hl_hazard, t.km(topHz.optInt("ahead")), road.hazardLabel(topHz)] to
                (topHz.optJSONArray("by")?.optString(0)?.takeIf { it.isNotEmpty() }?.let { n -> t[R.string.sl_hazard_by, n] + if (topHz.optInt("anon") > 0) t[R.string.sl_hazard_more, topHz.optInt("anon")] else "" }
                    ?: t[R.string.sl_hazard_cars, topHz.optInt("cars")])
            road.active && adv != null && adv.optInt("dist") > 0 -> t[R.string.hl_advice, adv.optInt("adviseKmh")] to t[R.string.sl_advice, t.km(adv.optInt("dist")), adv.optInt("jamKmh")]
            road.active && rainAhead != null -> t[R.string.hl_rain, maxOf(1, (rainAhead.optInt("eta") + 30) / 60)] to t[R.string.sl_rain, t.km(rainAhead.optInt("dist")), rainAhead.optInt("cars")]
            road.active -> (if (road.ff > 1.0) t[R.string.hl_ff, "%.0f".format(road.ff)] else t[R.string.hl_road_on]) to ((road.sideNote()?.let { "$it · " } ?: "") + (if (road.demoDrive) t[R.string.sl_demo_drive] + " · " else if (road.onRealRoute && road.simDrive) t[R.string.sl_sim_real] + " · " else "") + t[R.string.sl_road_nearby, cv?.optInt("nearby") ?: 0] +
                (cv?.optJSONObject("restNext")?.let { t[R.string.sl_road_next, it.optString("name"), t.km(it.optInt("dist"))] } ?: ""))
            q?.kind == Question.Kind.ROUTINE -> routine.asked.let { r -> t[R.string.hl_routine, r?.member ?: "", r?.label ?: ""] to t[R.string.sl_routine] }
            nb.orderStage == "listen" -> t[R.string.hl_order_listen] to
                (if (nb.offerKind == "takeout") t[R.string.sl_order_listen_takeout, nb.orderPlace?.optString("name") ?: ""] else t[R.string.sl_order_listen_drivethru, nb.orderPlace?.optString("name") ?: ""])
            nb.orderStage == "confirm" -> t[R.string.hl_order_confirm] to nb.orderSummary()
            q?.kind == Question.Kind.DRIVETHRU && nb.offerKind == "takeout" -> t[R.string.hl_takeout_ask] to
                (if ((offer?.optInt("lateSec") ?: 0) < 45) t[R.string.sl_takeout_ontime, offer?.optString("name") ?: ""] else t[R.string.sl_takeout_late, offer?.optString("name") ?: "", t.mins(offer?.optInt("lateSec") ?: 0)])
            q?.kind == Question.Kind.DRIVETHRU -> t[R.string.hl_drivethru_ask] to t[R.string.name_dist, offer?.optString("name") ?: "", offer?.optInt("dist") ?: 0]
            q?.kind == Question.Kind.PARKING -> (if (nb.offerKind == "toilet") t[R.string.hl_toilet_ask] else t[R.string.hl_parking_ask]) to t[R.string.name_dist, offer?.optString("name") ?: "", offer?.optInt("dist") ?: 0]
            pickup.parkedWaiting && m != null -> t[R.string.hl_parked_waiting] to t[R.string.sl_parked_waiting, t.subj(m.name)]
            m == null || !pickup.active -> {
                showMembers = !road.active
                if (!BuildConfig.PICKUP) t[R.string.hl_idle_gilbeot] to t[R.string.sl_idle_gilbeot]
                else if (BuildConfig.ROAD_LINK) t[R.string.hl_idle_road] to t[R.string.sl_idle_road] else t[R.string.hl_idle] to t[R.string.sl_idle]
            }
            status == "dropped" -> { showMembers = true; t[R.string.hl_dropped, m.name] to t[R.string.sl_dropped] }
            status == "boarded" && !pickup.riderConfirmed() -> t[R.string.hl_boarded_detected, t.seatLabel(pickup.boardedSeat ?: region.riderSeat)] to t[R.string.sl_boarded_detected, m.name]
            status == "boarded" -> t[R.string.hl_boarded, m.name] to
                t[R.string.sl_boarded, t.seatLabel(pickup.boardedSeat ?: region.riderSeat), if (pickup.beltOf(pickup.boardedSeat ?: region.riderSeat) == true) t[R.string.belt_on] else t[R.string.belt_off]]
            status == "arrived" && tName != null -> t[R.string.hl_arrived, tName] to t[R.string.sl_arrived, m.name, pickup.curbDoorLabel()]
            status in setOf("confirmed", "arrived") && tName != null -> {
                showNavi = true
                val eta = st?.optJSONObject("eta")
                t[R.string.hl_enroute, tName] to when {
                    pickup.replanPending() -> t[R.string.sl_enroute_replan, m.name]
                    eta != null && st?.optBoolean("leaveNow") == true -> t[R.string.sl_enroute_leave, t.mins(eta.optInt("carSec")), m.name]
                    eta != null -> t[R.string.sl_enroute_eta, t.mins(eta.optInt("carSec")), m.name, t.mins(eta.optInt("walkSec"))]
                    else -> t[R.string.sl_enroute_told, m.name]
                }
            }
            q?.kind == Question.Kind.POINT && best != null -> t[R.string.hl_proposal, best.optString("name")] to
                t[R.string.sl_proposal, m.name, t.mins(best.optInt("walkSec")), t.mins(best.optInt("carSec")), best.optInt("stopLimit") / 60]
            else -> t[R.string.hl_waiting_rider, m.name] to t[R.string.sl_waiting_rider]
        }

        // 경고 화면 머리(11-13 디자인 손질): 헤드라인과 같은 뜻을 큰 거리 숫자 / 제목 / 본문으로 나눠 그린다. 경고가 헤드라인일 때만(응급·끊김·설문·질문 중에는 없다)
        val alertHead = road.active && !emg && !linkStale && !licensesPanel && !studyPanel && !chargePanel && q?.kind != Question.Kind.CHARGER && q?.kind != Question.Kind.REST
        val alert = if (!alertHead) null else when {
            topHz?.optString("type") == "SIREN" -> {
                val (n, u) = bigKm(topHz.optInt("ahead"))
                val veh = t[when (topHz.optString("vtype")) { "fire" -> R.string.veh_fire; "police" -> R.string.veh_police; else -> R.string.veh_ambulance }]
                // 지도 우선 화면의 한 줄 뒤 보조: 통과 예상 시각이 있으면 그것만 짧게("약 30초"), 없으면 보조 줄 그대로
                val eta = if (topHz.isNull("eta")) null else t[R.string.al_siren_eta_short, maxOf(10, (topHz.optInt("eta") + 5) / 10 * 10)]
                AlertUi(t[R.string.al_siren_lead].ifEmpty { null }, n, t[R.string.al_siren_unit, u],
                    if (topHz.optBoolean("beacon")) t[R.string.al_siren_ev, veh] else t[if (topHz.optBoolean("yieldOnly")) R.string.al_siren_yield else R.string.al_siren], sub, AlertTone.SIREN,
                    eyebrow = t[R.string.band_siren], trail = t[R.string.al_siren_after].ifEmpty { null }, note = eta ?: sub,
                    what = if (topHz.optBoolean("beacon")) t[R.string.al_siren_ev, veh] else t[if (topHz.optBoolean("yieldOnly")) R.string.al_siren_yield else R.string.al_siren], distM = kotlin.math.abs(topHz.optInt("ahead")))
            }
            topHz != null -> bigKm(topHz.optInt("ahead")).let { (n, u) ->
                val sign = if (topHz.optBoolean("flood")) R.drawable.hz_sign_rain else hazardSign(topHz.optString("type"))
                AlertUi(null, n, u, t[R.string.al_hazard, road.hazardLabel(topHz)], sub, AlertTone.DANGER, eyebrow = sub, sign = sign, bar = alertBar(topHz.optInt("ahead")),
                    trail = t[R.string.al_ahead], what = road.hazardLabel(topHz), note = sub) }
            adv != null && adv.optInt("dist") > 0 -> bigKm(adv.optInt("dist")).let { (n, u) ->
                AlertUi(null, n, u, t[R.string.al_advice, adv.optInt("adviseKmh")], t[R.string.al_advice_sub, adv.optInt("jamKmh")], AlertTone.CAUTION,
                    eyebrow = t[R.string.al_advice_eyebrow, adv.optInt("jamKmh")], sign = R.drawable.hz_sign_jam, bar = alertBar(adv.optInt("dist")),
                    trail = t[R.string.al_ahead], what = t[R.string.al_advice_what, adv.optInt("adviseKmh")], note = t[R.string.al_advice_eyebrow, adv.optInt("jamKmh")]) }
            else -> null
        }
        // 지도 우선 화면: 주행 중 경고가 없을 때 왼쪽 위 한 줄(끊김·넓힌 안내·비 예상은 주황 점, 그 밖은 초록 — 글은 헤드라인 그대로)
        val pill = if (!road.active || emg || alert != null) null else when {
            linkStale -> StatusUi(head, AlertTone.CAUTION)
            !road.sirenBanner() && road.farNotice() != null -> StatusUi(road.farNotice()!!, AlertTone.CAUTION)
            rainAhead != null -> StatusUi(head, AlertTone.CAUTION)
            // 평소: "연결됨 · 주변 차 13대"(주변 차 수는 허브 값 그대로, 11-14 주변 차 연결 / 일행 나누기). 보기가 아직 없으면 수를 붙이지 않는다
            // 최소 표시: 알약 하나에 앱 이름 + "· 16대"(주변 차가 없으면 "· 연결됨") — 빠르게 감기 등 다른 머리말일 때는 그 글 그대로
            homeMinimal && linkEdition && head == t[R.string.hl_road_on] -> StatusUi(cv?.optInt("nearby")?.takeIf { it > 0 }?.let { t[R.string.pill_cars, it] } ?: t[R.string.pill_linked], null)
            else -> StatusUi(head + (cv?.let { v -> " · " + if (v.optInt("nearby") > 0) t[R.string.sl_road_nearby, v.optInt("nearby")] else t[R.string.pill_no_cars] } ?: ""), null)
        }
        // 일행이 있으면 둘째 알약 "일행 N대" 알약(나를 뺀 일행 수 — 허브 그룹 보기의 일행 줄을 센 값)
        val mates = cv?.optJSONObject("group")?.optJSONArray("members")?.let { a -> (0 until a.length()).count { !a.getJSONObject(it).optBoolean("me") } }
        val convoyPill = if (road.active && !emg && road.hasGroup && mates != null) t[R.string.pill_convoy, mates] else null

        // 연구 기록: 헤드라인에 띄운 경고(사고 의심·공식 안내·정체 꼬리)를 읽기만 한다 — 화면 노출 시각(t = 0 후보)
        if (study.active && road.active && !emg && !linkStale && q?.kind != Question.Kind.REST) {
            if (topHz != null && topHz.optString("type") != "SIREN") study.onScreen(topHz, null, head)
            else if (topHz == null && adv != null && adv.optInt("dist") > 0) study.onScreen(null, adv, head)
        }
        val members = region.members.map { MemberUi(it.name, t.memberType(it.type), memberIcon(it.type), it.type) }
        val panel: Panel = when {
            emg -> Panel.Emergency(
                card = CardUi(CardTone.SAFETY, t[R.string.emg_card_title], R.drawable.ic_medical, emergency.lines + CardLine(R.drawable.ic_timer, t[R.string.emg_elapsed, emergency.elapsedSec()])),
                dialLabel = t[R.string.btn_dial, region.emergencyNumber], endLabel = t[R.string.btn_end_emergency])
            licensesPanel -> Panel.Licenses(licensesUi())
            studyPanel -> Panel.Study(studyUi())
            chargePanel -> Panel.ChargeReport(chargeUi(road.survey!!))
            road.active -> roadPanel(if (linkStale) null else cv, q, if (linkStale) null else topHz?.optString("id"), linkStale)
            showMembers && !pickup.active -> Panel.Idle(if (!BuildConfig.ROAD_LINK) null else t[if (BuildConfig.PICKUP) R.string.btn_road_link else R.string.btn_road_start], if (BuildConfig.PICKUP) members else emptyList(),
                hero = if (BuildConfig.ROAD_LINK && !BuildConfig.PICKUP) t[R.string.hero_road] else null,   // 첫 화면 한 문장(차대차 판만)
                studyLabel = if (BuildConfig.STUDY_LOG && BuildConfig.ROAD_LINK && !s.car.locked) t[if (study.active) R.string.btn_study_on else R.string.btn_study_off] else null,
                licensesLabel = if (!s.car.locked) t[R.string.btn_licenses] else null,
                demoLabel = if (BuildConfig.DEMO_HOOKS && Edition.roadLink) t[R.string.btn_demo_lap] else null,
                summary = if (!road.active && s.car.stoppedKnown && road.summary.worthShowing()) summaryCard() else null,
                parking = parkingUi(),
                dest = destUi(),
                settings = settingsUi(),
                // 주변 차 연결 한 줄 + 스위치(차대차 판만): 동의 전 = 동의 안내, 켬 = 자동 연결 안내, 끔 = 꺼짐 안내
                link = if (!linkEdition) null else ai.meeos.tago.ui.LinkLineUi(
                    t[if (!linkConsent) R.string.link_need_consent else if (autoLink) R.string.link_auto_line else R.string.link_auto_off], linkConsent && autoLink,
                    short = t[if (!linkConsent) R.string.link_short_consent else if (autoLink) R.string.link_short_wait else R.string.link_short_off]),
                // 첫 실행 동의 한 장: 차가 서 있을 때만(속도 0 — 큰 버튼 둘뿐이라 기어와 무관하게 연다, 달리는 중에는 띄우지 않는다)
                consent = if (linkEdition && !linkConsent && s.car.speedMs < 1f && (consentAsk || !consentLater)) ai.meeos.tago.ui.ConsentUi(
                    t[R.string.consent_title], listOf(t[R.string.consent_send], t[R.string.consent_not], t[R.string.consent_stop]), t[R.string.btn_consent_agree], t[R.string.btn_consent_later],
                    moreLabel = t[R.string.consent_more]) else null,
                convoyLabel = if (linkEdition) t[R.string.btn_convoy] else null,
                menuLabel = if (linkEdition) t[R.string.home_menu] else null)
            else -> {
                val step = when {
                    !pickup.active || q?.kind == Question.Kind.ROUTINE -> 0
                    status == "boarded" || status == "dropped" -> 4
                    status == "arrived" -> 3
                    status == "confirmed" -> 2
                    else -> 1
                }
                val message = pickup.lastMessage?.takeIf { status in setOf("waiting_rider", "proposed", "confirmed", "arrived") }?.let { "${m?.name}: $it" }
                val card = when {
                    nb.orderStage == "placed" && status !in setOf("boarded", "dropped") -> orderCard(nb)
                    pickup.replanPending() && status in setOf("confirmed", "arrived") -> CardUi(CardTone.WARNING, null, null, listOf(CardLine(R.drawable.ic_flag, t[R.string.card_replan, pickup.replanFrom()])))
                    else -> null
                }
                Panel.Pickup(step = if (status == "dropped") 0 else step, stepLabels = listOf(t[R.string.step_link], t[R.string.step_meet], t[R.string.step_arrive], t[R.string.step_board]),
                    message = message, naviLabel = if (showNavi) t[R.string.btn_navigate] else null, card = card, members = if (showMembers) members else emptyList())
            }
        }

        val voice = when {
            s.voice.listening -> VoiceUi.LISTENING
            s.voice.speaking -> VoiceUi.SPEAKING
            else -> VoiceUi.IDLE
        }
        val voiceText = when {
            transientHeard != null -> "“$transientHeard”"
            voice == VoiceUi.LISTENING -> s.voice.heard?.let { "“$it”" } ?: q?.listenHint
            voice == VoiceUi.SPEAKING -> t[R.string.voice_speaking]
            else -> null
        }
        val feed = ai.meeos.tago.engine.RearCamFeed
        val rearCam = if (s.car.reversing && feed.fresh()) feed.frame?.let { f ->
            val sf = feed.sensorFresh()
            RearCamUi(f, feed.frameAt, if (sf) feed.left else null, if (sf) feed.center else null, if (sf) feed.right else null, t[R.string.rearcam_title], t[R.string.rearcam_hint], feed.bitmap) } else null
        val cam = if (BuildConfig.ROAD_LINK) road.camAlert()?.let { c ->
            val d = if (c.distM < 1000) t[R.string.cam_m, (c.distM / 10).toInt() * 10] else t[R.string.cam_km, "%.1f".format(c.distM / 1000)]
            when (c.kind) {
                ai.meeos.tago.road.RoutePlace.CamKind.SPEED -> CamUi(c.limit, d, t[R.string.cam_speed])
                ai.meeos.tago.road.RoutePlace.CamKind.SECTION_START -> CamUi(c.limit, d, t[R.string.cam_section_start])
                ai.meeos.tago.road.RoutePlace.CamKind.IN_SECTION -> CamUi(c.limit, t[R.string.cam_to_end, d], t[R.string.cam_section])
            }
        } else null
        return TagoUiState(
            cam = cam,
            rearCam = rearCam,
            headline = head, subline = sub,
            voice = voice, voiceText = voiceText,
            question = q?.let { if (it.yesLabel != null && it.noLabel != null) QuestionUi(it.yesLabel, it.noLabel) else null },
            panel = panel,
            emergencyLabel = if (BuildConfig.EMERGENCY && !emg) t[R.string.btn_emergency] else null,
            regions = if (BuildConfig.PICKUP && !pickup.active && !road.active && !emg && !studyPanel && !licensesPanel) Region.entries.map { RegionUi(it.id, it.label) } else null,
            regionTitle = t[R.string.region_section],
            regionId = region.id,
            mapUrl = mapUrl,
            mapEmptyText = s.serverError ?: if (!BuildConfig.PICKUP) t[R.string.map_empty_gilbeot] else if (BuildConfig.ROAD_LINK) t[R.string.map_empty_road] else t[R.string.map_empty],
            toast = toast,
            devLog = if (devPanel) s.log.lines.toList() else null,
            devStatus = if (devPanel) devStatus() else null,
            notice = serviceNotice(),
            banner = if (emg) null else if (road.sirenBanner()) t[R.string.banner_siren] else road.farNotice(),   // 넓힌 안내(새 기능 I)는 가까운 안내가 없을 때만, 같은 배너 자리에 약한 문구
            alert = alert,
            driving = !s.car.stoppedKnown,
            sideNote = if (BuildConfig.ROAD_LINK) road.sideNote() else null,
            goHint = if (BuildConfig.ROAD_LINK && !BuildConfig.PICKUP && !road.active && !road.realRouteReady() && road.offDemoStart()) t[R.string.go_demo_hint] else null,
            status = pill,
            around = aroundUi(panel),
            player = playerUi(panel),
            convoyPill = convoyPill,
            homeMin = homeMinimal && linkEdition,
        )
    }

    /**
     * 주변 차 화면(지도 우선 화면, 함께 가는 중 달릴 때만 — 11-14): 속도(차량 신호, 시연 주행이면 대본 속도) + 앞길 표식 가운데 차로 그릴 것.
     * 허브가 아는 것만 넘긴다: 일행 · 차 사고·급정지·길 막힘(비상등 켠 차) · 그 밖의 위험(삼각 표지) · 뒤에서 오는 긴급차량. 휴게소·내 차 표식은 뺀다.
     * 기능 스위치 [aroundEnabled] — **기본 꺼짐**(11-14 17:12 대표 세션: 사용자 답을 기다리는 동안 꺼 둔다). 꺼져 있으면 null → 주행 화면은 예전 그대로(지도 전체 + 알약).
     * 켜기: 시연 빌드 훅 `--ez around true`
     */
    var aroundEnabled = false
    /** 최소 표시 첫 화면(시안 C, 11-14) 스위치 — 기본 켬. 끄면 예전 첫 화면 판(시연 빌드 훅 `--ez home_min false`). 차대차 판에만 */
    var homeMinimal = true
    // ---- 시연 주행 재생 막대(시연 판만, 11-14 사용자 "주행 시연할때 N배속" · "동영상 재생 처럼" · "해당 구간 누르면 뒤로가기도 되고 앞으로가기도")
    private val playRates = listOf(1.0, 2.0, 4.0, 8.0)
    /** 재생 막대를 화면에 그릴지 — 기본 켬. 녹화할 때 막대가 안 찍히게 끈다(시연 훅 `--ez player false`). 꺼도 시연 주행·배속(훅 ff)은 그대로 */
    var playerShown = true
    private var playRate = 1.0                              // 손으로 고른 배속 — 안내 중에도 그대로 간다. 세울 자리·끝 앞에서는 엔진이 ×1 로 내리고, 지나면 여기 값으로 다시 건다
    fun playToggle() { if (BuildConfig.DEMO_HOOKS) road.setSimPause(!road.simPaused) }   // 시연 판이 아니면 아무것도 안 한다(제출 판에서는 이 길이 통째로 빠진다)
    fun playSpeed() { if (!BuildConfig.DEMO_HOOKS) return; playRate = playRates[(playRates.indexOf(playRate) + 1) % playRates.size]; road.setFf(playRate, force = true); render() }
    fun playSeek(frac: Float) { if (!BuildConfig.DEMO_HOOKS) return; val (a, b) = road.simSpan() ?: return; road.simSeek(a + (b - a) * frac.coerceIn(0f, 1f)) }
    fun playStop() { if (BuildConfig.DEMO_HOOKS && road.simPlaying) unlink() }   // 막대의 ✕: 시연 주행 끝내기(사용자 "시연 영상 취소하는게 없네")
    private fun playTick() { if (!BuildConfig.DEMO_HOOKS) return; if (!road.simPlaying) playRate = 1.0 else if (playRate > 1.0 && !road.simPaused) road.setFf(playRate, force = true) }
    private fun playerUi(panel: Panel): ai.meeos.tago.ui.PlayerUi? {
        if (!BuildConfig.DEMO_HOOKS || !playerShown) return null
        val (a, b) = road.simSpan() ?: return null
        val len = b - a
        val pos = road.linkPos
        val marks = (panel as? Panel.RoadLink)?.strip?.markers.orEmpty().mapNotNull { m ->
            val danger = m.kind == MarkerKind.HAZARD || m.kind == MarkerKind.HAZARD_TOP
            if ((!danger && m.kind != MarkerKind.REST) || m.distM <= 0) null
            else ((pos + m.distM - a) / len).takeIf { it in 0.0..1.0 }?.let { ai.meeos.tago.ui.PlayMark(it.toFloat(), danger) }
        }
        val shown = "%.0f".format(if (playRate > 1.0) playRate else road.ff)
        return ai.meeos.tago.ui.PlayerUi(
            paused = road.simPaused, frac = ((pos - a) / len).coerceIn(0.0, 1.0).toFloat(),
            pos = t[R.string.player_pos, "%.1f".format((pos - a).coerceAtLeast(0.0) / 1000), "%.1f".format(len / 1000)],
            rate = t[R.string.player_rate, shown],
            note = if (playRate > 1.0 && road.ff <= 1.0 && !road.simPaused) t[R.string.player_hold] else null,
            marks = marks, playLabel = t[R.string.player_play], pauseLabel = t[R.string.player_pause],
            rateLabel = t[R.string.player_speed, shown], seekLabel = t[R.string.player_seek], stopLabel = t[R.string.player_stop])
    }

    private fun aroundUi(panel: Panel): ai.meeos.tago.ui.AroundUi? {
        if (!aroundEnabled || !BuildConfig.ROAD_LINK || BuildConfig.PICKUP || !road.active || s.car.stoppedKnown) return null
        val markers = (panel as? Panel.RoadLink)?.strip?.markers ?: return null
        val carSigns = setOf(R.drawable.hz_sign_crash, R.drawable.hz_sign_brake, R.drawable.hz_sign_blocked)
        val cars = markers.mapIndexedNotNull { i, m ->
            when (m.kind) {
                MarkerKind.MEMBER -> ai.meeos.tago.ui.AroundCar("m:${m.label}", "member", m.distM, m.label, m.distLabel)
                MarkerKind.SIREN -> ai.meeos.tago.ui.AroundCar("ev", "ev", m.distM, m.label, m.distLabel)
                MarkerKind.HAZARD, MarkerKind.HAZARD_TOP -> ai.meeos.tago.ui.AroundCar("h:$i:${m.label}", if (m.sign in carSigns) "hazard" else "sign", m.distM, "", m.distLabel,
                    if (m.tone == AlertTone.CAUTION) "caution" else "danger")
                else -> null
            }
        }
        return ai.meeos.tago.ui.AroundUi(kmh = Math.round(s.car.speedMs * 3.6f), unit = "km/h", cars = cars)
    }

    /** 내 차 → 위험 막대: 1 km 안이면 축 1 km, 넘으면 다음 km 까지(위험 점이 끝에 붙지 않게 4~96 %) */
    private fun alertBar(distM: Int): BarUi {
        val axis = if (distM < 1_000) 1_000 else (distM / 1_000 + 1) * 1_000
        return BarUi((distM.toFloat() / axis).coerceIn(0.04f, 0.96f), t[R.string.road_me], t.km(axis))
    }

    /** 거리 글("1.8km"·"300m")을 숫자와 단위로 나눈다 — 경고 화면 머리에서 숫자만 가장 크게 */
    private fun bigKm(m: Int): Pair<String, String> {
        val s = t.km(m)
        val i = s.indexOfFirst { it.isLetter() }
        return if (i <= 0) s to "" else s.substring(0, i) to s.substring(i)
    }

    /** 음성·차량 서비스가 못 붙었을 때 한 줄(V-04). 첫 시도가 실패해 다시 시도 중이거나 시작 10초가 지나도 안 붙었을 때만 — 부팅 직후 몇 초는 조용히 기다린다 */
    private fun serviceNotice(): String? {
        val late = android.os.SystemClock.elapsedRealtime() - startedAt > NOTICE_AFTER_MS
        val voiceDown = !s.voice.ttsUp && (s.voice.initAttempts >= 2 || late)
        val carDown = !(s.car.vehicleReady && s.car.speedReady) && (s.car.initAttempts >= 2 || late)
        return when {
            voiceDown && carDown -> t[R.string.notice_both_down]
            voiceDown -> t[R.string.notice_voice_down]
            carDown -> t[R.string.notice_car_down]
            else -> null
        }
    }

    private var mapUrl: String? = null
    /** 내장 서버가 떴는지(PC 서버면 처음부터). 뜨기 전에 미리보기 주소를 열면 WebView 에 오류 페이지가 남는다 */
    private var serverUp = !BuildConfig.EMBEDDED_SERVER

    /** MainActivity 가 내장 서버를 띄운 뒤 부른다: 대기 화면 지도를 미리보기로 채운다 */
    fun serverReady() { serverUp = true; if (mapUrl == null) setMap(null) }

    /** MainActivity: 내장 서버가 포트를 못 잡았다. 지도 자리에 이유와 할 일을 남기고(지도는 열지 않는다) 토스트로 한 번 알린다 */
    fun serverFailed() {
        val msg = t[R.string.err_server_port, BuildConfig.LOCAL_PORT]
        s.serverError = msg
        s.toast(msg)
        render()
    }

    /** 목적지 칸(차대차 판, 정차 중·연결 전): 기능이 꺼졌거나(키 없음·스위치) 달리는 중이면 없음 */
    private fun destUi(): ai.meeos.tago.ui.DestUi? {
        if (!BuildConfig.ROAD_LINK || BuildConfig.PICKUP || !road.destAvailable || road.active) return null
        // 11-14: 기어 D 로 서 있으면(에뮬레이터 기본) 예전엔 칸이 통째로 숨어 '목적지 검색이 없다'로 보였다 → 서 있으면 보이되 잠근다(달리는 중엔 판이 숨는다)
        val locked = !s.car.stoppedKnown
        if (locked && s.car.speedMs >= 1f) return null
        if (locked && destOpen) destOpen = false
        val d = road.dest
        return ai.meeos.tago.ui.DestUi(title = t[R.string.dest_title], chosen = d?.name,
            chosenSub = d?.let { dd -> road.realRoute?.let { p -> t[R.string.dest_real, "%.1f".format(p.distM / 1000.0), maxOf(1, (p.timeS + 30) / 60)] }
                ?: if (road.destBusy) t[R.string.dest_routing] else if (dd.nearDemo) t[R.string.dest_ahead, "%.0f".format(kotlin.math.max(0.0, dd.s - road.startS) / 1000)] else null },
            open = destOpen, hint = t[R.string.dest_hint], searchLabel = t[R.string.btn_dest_search], clearLabel = t[R.string.btn_dest_clear],
            closeLabel = t[R.string.btn_dest_close], openLabel = t[R.string.btn_dest_open], busy = road.destBusy, note = destVoiceNote ?: road.destNote, query = destQuery,
            results = road.destCands.orEmpty().map { c -> ai.meeos.tago.ui.DestRow(c.name, listOfNotNull(if (c.onRoute) null else t[R.string.dest_off_tag], c.addr.takeIf { it.isNotBlank() },
                road.realHere()?.let { h -> t[R.string.dest_from_here, "%.1f".format(geoKm(h.lat, h.lon, c.lat, c.lon))] }   // 실제 위치 모드: 여기서 곧은 거리
                    ?: if (c.onRoute) t[R.string.dest_ahead, "%.0f".format(kotlin.math.max(0.0, c.s - road.startS) / 1000)] else null).joinToString(" · "), c.onRoute) },
            prompt = t[R.string.dest_prompt], locked = locked, lockNote = t[R.string.dest_locked],
            mic = t[R.string.dest_mic], listening = destListening,
            listenText = if (destListening) s.voice.heard?.takeIf { it.isNotBlank() } ?: t[R.string.dest_listening] else null)
    }

    /** 설정 판(지도 우선 화면, 첫 화면에서만). 주행 중이면 잠금(화면이 닫는다). 항목은 실제로 동작하는 것만 — SettingsUi 설명 */
    private fun settingsUi(): ai.meeos.tago.ui.SettingsUi? {
        if (!BuildConfig.ROAD_LINK || BuildConfig.PICKUP) return null
        val park = parking.enabled && s.car.stoppedKnown && parking.line() != null
        return ai.meeos.tago.ui.SettingsUi(title = t[R.string.settings_title], closeLabel = t[R.string.settings_back], locked = !s.car.stoppedKnown, lockNote = t[R.string.set_locked],
            realLocLabel = t[R.string.set_real_loc], realLocSub = t[R.string.set_real_loc_sub], realLocOn = road.realLocEnabled,
            parkClearLabel = if (park) t[R.string.set_park_clear] else null, parkClearBtn = t[R.string.set_park_clear_btn],
            studyLabel = if (BuildConfig.STUDY_LOG) t[R.string.set_study] else null, studyState = if (BuildConfig.STUDY_LOG) t[if (study.active) R.string.set_on else R.string.set_off] else null,
            licensesLabel = t[R.string.set_licenses], version = t[R.string.set_version, BuildConfig.APP_LABEL, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE],
            autoLinkLabel = t[R.string.set_auto_link], autoLinkSub = t[R.string.set_auto_link_sub], autoLinkOn = linkConsent && autoLink,
            withdrawLabel = if (linkEdition && linkConsent) t[R.string.set_withdraw] else null, withdrawSub = t[R.string.set_withdraw_sub], withdrawBtn = t[R.string.set_withdraw_btn])
    }

    /** 대기 화면 지도: 길벗 판은 시연 경로 미리보기(경로·휴게소·사고 다발 구간만, 폴링 없음). 픽업이 있는 판은 빈 상태 그대로 */
    private fun idleMap(): String? = if (serverUp && BuildConfig.ROAD_LINK && !BuildConfig.PICKUP) "${BuildConfig.SERVER_URL}/link/road?car=1&preview=1&camui=app&s=${road.startS.toInt()}${if (road.realLocEnabled) "&here=1" else ""}${road.rvParam()}${road.destParam()}" else null   // s = 연결 시작 자리(첫 화면 내 위치)

    /** 지도 페이지: 픽업 /car/<세션>, 함께 가기 /link/…, 끝나면 null → 대기 지도(길벗은 경로 미리보기, 그 밖은 빈 상태) */
    private fun setMap(url: String?) {
        mapUrl = url ?: idleMap()
        onMapUrl(mapUrl)
        render()
    }

    /** 위험 종류 → 주의 표지 그림(지도 link.html 의 HZ[].g 와 같은 짝) */
    private fun hazardSign(type: String) = when (type) {
        "INCIDENT" -> R.drawable.hz_sign_crash; "HARD_BRAKE" -> R.drawable.hz_sign_brake; "CONGESTION" -> R.drawable.hz_sign_jam
        "ICING" -> R.drawable.hz_sign_ice; "HEAVY_RAIN" -> R.drawable.hz_sign_rain; "BLOCKED" -> R.drawable.hz_sign_blocked
        "DEBRIS" -> R.drawable.hz_sign_debris; "SMOKE" -> R.drawable.hz_sign_smoke; "DOWNHILL" -> R.drawable.hz_sign_downhill
        "SIREN" -> R.drawable.hz_sign_siren; "CHARGER_FAULT" -> R.drawable.hz_sign_charger_fault; "CHARGER_WAIT" -> R.drawable.hz_sign_charger_wait
        else -> R.drawable.hz_sign_official
    }

    private fun memberIcon(type: String) = when (type) { "child" -> R.drawable.ic_child; "senior" -> R.drawable.ic_senior; else -> R.drawable.ic_adult }

    /** 함께 가기 패널: 앞길 세로 스트립 — 가까운 거리 축(앞 [STRIP_AHEAD_M]·뒤 2km)에 내 차·위험(앞 3개)·뒤에서 오는 긴급차량·일행·다음 휴게소를 놓는다.
     *  축 밖(더 먼 앞)은 지금 안내 중인 위험과 일행만 축 맨 위에 붙인다(거리 글은 실제 값). 휴게소·다른 위험은 빼고, 헤드라인·보조 줄이 알려 준다.
     *  축 색은 500m 칸마다 앞차 평균 속도(초록·주황·빨강), 비(파랑), 위험(빨강·보라) */
    private fun roadPanel(v: JSONObject?, q: Question?, topHazardId: String?, stale: Boolean = false): Panel.RoadLink {
        val bins = v?.optJSONObject("ahead")?.optJSONArray("bins")?.let { arr ->
            (0 until minOf(arr.length(), STRIP_AHEAD_M / 500)).map { i ->
                val b = arr.getJSONObject(i)
                val first = b.optJSONArray("hz")?.optString(0) ?: ""
                when {
                    first in setOf("INCIDENT", "BLOCKED", "HARD_BRAKE", "DEBRIS") -> BinColor.DANGER
                    first in setOf("SMOKE", "DOWNHILL") -> BinColor.PURPLE
                    b.optInt("rain") > 0 -> BinColor.RAIN
                    b.isNull("kmh") -> BinColor.UNKNOWN
                    b.optInt("kmh") < 40 -> BinColor.SLOW
                    b.optInt("kmh") < 70 -> BinColor.MEDIUM
                    else -> BinColor.FREE
                }
            }
        } ?: emptyList()
        val markers = mutableListOf<StripMarker>()
        markers += StripMarker(0, MarkerKind.ME, R.drawable.ic_car, "", t[R.string.road_me], null)
        val hz = v?.optJSONArray("hazards")
        var shown = 0
        if (hz != null) for (i in 0 until hz.length()) {
            val h = hz.getJSONObject(i)
            if (h.optString("type") == "SIREN" && h.has("say")) {   // 뒤에서 다가오는 긴급차량(앞 위험 개수에 안 센다)
                val d = -kotlin.math.abs(h.optInt("ahead"))
                val sub = if (h.optBoolean("beacon")) (if (h.optInt("cars") > 0) t[R.string.siren_cars, h.optInt("cars")] else null) else if (h.optBoolean("yieldOnly")) t[R.string.sl_siren_yield, h.optInt("yielded")] else t[R.string.siren_cars, h.optInt("cars")]
                markers += StripMarker(d, MarkerKind.SIREN, R.drawable.ic_flag, t[R.string.behind_distance, t.km(d)], road.hazardLabel(h), sub, sign = R.drawable.hz_sign_siren, tone = AlertTone.SIREN)
                continue
            }
            if (!h.optBoolean("confirmed") || h.isNull("ahead") || h.optInt("ahead") <= 0 || shown >= 3) continue
            val top = h.optString("id") == topHazardId
            if (h.optInt("ahead") > STRIP_AHEAD_M && !top) continue   // 축 밖: 지금 안내 중인 위험만 맨 위에 붙인다
            shown++
            val rain = h.optString("type") == "HEAVY_RAIN" || h.optBoolean("flood")
            val kind = if (top) MarkerKind.HAZARD_TOP else MarkerKind.HAZARD
            markers += StripMarker(h.optInt("ahead"), kind, if (rain) R.drawable.ic_rain else R.drawable.ic_flag, t.km(h.optInt("ahead")), road.hazardLabel(h), t[R.string.n_cars, h.optInt("cars")],
                sign = if (h.optBoolean("flood")) R.drawable.hz_sign_rain else hazardSign(h.optString("type")),
                tone = when (h.optString("type")) { "INCIDENT", "BLOCKED", "HARD_BRAKE", "DEBRIS" -> AlertTone.DANGER; "SIREN" -> AlertTone.SIREN; else -> AlertTone.CAUTION })
        }
        v?.optJSONObject("group")?.optJSONArray("members")?.let { a ->
            for (i in 0 until a.length()) {
                val mb = a.getJSONObject(i)
                if (mb.optBoolean("me") || mb.isNull("gap")) continue
                val gap = mb.optInt("gap")
                val soc = if (mb.isNull("soc")) null else t[R.string.battery_pct, mb.optInt("soc")]
                val st = when (mb.optString("status")) { "stopped" -> t[R.string.member_stopped]; "slow" -> t[R.string.member_slow]; else -> null }
                val name = mb.optString("name") + if (mb.optString("kind") == "web") t[R.string.member_phone] else ""
                val distLabel = if (gap >= 0) t.km(gap) else t[R.string.behind_distance, t.km(gap)]
                // 일행의 주차 글(층·기둥만 — 허브 그룹 보기의 park, 11-14 시연 4막 "주차장에 같이"): 있으면 "주차 B2 · C-7" 을 상태 글 자리에
                val parked = if (mb.isNull("park")) null else mb.optString("park").takeIf { it.isNotBlank() }?.let { t[R.string.member_parked, it] }
                markers += StripMarker(gap, MarkerKind.MEMBER, R.drawable.ic_people, distLabel, name, listOfNotNull(parked ?: st, soc).joinToString(" · ").ifEmpty { null })
            }
        }
        v?.optJSONObject("restNext")?.takeIf { it.optInt("dist") <= STRIP_AHEAD_M }?.let { r -> markers += StripMarker(r.optInt("dist"), MarkerKind.REST, R.drawable.ic_cafe, t.km(r.optInt("dist")), r.optString("name"), null, sign = R.drawable.hz_sign_rest) }
        return Panel.RoadLink(
            strip = if (v == null) null else RoadStripUi(aheadM = STRIP_AHEAD_M, behindM = 2_000, bins = bins, markers = markers, aheadLabel = t[R.string.ribbon_label]),
            connecting = if (stale) t[R.string.link_reconnecting] else if (v == null) t[R.string.road_connecting] else null,
            primaryLabel = if (road.hasGroup) t[R.string.btn_rest_together] else t[R.string.btn_link_family],
            primaryIcon = if (road.hasGroup) R.drawable.ic_cafe else R.drawable.ic_people,
            endLabel = t[R.string.btn_end],
            showButtons = q?.kind != Question.Kind.REST && q?.kind != Question.Kind.CHARGER,
            // 충전소(휴게소 services) 반경 500 m 안에 서 있을 때만(허브 restHere). 질문 중·주행 중·반경 밖에서는 숨김
            chargeLabel = if (BuildConfig.ROAD_LINK && s.car.stoppedKnown && q?.kind != Question.Kind.CHARGER && road.nearCharger)
                t[R.string.btn_charger_report] else null,
            detourLabel = if (BuildConfig.ROAD_LINK && q?.kind != Question.Kind.CHARGER) road.detourLabel() else null,
            wrongLabel = if (q == null && road.canFlagWrong()) t[R.string.btn_alert_wrong] else null,
            summary = if (road.arrivedNow() && road.summary.worthShowing()) summaryCard() else null,
            parking = parkingUi(v),
        )
    }

    /** 주차 위치 한 줄(정차 중에만 — 기어를 알고 주행이 아닐 때). 기록이 없으면 null */
    private fun parkingUi(v: JSONObject? = null): ai.meeos.tago.ui.ParkingUi? {
        if (!parking.enabled || !s.car.stoppedKnown) return null
        val line = parking.line() ?: return null
        val edit = if (parking.editing) ai.meeos.tago.ui.ParkingEditUi(t[R.string.btn_park_up], t[R.string.btn_park_down], t[R.string.btn_park_clear], t[R.string.btn_park_done]) else null
        // 일행의 주차 한 줄(함께 가는 중에만): 주차 글이 있는 일행 수를 센 값 그대로. 모두 150 m 안이면 "가까이" — 허브는 건물을 모르므로 "같은 주차장"이라고 쓰지 않는다
        val gaps = v?.optJSONObject("group")?.optJSONArray("members")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("me") && !it.isNull("park") && it.optString("park").isNotBlank() }.map { if (it.isNull("gap")) null else kotlin.math.abs(it.optInt("gap")) } }.orEmpty()
        val mates = if (gaps.isEmpty()) null else t[if (gaps.all { it != null && it <= PARK_NEAR_M }) R.string.park_mates_near else R.string.park_mates, gaps.size]
        return ai.meeos.tago.ui.ParkingUi(line, t[if (parking.editing) R.string.btn_park_done else R.string.btn_park_fix], edit, parking.card(), mates)
    }

    /** 이번 주행 요약(정차 중에만): 알림·확인·틀림 건수와 걸린 시간. 집계일 뿐 판정에는 쓰이지 않는다 */
    private fun summaryCard(): CardUi {
        val sm = road.summary
        return CardUi(CardTone.INFO, t[R.string.sum_title], R.drawable.ic_flag, listOf(
            CardLine(null, t[R.string.sum_alerts, sm.alertCount, sm.confirmedCount]),
            CardLine(null, t[R.string.sum_wrong, sm.wrongCount]),
            CardLine(R.drawable.ic_timer, t[R.string.sum_minutes, sm.minutes(System.currentTimeMillis())]),
        ))
    }

    /** 충전기 고장 신고 1분 설문 한 화면: 지금 문항의 그림 타일(ChargeSurvey.OPTIONS 순서). 기기 번호는 숫자 타일 */
    private fun chargeUi(sv: ai.meeos.tago.road.ChargeSurvey): ai.meeos.tago.ui.ChargeReportUi {
        val key = sv.key ?: "alt"
        val ids = ai.meeos.tago.road.ChargeSurvey.OPTIONS.getValue(key)
        val tiles = ids.map { id ->
            if (key == "unit") ai.meeos.tago.ui.SurveyTileUi(id, id, null)
            else CS_LABEL.getValue("${key}_$id").let { (label, icon) -> ai.meeos.tago.ui.SurveyTileUi(id, t[label], icon) }
        }
        val q = mapOf("sym" to R.string.cs_q_sym, "unit" to R.string.cs_q_unit, "place" to R.string.cs_q_place, "broken" to R.string.cs_q_broken, "alt" to R.string.cs_q_alt)
        return ai.meeos.tago.ui.ChargeReportUi(t[R.string.cs_progress, sv.step + 1, sv.keys.size], t[q.getValue(key)], tiles,
            columns = when (key) { "unit" -> 4; "broken" -> 4; else -> 3 }, sel = sv.answers[key] ?: "",
            backLabel = if (sv.step > 0) t[R.string.btn_back] else null, skipLabel = t[R.string.btn_skip_unknown], closeLabel = t[R.string.btn_close])
    }

    private fun orderCard(nb: ai.meeos.tago.pickup.NearbyOffers): CardUi {
        val store = nb.orderPlace?.optString("name") ?: ""
        val lines = mutableListOf<CardLine>()
        nb.orderItems.forEach { (q, n) -> lines += CardLine(null, t[R.string.order_item, n, q]) }
        if (nb.offerKind == "takeout") lines += CardLine(R.drawable.ic_timer, t[R.string.order_card_ready, (nb.orderPlace?.optInt("prepSec") ?: 300) / 60, t.mins(nb.orderPlace?.optInt("carToStoreSec") ?: 0)])
        lines += CardLine(null, if (nb.offerKind == "takeout") t[R.string.order_card_pay_takeout] else t[R.string.order_card_pay_drivethru])
        return CardUi(CardTone.INFO, if (nb.offerKind == "takeout") t[R.string.order_card_takeout, store, nb.orderNo] else t[R.string.order_card_drivethru, store, nb.orderNo], R.drawable.ic_cafe, lines)
    }

    /** 개발 패널 한 줄(한국어 고정 — 운전자용 글이 아니다) */
    private fun devStatus(): String {
        val car = s.car
        val seat = s.region.riderSeat
        return "${if (car.locked) (if (car.driving) "주행" else "모름(잠금)") else "정차"} · ${"%.0f".format(car.speedMs * 3.6)}km/h · 와이퍼 ${car.wiper} · 상태 ${pickup.status}" +
            "\n${t.seatLabel(seat)}: ${if (car.seatOcc[seat] == true) "착석" else "빈자리"} · 벨트 ${if (pickup.beltOf(seat) == true) "체결" else "-"} · 도어 ${if (car.doorIsOpen(s.region.curbDoor)) "열림" else "닫힘"}"
    }
}

/** 서비스 못 씀 안내를 띄우기까지 기다리는 시간(ms) — 시동 직후 서비스가 뜨는 몇 초는 조용히 */
private const val NOTICE_AFTER_MS = 10_000L

/** 앞길 스트립의 앞쪽 축 길이(m). 가까운 곳만 보인다 — 먼 위험은 헤드라인이, 다음 휴게소는 보조 줄이 알려 준다(docs/ui-design.md §5) */
private const val STRIP_AHEAD_M = 3_000

/** 1분 설문 타일(문항_답 → 글자·그림). 그림은 GPT Image 2.6 시트를 잘라 넣은 3D 렌더 그림(res/drawable-nodpi/cs_*.png, 투명 바탕) */
private val CS_LABEL = mapOf(
    "sym_no_start" to (R.string.cs_sym_no_start to R.drawable.cs_sym_no_start), "sym_screen_off" to (R.string.cs_sym_screen_off to R.drawable.cs_sym_screen_off),
    "sym_payment" to (R.string.cs_sym_payment to R.drawable.cs_sym_payment), "sym_connector" to (R.string.cs_sym_connector to R.drawable.cs_sym_connector),
    "sym_notice" to (R.string.cs_sym_notice to R.drawable.cs_sym_notice),
    "place_entrance" to (R.string.cs_place_entrance to R.drawable.cs_place_entrance), "place_middle" to (R.string.cs_place_middle to R.drawable.cs_place_middle),
    "place_end" to (R.string.cs_place_end to R.drawable.cs_place_end),
    "broken_1" to (R.string.cs_broken_1 to R.drawable.cs_broken_1), "broken_2" to (R.string.cs_broken_2 to R.drawable.cs_broken_2),
    "broken_3" to (R.string.cs_broken_3 to R.drawable.cs_broken_3), "broken_all" to (R.string.cs_broken_all to R.drawable.cs_broken_all),
    "alt_yes" to (R.string.cs_alt_yes to R.drawable.cs_alt_yes), "alt_no" to (R.string.cs_alt_no to R.drawable.cs_alt_no), "alt_wait" to (R.string.cs_alt_wait to R.drawable.cs_alt_wait),
)
