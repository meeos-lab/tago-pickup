package ai.meeos.tago.voice

import ai.meeos.tago.app.AppLog
import ai.meeos.tago.app.InitRetry
import ai.meeos.tago.app.Region
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 온디바이스 음성(Pleos TTS/STT). 말하기는 줄을 세워 한 문장씩(끊김 방지), 질문을 마친 뒤에만 듣는다(운전자는 '응' 한마디로 끝).
 * 상태 변화(말하는 중·듣는 중)는 콜백으로 알려 화면(VoiceChip)과 지도(WebView 타이머)가 따라간다.
 */
/** 앱 안내 음성(TTS)이 지금 나오는 중인지. VoiceAssistant 가 쓰고 버튼 누름 피드백(ui/components PressFeedback)이 읽는다 */
object SpeechState {
    @Volatile var speaking = false
        internal set
}

class VoiceAssistant(private val context: Context, private val main: Handler, private val log: AppLog) {
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    @Volatile private var ttsReady = false   // SDK 초기화 콜백(스레드 미확인)에서 쓰고 메인에서 읽는다
    @Volatile private var sttReady = false
    @Volatile private var released = false   // 해제 뒤 미뤄 둔 작업(포커스 재시도·막힘 감시·듣기 끝)이 돌지 않게

    @Volatile var speaking = false
        private set(v) { field = v; SpeechState.speaking = v }
    var listening = false
        private set(v) { if (v && !field) ListenLevel.start() else if (!v && field) ListenLevel.stop(); field = v }   // 소리 막대·카운터(말로 검색)
    /** 듣는 동안 들린 말(부분 인식 포함). 화면에 보여 준다 */
    var heard: String? = null
        private set

    // 말하는 중에 다음 문장이 오면 끊지 않고 줄 세운다(새 speak가 앞 문장을 끊어 '끊겨 들리던' 문제). 오래된 것부터 버려 최대 3개.
    // 긴급 안내는 오래 기다리면 말하기 직전에 최신 상태로 다시 만들고, 못 말하고 버려지면 부른 쪽에 알린다(SpeechQueue)
    private val queue = SpeechQueue()
    private var speakStartedAt = 0L

