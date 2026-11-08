package ai.meeos.tago.voice

import ai.meeos.tago.app.AppLog
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
        private set
    /** 듣는 동안 들린 말(부분 인식 포함). 화면에 보여 준다 */
    var heard: String? = null
        private set

    // 말하는 중에 다음 문장이 오면 끊지 않고 줄 세운다(새 speak가 앞 문장을 끊어 '끊겨 들리던' 문제). 오래된 것부터 버려 최대 3개
    private val queue = ArrayDeque<String>()
    private var speakStartedAt = 0L

    // Pleos 오디오 포커스 정책: 안내 음성은 내비 안내(NAVIGATION) 용도로, 음악 위에서는 음악을 줄이고(duck) 말한다.
    // Gleo 가 말하는 중에는 포커스가 거절된다(R) — 1.5초 뒤 다시 청한다(최대 6번), 그동안 문장은 줄에 남는다
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
                if (speaking) { current?.let { if (queue.firstOrNull() != it) queue.addFirst(it) }; runCatching { tts?.stop() } }
            }
        }, main)
        .build()
    private var focusTries = 0
    private var current: String? = null
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

    /** 서비스가 붙는 것은 비동기라, 지역은 그때(onSuccess) 다시 읽는다 — 저장된 지역이 대치동이 아닐 때 한국어 화자로 굳지 않게 */
    fun init(regionProvider: () -> Region) {
        runCatching {
            tts = TextToSpeech(context, TtsMode.ON_DEVICE).also { t ->
                t.initialize(object : TtsInit {
                    override fun onSuccess() {
                        val region = regionProvider()
                        t.setLocale(region.locale)
                        runCatching { t.setSpeakerType(speaker(region)) }
                        runCatching { t.setAudioAttributes(voiceAttrs) }   // 기본값(USAGE_MEDIA)이면 미디어 음량·포커스를 따라 안내가 묻힌다
                        t.addEventListener(ttsListener)
                        ttsReady = true
                        main.post { if (!speaking && queue.isNotEmpty()) speakNext() }   // 준비 전에 온 안내를 버리지 않고 이제 말한다
                    }

                    override fun onFailure(errorType: TtsError) = log.d("TTS 실패: $errorType")
                })
            }
            stt = SpeechToText(context, SttMode.ON_DEVICE).also { s ->
                s.initialize(object : SttInit {
                    override fun onSuccess() {
                        s.setLocale(regionProvider().locale)
                        s.addListener(sttListener)
                        sttReady = true
                    }

                    override fun onFailure(errorType: SttError) = log.d("STT 실패: $errorType")
                })
            }
        }.onFailure { log.d("음성 초기화 예외: $it") }
    }

    /** 언어별 화자(Pleos TTS: 한국어 seoyoon 등, 영어 lyra·finley). 영어에 한국어 화자를 두면 기본 화자로 바뀐다 */
    private fun speaker(region: Region) = if (region.lang == "ko") "seoyoon" else "lyra"

    fun setRegion(region: Region) {
        runCatching { tts?.setSpeakerType(speaker(region)) }
        runCatching { tts?.setLocale(region.locale) }
        runCatching { stt?.setLocale(region.locale) }
    }

    fun say(text: String) {
        log.demo("[음성] $text")   // 문장에는 아이 이름·만날 곳이 들어 있다 — 제품 빌드 로그에는 남기지 않는다
        main.post {
            if (!ttsReady) { queue.addLast(text); while (queue.size > QUEUE_MAX) queue.removeFirst(); return@post }
            // 15초 넘게 끝 신호가 없으면 막힌 것으로 보고 그냥 말한다
            if (speaking && System.currentTimeMillis() - speakStartedAt < STUCK_MS) {
                queue.addLast(text)
                while (queue.size > QUEUE_MAX) queue.removeFirst()
            } else speakNow(text)
        }
    }

    private fun speakNow(text: String) {
        if (!requestFocus()) {
            // 포커스 거절(Gleo 말하는 중 등): 문장을 줄 맨 앞에 두고 잠시 뒤 다시
            queue.addFirst(text)
            if (focusTries++ < FOCUS_RETRIES) main.postDelayed({ if (!released && !speaking && queue.isNotEmpty()) speakNext() }, FOCUS_RETRY_MS)
            else { log.d("오디오 포커스를 받지 못해 안내 ${queue.size}개를 건너뜀"); queue.clear(); focusTries = 0 }
            return
        }
        focusTries = 0; current = text
        speaking = true; speakStartedAt = System.currentTimeMillis()
        val startedAt = speakStartedAt
        runCatching { tts?.speak(text) }.onFailure { speaking = false }
        // 끝 신호(onDone/onStop)가 15초 넘게 없으면 막힌 것으로 보고 끝낸다 — 지도 타이머가 영영 멈추지 않게
        main.postDelayed({ if (!released && speaking && speakStartedAt == startedAt) { log.d("TTS 끝 신호 없음 — 강제 종료"); finished() } }, STUCK_MS)
    }

    /** 줄 선 문장이 있으면 이어서 말한다 — true면 이번 끝은 질문의 끝이 아니다 */
    private fun speakNext(): Boolean {
        val next = queue.removeFirstOrNull() ?: return false
        speakNow(next); return true
    }

    private fun finished() {
        speaking = false
        if (speakNext()) return
        abandonFocus()
        onSpeakingChanged(false)
    }

    private fun requestFocus(): Boolean = runCatching { audio?.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_FAILED }.getOrDefault(true)
    private fun abandonFocus() { runCatching { audio?.abandonAudioFocusRequest(focusReq) } }

    private val ttsListener = object : EventListener {
        override fun onStart() { main.post { speaking = true; onSpeakingChanged(true); android.util.Log.d(AppLog.TAG, "TTS 시작") } }
        override fun onReady() = Unit
        override fun onStop() { main.post { finished() } }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: TtsError) { log.d("TTS 오류: $errorType"); main.post { finished() } }
        override fun onDone() {
            main.post {
                android.util.Log.d(AppLog.TAG, "TTS 끝 (대기 ${queue.size})")
                speaking = false
                if (speakNext()) return@post                  // 줄 선 문장부터
                abandonFocus()
                onSpeakingChanged(false)
                if (shouldListen() && sttReady) listen()      // 질문을 마친 뒤에만 듣는다
            }
        }
    }

    private fun listen() {
        beforeListen()
        listening = true; heard = null
        onListeningChanged()
        runCatching { stt?.request() }.onFailure { listening = false; onListeningChanged() }
        val seq = ++listenSeq
        main.postDelayed({ if (!released && listening && seq == listenSeq) { log.d("20초 동안 답이 없어 듣기를 멈춤(버튼으로 답할 수 있음)"); stopListening() } }, LISTEN_MS)
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: SttError) { log.d("STT 오류: $errorType"); main.post { listening = false; onListeningChanged() } }
        override fun onUpdated(stt: String, completed: Boolean) {   // 이름은 SDK 인터페이스와 같게
            main.post {
                heard = stt
                onListeningChanged()
                if (completed) { listening = false; onListeningChanged(); onUtterance(stt) }
            }
        }
    }

    /** 답을 받았거나 질문을 거뒀을 때 */
    fun stopListening() {
        listening = false; heard = null
        runCatching { stt?.stop() }
        onListeningChanged()
    }

    fun release() {
        released = true
        speaking = false
        abandonFocus()
        runCatching { tts?.release() }
        runCatching { stt?.release() }
    }

    private companion object {
        const val QUEUE_MAX = 3            // 말하기 대기열 상한(넘으면 오래된 것부터 버린다)
        const val STUCK_MS = 15_000L       // TTS 끝 신호가 이만큼 없으면 막힌 것으로 본다
        const val FOCUS_RETRIES = 6        // 오디오 포커스를 못 얻었을 때 다시 시도하는 횟수
        const val FOCUS_RETRY_MS = 1500L
        const val LISTEN_MS = 20_000L      // 답을 기다리는 시간
    }
}
