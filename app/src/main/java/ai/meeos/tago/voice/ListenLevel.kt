package ai.meeos.tago.voice

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 듣는 동안의 말소리 크기(0~1)와 듣기 시작 시각 — 말로 검색의 소리 막대·"녹음 중"·카운터(11-15 사용자 "마이크 켰을때 사운드 바",
 * "말하면 막 움직이는거랑 녹음중 뜨고 카운터 뜨는거")가 읽는다. 막대는 실제 입력 크기로만 움직인다(지어낸 움직임 금지):
 *  - 마이크: Pleos STT 가 주는 onUpdatedRms 값. 단위가 문서에 없지만 Android SpeechRecognizer 의 rmsdB 같은 dB 꼴로 보인다
 *    (에뮬레이터에서 말하지 않을 때 4.52~4.85). 이번 듣기에서 나온 가장 작은 값(바닥) 위로 [SDK_NOISE] 까지는 잡음으로 0,
 *    그 위 [SDK_RANGE] 를 0~1 로. 11-15 사용자 "말안하고 있는데 이렇게 되면 어쩌냐" — 전에는 최소~최대 폭을 0~1 로 늘려
 *    0.33 흔들림이 0.95 가 되었다.
 *  - 시연·시험 녹음(stt_wav, sendAudio): 보내는 PCM 조각의 RMS 를 dBFS 로 재서 [PCM_FLOOR_DB, PCM_TOP_DB] 를 0~1 로.
 * 녹음을 넣는 동안에는 녹음 값만 쓴다(같은 듣기에서 두 값이 섞이지 않게).
 */
object ListenLevel {
    const val PCM_FLOOR_DB = -55.0
    const val PCM_TOP_DB = -12.0
    /** STT 값이 바닥보다 이만큼 커질 때까지는 잡음(말하지 않을 때 흔들림 0.33 실측의 세 배) */
    const val SDK_NOISE = 1.0
    /** 잡음 위로 이만큼 커지면 막대 가득(rmsdB 꼴 단위의 보통 말소리 폭) */
    const val SDK_RANGE = 5.0

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _since = MutableStateFlow(0L)
    /** 듣기 시작(SystemClock.elapsedRealtime, ms). 0 = 듣지 않음 */
    val since: StateFlow<Long> = _since

    @Volatile private var fromPcm = false
    private var lo = Double.NaN
    @Volatile var sdkSamples = 0; private set

    @Synchronized fun start(now: Long = SystemClock.elapsedRealtime()) {
        fromPcm = false; lo = Double.NaN; sdkSamples = 0
        _level.value = 0f; _since.value = now
    }

    @Synchronized fun stop() { _level.value = 0f; _since.value = 0L; fromPcm = false }

    /** Pleos STT onUpdatedRms(마이크). 녹음을 넣는 중이면 무시 */
    @Synchronized fun sdk(rms: Double) {
        if (_since.value == 0L || fromPcm || !rms.isFinite()) return
        sdkSamples++
        lo = if (lo.isNaN()) rms else minOf(lo, rms)
        _level.value = ((rms - lo - SDK_NOISE) / SDK_RANGE).toFloat().coerceIn(0f, 1f)
    }

    /** sendAudio 로 넣는 16비트 리틀엔디언 모노 PCM 한 조각 */
    @Synchronized fun pcm(chunk: ByteArray) {
        if (_since.value == 0L) return
        fromPcm = true
        _level.value = levelOfPcm(chunk)
    }

    /** PCM 조각의 크기(0~1): RMS → dBFS → [PCM_FLOOR_DB, PCM_TOP_DB] 를 0~1 로. 빈 조각은 0 */
    fun levelOfPcm(chunk: ByteArray): Float {
        val n = chunk.size / 2
        if (n == 0) return 0f
        var ss = 0.0
        for (i in 0 until n) {
            val v = ((chunk[2 * i + 1].toInt() shl 8) or (chunk[2 * i].toInt() and 0xFF)).toShort().toDouble()
            ss += v * v
        }
        val db = 20 * log10(sqrt(ss / n) / 32768.0 + 1e-12)
        return ((db - PCM_FLOOR_DB) / (PCM_TOP_DB - PCM_FLOOR_DB)).toFloat().coerceIn(0f, 1f)
    }
}