    // Pleos 오디오 포커스 정책: 안내 음성은 내비 안내(NAVIGATION) 용도로, 음악 위에서는 음악을 줄이고(duck) 말한다.
    // Gleo·시스템 화면(깜빡이 연동 카메라 등)이 잡고 있으면 포커스가 거절된다(R) — 1초마다 다시 청한다(최대 FOCUS_WAIT_MS), 그동안 문장은 줄에 남고
    // 앱이 다시 앞에 오면(onForeground) 바로 다시 청한다
    private val audio = context.getSystemService(AudioManager::class.java)
    private val voiceAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusReq: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(voiceAttrs)
        .setOnAudioFocusChangeListener({ change ->
            // 다른 음성(Gleo 호출 등)이 잠깐 가져가면 바로 멈춘다 — 끝난 뒤 남은 문장은 다시 포커스를 받아 이어 말한다
            if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS) {
                if (speaking) { currentItem?.let { if (queue.first() !== it) queue.addFirst(it) }; runCatching { tts?.stop() } }
            }
        }, main)
        .build()
    private var focusSince = 0L          // 포커스를 처음 거절당한 시각(0 = 거절 중 아님)
    private var retryPending = false     // 포커스 재시도가 이미 예약돼 있는지(겹쳐 예약하지 않는다)
    private var announced = false         // onSpeakingChanged(true) 를 보냈는지
    private var currentItem: SpeechQueue.Item? = null
    private val current: String? get() = currentItem?.text
    /** 지금 말하고 있는 문장(연구 기록이 경고 음성의 실제 시작·끝 시각을 맞춘다) */
    val currentText: String? get() = current
    private var listenSeq = 0

    /** 질문을 마친 뒤 들을지(대기 중인 질문이 있는지) */
    var shouldListen: () -> Boolean = { false }
    /** 듣기 직전: 마이크를 쓰는 다른 기능(사이렌 감지)을 쉬게 한다 */
    var beforeListen: () -> Unit = {}
    var onSpeakingChanged: (Boolean) -> Unit = {}
    var onListeningChanged: () -> Unit = {}
    /** 최종 인식 결과 */
    var onUtterance: (String) -> Unit = {}

    private var ttsRetry: InitRetry? = null
    private var sttRetry: InitRetry? = null
    /** 음성 합성(TTS)이 준비됐나 — 아니면 화면이 '음성 안내를 쓸 수 없음'을 띄운다(V-04) */
    val ttsUp: Boolean get() = ttsReady
    /** 음성 서비스 시도 횟수(2 이상이면 첫 시도가 실패) */
    val initAttempts: Int get() = maxOf(ttsRetry?.attempts ?: 0, sttRetry?.attempts ?: 0)
    /** 시연 훅 init_fail: 이 시각(elapsedRealtime ms)까지는 서비스 연결이 실패한 것처럼 군다(늦게 뜨는 서비스 재현) */
    @Volatile var simInitFailUntil = 0L
    private fun simFail() = android.os.SystemClock.elapsedRealtime() < simInitFailUntil

    /**
     * 서비스가 붙는 것은 비동기라, 지역은 그때(onSuccess) 다시 읽는다 — 저장된 지역이 대치동이 아닐 때 한국어 화자로 굳지 않게.
     * 연결에 실패하면 5초마다 다시 시도한다(시동 직후 음성 서비스가 늦게 뜨는 경우, V-04) — 전에는 한 번 실패하면 그 주행 내내 말이 없었다
     */
    fun init(regionProvider: () -> Region) {
        val sched: (Long, () -> Unit) -> Unit = { d, f -> main.postDelayed(f, d) }
        ttsRetry = InitRetry(schedule = sched) { r -> connectTts(r, regionProvider) }.also { it.start() }
        sttRetry = InitRetry(schedule = sched) { r -> connectStt(r, regionProvider) }.also { it.start() }
    }

    private fun connectTts(r: InitRetry, regionProvider: () -> Region) {
        if (released) return
        var made: TextToSpeech? = null
        try {
            if (simFail()) throw IllegalStateException("시연: 서비스 연결 실패")
            val t = TextToSpeech(context, TtsMode.ON_DEVICE); made = t
            t.initialize(object : TtsInit {
                override fun onSuccess() {
                    if (released) { runCatching { t.release() }; return }
                    tts = t
                    val region = regionProvider()
                    t.setLocale(region.locale)
                    runCatching { t.setSpeakerType(speaker(region)) }
                    runCatching { t.setAudioAttributes(voiceAttrs) }   // 기본값(USAGE_MEDIA)이면 미디어 음량·포커스를 따라 안내가 묻힌다
                    t.addEventListener(ttsListener)
                    ttsReady = true
                    r.succeeded()
                    if (r.attempts > 1) log.d("TTS 연결됨(${r.attempts}번째 시도)")
                    main.post { if (!speaking && !queue.isEmpty()) speakNext() }   // 준비 전에 온 안내를 버리지 않고 이제 말한다
                }

                override fun onFailure(errorType: TtsError) {
                    log.d("TTS 실패(${r.attempts}번째 시도, 5초 뒤 재시도): $errorType")
                    main.post { runCatching { t.release() }; r.failed() }
                }
            })
        } catch (e: Throwable) {
            runCatching { made?.release() }
            log.d("음성 초기화 예외(TTS ${r.attempts}번째 시도, 5초 뒤 재시도): $e")
            r.failed()
        }
    }

    private fun connectStt(r: InitRetry, regionProvider: () -> Region) {
        if (released) return
        var made: SpeechToText? = null
        try {
            if (simFail()) throw IllegalStateException("시연: 서비스 연결 실패")
            val s = SpeechToText(context, SttMode.ON_DEVICE); made = s
            s.initialize(object : SttInit {
                override fun onSuccess() {
                    if (released) { runCatching { s.release() }; return }
                    stt = s
                    s.setLocale(regionProvider().locale)
                    s.addListener(sttListener)
                    sttReady = true
                    r.succeeded()
                    if (r.attempts > 1) log.d("STT 연결됨(${r.attempts}번째 시도)")
                }

                override fun onFailure(errorType: SttError) {
                    log.d("STT 실패(${r.attempts}번째 시도, 5초 뒤 재시도): $errorType")
                    main.post { runCatching { s.release() }; r.failed() }
                }
            })
        } catch (e: Throwable) {
            runCatching { made?.release() }
            log.d("음성 초기화 예외(STT ${r.attempts}번째 시도, 5초 뒤 재시도): $e")
            r.failed()
        }
    }

    /** 언어별 화자(Pleos TTS: 한국어 seoyoon 등, 영어 lyra·finley). 영어에 한국어 화자를 두면 기본 화자로 바뀐다 */
    private fun speaker(region: Region) = if (region.lang == "ko") "seoyoon" else "lyra"

    fun setRegion(region: Region) {
        runCatching { tts?.setSpeakerType(speaker(region)) }
        runCatching { tts?.setLocale(region.locale) }
        runCatching { stt?.setLocale(region.locale) }
    }

    /**
     * 한 문장을 말한다(줄이 비었으면 바로). [key] 가 같은 대기 문장은 이 문장이 대신하고, [fresh] 가 있으면 오래 기다린 뒤 말하기 직전에
     * 최신 문장으로 바꾼다(null = 이제 필요 없음), 포커스를 끝내 못 얻어 버려지면 [onDropped] 를 부른다
     */
    fun say(text: String, key: String? = null, fresh: (() -> String?)? = null, onDropped: (() -> Unit)? = null) {
        log.demo("[음성] $text")   // 문장에는 아이 이름·만날 곳이 들어 있다 — 제품 빌드 로그에는 남기지 않는다
        val item = SpeechQueue.Item(text, key, fresh, onDropped)
        main.post {
            if (released) return@post
            val now = System.currentTimeMillis()
            queue.add(item, now)   // 늘 줄을 거친다 — 포커스를 기다리는 앞 문장이 있으면 그 뒤가 아니라 그 앞질러 말하지 않게(순서·같은 key 중복 방지)
            // 15초 넘게 끝 신호가 없으면 막힌 것으로 보고 그냥 말한다
            if (ttsReady && (!speaking || now - speakStartedAt >= STUCK_MS)) speakNext()
        }
    }

    /** 앱이 다시 앞에 왔다(깜빡이 연동 카메라·다른 앱이 닫힘): 포커스를 기다리던 문장을 바로 다시 청한다 */
    fun onForeground() {
        main.post { if (!released && ttsReady && !speaking && !queue.isEmpty()) speakNext() }
    }

    private fun speakNow(item: SpeechQueue.Item) {
        if (!requestFocus()) {
            // 포커스 거절(Gleo 말하는 중·시스템 화면 등): 문장을 줄 맨 앞에 두고 1초 뒤 다시, FOCUS_WAIT_MS 가 지나면 포기하고 알린다
            queue.addFirst(item)
            val now = System.currentTimeMillis()
            if (focusSince == 0L) focusSince = now
            if (now - focusSince < FOCUS_WAIT_MS) {
                if (!retryPending) {
                    retryPending = true
                    main.postDelayed({ retryPending = false; if (!released && !speaking && !queue.isEmpty()) speakNext() }, FOCUS_RETRY_MS)
                }
            } else { log.d("오디오 포커스를 ${FOCUS_WAIT_MS / 1000}초 넘게 받지 못해 안내 ${queue.size}개를 건너뜀"); queue.clear(); focusSince = 0L; announceIdle() }
            return
        }
        focusSince = 0L; currentItem = item
        val text = item.text
        speaking = true; speakStartedAt = System.currentTimeMillis()
        val startedAt = speakStartedAt
        runCatching { tts?.speak(text) }.onFailure {
            log.d("TTS 말하기 예외: $it"); speaking = false; item.onDropped?.invoke()   // 말하지 못한 문장 — 부른 쪽이 '말했다' 기록을 되돌린다
            main.post { if (!released && !speaking) finished() }                       // 줄에 남은 문장이 멈추지 않게
        }
        // 끝 신호(onDone/onStop)가 15초 넘게 없으면 막힌 것으로 보고 끝낸다 — 지도 타이머가 영영 멈추지 않게
        main.postDelayed({ if (!released && speaking && speakStartedAt == startedAt) { log.d("TTS 끝 신호 없음 — 강제 종료"); finished() } }, STUCK_MS)
    }

    /** 줄 선 문장이 있으면 이어서 말한다 — true면 이번 끝은 질문의 끝이 아니다 */
    private fun speakNext(): Boolean {
        val next = queue.poll(System.currentTimeMillis()) ?: run { focusSince = 0L; return false }   // 줄이 비면 포커스 대기 기록도 끝낸다(다음 문장이 옛 대기 시각으로 바로 버려지지 않게)
        speakNow(next); return true
    }

    private fun finished() {
        speaking = false
        if (speakNext()) return
        abandonFocus()
        announceIdle()
    }

    /** 연구 기록 등에 '말하는 중' 알림을 보냈다면 끝을 알린다(포커스를 기다리다 문장을 버린 경로에서도 끝이 빠지지 않게) */
    private fun announceIdle() { if (announced) { announced = false; onSpeakingChanged(false) } }

    private fun requestFocus(): Boolean = runCatching { audio?.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_FAILED }.getOrDefault(true)
    private fun abandonFocus() { runCatching { audio?.abandonAudioFocusRequest(focusReq) } }

    private val ttsListener = object : EventListener {
        override fun onStart() { main.post { if (released) return@post; speaking = true; announced = true; onSpeakingChanged(true); android.util.Log.d(AppLog.TAG, "TTS 시작") } }
        override fun onReady() = Unit
        override fun onStop() { main.post { if (!released) finished() } }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: TtsError) { log.d("TTS 오류: $errorType"); main.post { if (!released) finished() } }
        override fun onDone() {
            main.post {
                if (released) return@post
                android.util.Log.d(AppLog.TAG, "TTS 끝 (대기 ${queue.size})")
                speaking = false
                if (speakNext()) return@post                  // 줄 선 문장부터
                abandonFocus()
                announceIdle()
                if (shouldListen() && sttReady) listen()      // 질문을 마친 뒤에만 듣는다
            }
        }
    }

    private fun listen() {
        if (onceText != null) finishOnce(OnceFail.BUSY)   // 질문의 답 듣기가 말로 검색보다 먼저다
        beforeListen()
        listening = true; heard = null
        onListeningChanged()
        runCatching { stt?.request() }.onFailure { listening = false; onListeningChanged() }
        val seq = ++listenSeq
        main.postDelayed({ if (!released && listening && seq == listenSeq) { log.d("20초 동안 답이 없어 듣기를 멈춤(버튼으로 답할 수 있음)"); stopListening() } }, LISTEN_MS)
    }

    // ---- 말로 검색(11-14 사용자 "검색에 stt 기능도 넣어줘"): 질문 없이 한 번 듣고, 끝난 말은 onUtterance(답 해석) 대신 [listenOnce] 를 부른 쪽에 준다
    private var onceText: ((String) -> Unit)? = null
    private var onceFail: ((OnceFail) -> Unit)? = null
    /** 한 번 듣기가 실패한 까닭: 말이 없음 · 알아듣지 못함 · 마이크를 못 씀 · 안내를 말하는 중 · 음성 인식 서비스 없음 · 그 밖의 오류 */
    enum class OnceFail { SILENT, NO_MATCH, MIC, BUSY, DOWN, ERROR }

    /**
     * 말로 검색: 지금 바로 한 번 듣는다(질문 경로와 같은 Pleos STT, 같은 마이크 양보 beforeListen). 안내를 말하는 중이거나 질문을 기다리는 중이면
     * 듣지 않고 BUSY(안전 안내를 끊지 않는다). [ONCE_MS] 안에 끝난 말이 없으면 SILENT. 듣기 시작했으면 true
     */
    fun listenOnce(onText: (String) -> Unit, onFail: (OnceFail) -> Unit): Boolean {
        if (released) return false
        if (!sttReady || stt == null) { onFail(OnceFail.DOWN); return false }
        if (speaking || listening) { onFail(OnceFail.BUSY); return false }
        onceText = onText; onceFail = onFail
        beforeListen()
        listening = true; heard = null
        onListeningChanged()
        val r = runCatching { stt?.request() }
        if (r.isFailure) { log.d("STT 요청 예외(말로 검색): ${r.exceptionOrNull()}"); finishOnce(OnceFail.ERROR); return false }
        val seq = ++listenSeq
        main.postDelayed({ if (!released && listening && seq == listenSeq && onceText != null) { runCatching { stt?.stop() }; finishOnce(OnceFail.SILENT) } }, ONCE_MS)
        return true
    }

    /**
     * 시연·시험 훅 전용(stt_wav): 마이크 대신 녹음(16 kHz 모노 16비트 PCM, 머리 없음)을 같은 Pleos 인식 엔진에 넣는다(SDK sendAudio → completeAudioSend).
     * 에뮬레이터 PC 의 녹음 장치에 소리가 안 들어와(11-14 실측 −97 dBFS) 말소리 → 글자 → 검색을 확인할 다른 길이 없어서 둔다.
     * request()(마이크 열기)는 부르지 않는다 — 마이크 녹음과 같이 넣으면 엔진이 오류로 끝났다(11-14). 실시간처럼 0.1초 분량씩 보낸다.
     * [play] 면 같은 조각을 스피커(미디어)로도 튼다 — 시연 판 마이크 단추(11-15): 듣는 사람이 엔진에 들어가는 소리를 그대로 듣는다
     */
    /** 지금 스피커에서 실제로 나오는 자리(바이트). 재생 타임스탬프(AudioTimestamp: 그 시각에 내보낸 프레임)로 지금 시각까지 늘려 잡는다 —
     *  playbackHeadPosition 은 믹서가 가져간 자리라 출력 지연만큼 앞선다(11-15 에뮬레이터 실측: 막대가 소리보다 약 0.4초 앞섰다). 타임스탬프가 아직 없으면 머리 자리 */
    private val ts = android.media.AudioTimestamp()
    private fun heardPos(t: android.media.AudioTrack, size: Int): Int {
        val frames = if (t.getTimestamp(ts) && ts.framePosition > 0) ts.framePosition + (System.nanoTime() - ts.nanoTime) * 16_000L / 1_000_000_000L
            else t.playbackHeadPosition.toLong()
        return (frames * 2L).coerceIn(0L, minOf(size.toLong(), t.playbackHeadPosition * 2L)).toInt() and 1.inv()
    }
    /** 지금 스피커에서 나오는 0.1초(들리는 자리 바로 앞 3200바이트, 16 kHz 모노 16비트) */
    private fun heardWindow(pcm: ByteArray, t: android.media.AudioTrack): ByteArray {
        val head = heardPos(t, pcm.size)
        return pcm.copyOfRange(maxOf(0, head - 3200), head)
    }

    fun listenOnceAudio(pcm: ByteArray, onText: (String) -> Unit, onFail: (OnceFail) -> Unit, play: Boolean = false): Boolean {
        if (released) return false
        val s = stt
        if (!sttReady || s == null) { onFail(OnceFail.DOWN); return false }
        if (speaking || listening) { onFail(OnceFail.BUSY); return false }
        onceText = onText; onceFail = onFail
        listening = true; heard = null
        onListeningChanged()
        val seq = ++listenSeq
        Thread {
            val track = if (!play) null else runCatching {
                val min = android.media.AudioTrack.getMinBufferSize(16_000, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(16_000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min, 32_000)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build().also { it.play() }
            }.onFailure { e -> log.d("샘플 재생 못 함: $e") }.getOrNull()
            runCatching {
                if (track == null) {
                    var i = 0
                    while (i < pcm.size && !released && seq == listenSeq) {
                        val n = minOf(3200, pcm.size - i); val c = pcm.copyOfRange(i, i + n)
                        ListenLevel.pcm(c); s.sendAudio(c); i += n; Thread.sleep(100)
                    }
                } else {
                    // 소리를 틀 때(시연 마이크, 11-15 사용자 "녹음 샘플본이랑 녹음 애니메이션이랑 안맞는데"): 스피커에 미리 써 두고, 막대와 인식 엔진은
                    // 재생 머리(지금 들리는 자리)를 따라간다 — 보낸 조각 기준이면 재생 지연만큼 막대가 앞서고 인식이 소리보다 먼저 끝났다
                    var i = 0; var sent = 0
                    val end = System.currentTimeMillis() + pcm.size / 32L + 3_000   // 소리 길이 + 3초 안에 끝낸다(재생 머리가 멈춰도)
                    while (sent < pcm.size && !released && seq == listenSeq && System.currentTimeMillis() < end) {
                        if (i < pcm.size) { val n = minOf(3200, pcm.size - i); track.write(pcm, i, n); i += n }
                        val head = heardPos(track, pcm.size)
                        if (head > sent) { s.sendAudio(pcm.copyOfRange(sent, head)); sent = head }
                        ListenLevel.pcm(heardWindow(pcm, track)); Thread.sleep(50)
                    }
                    if (sent < pcm.size && seq == listenSeq) s.sendAudio(pcm.copyOfRange(sent, pcm.size))
                }
                if (seq == listenSeq) s.completeAudioSend()
            }.onFailure { e -> log.d("STT sendAudio 예외: $e"); main.post { if (!released && seq == listenSeq && onceText != null) finishOnce(OnceFail.ERROR) } }
            track?.let { t -> runCatching { ListenLevel.pcm(ByteArray(0)); t.stop(); t.release() } }
        }.start()
        main.postDelayed({ if (!released && listening && seq == listenSeq && onceText != null) finishOnce(OnceFail.SILENT) }, ONCE_MS)
        return true
    }

    /** 한 번 듣기를 끝낸다(실패면 까닭을 알린다) */
    private fun finishOnce(why: OnceFail?) {
        val f = onceFail
        onceText = null; onceFail = null
        listening = false; heard = null
        onListeningChanged()
        if (why != null) f?.invoke(why)
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) {   // 마이크 입력 크기(단위 문서 없음 — ListenLevel 이 이번 듣기 안에서 상대값으로)
            ListenLevel.sdk(rms)
            if (ListenLevel.sdkSamples % 20 == 1) android.util.Log.d(AppLog.TAG, "STT rms $rms → ${ListenLevel.level.value}")
        }
        override fun onError(errorType: SttError) {
            log.d("STT 오류: $errorType")
            main.post {
                if (onceText != null) finishOnce(when (errorType) {   // 말로 검색 중: 까닭을 한 줄로
                    SttError.MIC_ERROR, SttError.AUDIO_ERROR, SttError.PERMISSION_DENIED -> OnceFail.MIC
                    SttError.TIMEOUT -> OnceFail.SILENT
                    else -> OnceFail.ERROR
                }) else { listening = false; onListeningChanged() }
            }
        }
        override fun onUpdated(stt: String, completed: Boolean) {   // 이름은 SDK 인터페이스와 같게
            main.post {
                heard = stt
                onListeningChanged()
                if (completed) {
                    val once = onceText
                    if (once != null) { if (stt.isBlank()) finishOnce(OnceFail.NO_MATCH) else { finishOnce(null); once(stt.trim()) } }
                    else { listening = false; onListeningChanged(); onUtterance(stt) }
                }
            }
        }
    }

    /** 답을 받았거나 질문을 거뒀을 때(말로 검색을 멈출 때도 — 그때는 실패 알림 없이 끝낸다) */
    fun stopListening() {
        onceText = null; onceFail = null
        listening = false; heard = null
        runCatching { stt?.stop() }
        onListeningChanged()
    }

    fun release() {
        released = true
        ttsRetry?.cancel(); sttRetry?.cancel()
        speaking = false
        abandonFocus()
        runCatching { tts?.release() }
        runCatching { stt?.release() }
    }

    private companion object {
        const val STUCK_MS = 15_000L       // TTS 끝 신호가 이만큼 없으면 막힌 것으로 본다
        const val FOCUS_WAIT_MS = 10_000L  // 오디오 포커스를 못 얻어도 이만큼은 기다렸다 말한다(넘으면 포기하고 부른 쪽에 알림)
        const val FOCUS_RETRY_MS = 1000L
        const val LISTEN_MS = 20_000L      // 답을 기다리는 시간
        const val ONCE_MS = 10_000L        // 말로 검색: 이만큼 끝난 말이 없으면 '말이 없음'
    }
}
