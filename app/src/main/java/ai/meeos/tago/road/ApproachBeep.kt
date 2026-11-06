package ai.meeos.tago.road

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 접근 경고음과 지난 알림음. 앱 안에서 합성한다(효과음 파일 없음).
 *
 * 레퍼런스 = Pleos 순정 알림음(시스템 테마 ConnectTheme 의 snd_noti_inf/urt/war/crt, 운전 전환 snd_ad_tor_l2/l3 — 에뮬레이터에서 꺼내 분석,
 * 음원은 쓰지 않았다). 거기서 읽은 문법을 따른다(2026-11-05 분석):
 *  - 음역은 낮은~중간(기음 180–560 Hz). 높은 단음 '삐'가 아니라 화음
 *  - 음색은 기음 + 옥타브(−7 dB) + 약한 3·4배음, 위 배음일수록 빨리 사라짐. 4 kHz 위는 거의 없다
 *  - 65–95 ms 간격으로 두세 번 치는 짧은 음형, 0.25–0.3 s 에 −20 dB
 *  - 위급할수록 같은 음형을 온음씩 올리고 더 세게·더 자주(운전 전환 L2 → L3)
 *  - 크기 RMS −22 ~ −17 dBFS
 *  - 세부(v4): 스테레오(좌우 상관 0.06–0.88), 부드러운 어택 10–25 ms, 은은한 떨림 2–5 dB, 감쇠 −64 ~ −134 dB/s, 길이 0.33–0.68 s
 *    → 화음 아래 음은 조금 왼쪽·위 음은 조금 오른쪽, 좌우 ±5 센트(맥놀이), 좌우 잔향 길이 차 1.3 ms, 어택 13 ms(사인 곡선)
 * 단, 순정 운전 전환 경고의 음(G#4·B4, A#4·C#5)은 피했다 — 차가 "핸들을 잡으라"는 소리로 오해하지 않게.
 *
 *   1단계  단3도 D4·F4 + 낮은 D3, 두 번(80 ms)
 *   2단계  같은 꼴을 온음 위 E4·G4 / E3, 더 세게(75 ms)
 *   3단계  다시 온음 위 F#4·A4 / F#3, 밝은 음색, 세 번(70 ms) — 줄일 때까지 1초 고리로 끊김 없이 되풀이([loop3])
 *   지남   열린 5도로 올라감 E4 → B4(아래 E3), 부드럽고 길게 — 경고와 반대 방향·화음이라 '끝났다'로 들린다
 *
 * 수치는 시제품(파이썬)과 같다. 재생은 음성 안내와 같은 내비 안내 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 섞어 낸다.
 * 오디오 포커스는 잡지 않는다(잡으면 말하던 안내 음성이 포커스를 잃고 끊긴다, VoiceAssistant).
 */
class ApproachBeep {
    private val player = Executors.newSingleThreadExecutor()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val cache = HashMap<Int, ShortArray>()

    // 첫 경고에서 합성 시간(수백 ms)만큼 늦지 않게, 만들 때 네 소리를 미리 합성해 둔다(같은 재생 스레드라 순서가 지켜진다)
    init { player.execute { runCatching { listOf(1, 2, 3, PASSED, LOOP3).forEach { st -> synchronized(cache) { cache.getOrPut(st) { render(st) } } } } } }

    // 3단계 고리 재생기: 1초짜리 고리를 정확한 박자로 되풀이한다(1초 틱마다 새로 틀면 틱 흔들림만큼 박자가 들쭉날쭉하고 사이가 뚝 끊겨 들렸다)
    private var loopTrack: AudioTrack? = null

    /** 3단계 반복 켜기·끄기(여러 번 불러도 된다). 끌 때는 소리를 0 으로 내린 뒤 40 ms 뒤 멈춘다(딸깍 방지) */
    fun loop3(on: Boolean) {
        player.execute {
            runCatching {
                if (on && loopTrack == null) {
                    val pcm = synchronized(cache) { cache.getOrPut(LOOP3) { render(LOOP3) } }
                    val frames = pcm.size / 2
                    val t = AudioTrack(attrs, stereo(), pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
                    t.write(pcm, 0, pcm.size)
                    t.setLoopPoints(0, frames, -1)
                    t.play()
                    loopTrack = t
                } else if (!on) loopTrack?.let { t ->
                    loopTrack = null
                    t.setVolume(0f); Thread.sleep(40); t.stop(); t.release()
                }
            }
        }
    }

    private fun stereo() = AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()

    /** stage 1·2·3 또는 PASSED — 비동기로 한 번 낸다. 앞 소리가 아직 나고 있으면 그 뒤에 이어진다 */
    fun play(stage: Int) {
        player.execute {
            runCatching {
                val pcm = synchronized(cache) { cache.getOrPut(stage) { render(stage) } }
                val track = AudioTrack(attrs, stereo(), pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size / 2 * 1000L / SR + 30)   // 좌우 번갈아 든 PCM: 길이 = 크기 ÷ 2
                track.stop(); track.release()
            }
        }
    }

    /** 지난 알림음 */
    fun passed() = play(PASSED)

    fun release() { loop3(false); player.shutdown() }

    /** 시험용(시연 훅 beep_wav): 네 소리를 dir 에 WAV 로 쓴다 — PC 에서 들어 보고 시제품과 대조하려고 */
    fun writeWavs(dir: File): List<File> = listOf(1, 2, 3, PASSED, LOOP3).map { st ->
        val pcm = render(st)
        val f = File(dir, when (st) { PASSED -> "beep_pass.wav"; LOOP3 -> "beep_3_loop.wav"; else -> "beep_$st.wav" })
        FileOutputStream(f).use { out ->
            val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(2).putInt(SR).putInt(SR * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
            pcm.forEach { b.putShort(it) }
            out.write(b.array())
        }
        f
    }

    // ------------------------------------------------------------ 합성

    /** 치기 하나: 시각(s), 음(Hz), 세기, 음색(배음 번호 → dB), 감쇠 시간(s), 치는 순간 '톡'(5배음, 8 ms) 세기 */
    private class Strike(val at: Double, val hz: Double, val amp: Double, val tone: List<Pair<Int, Double>>, val tau: Double, val tick: Double, val pan: Double = 0.0)

    private fun dyad(at: Double, lo: Double, hi: Double, bass: Double, amp: Double, tone: List<Pair<Int, Double>>, tau: Double, tick: Double) = listOf(
        Strike(at, lo, amp, tone, tau, tick, -0.35), Strike(at + 0.004, hi, amp * 0.9, tone, tau, tick, +0.35), Strike(at, bass, amp * 0.55, SOFT, tau * 1.3, 0.0, 0.0))

    /** (치기 목록, 목표 RMS dBFS) */
    private fun score(stage: Int): Pair<List<Strike>, Double> = when (stage) {
        // 감쇠 90 ms 안팎: 치는 사이 꺼짐을 순정 수준(5–12 dB)으로 — 60 ms 일 때 10–15 dB 로 끊겨 들렸다
        1 -> (dyad(0.0, D4, F4, D3, 0.9, SOFT, 0.09, 0.03) + dyad(0.08, D4, F4, D3, 0.75, SOFT, 0.11, 0.02)) to -19.0
        2 -> (dyad(0.0, E4, G4, E3, 1.0, SOFT, 0.09, 0.04) + dyad(0.075, E4, G4, E3, 0.85, SOFT, 0.10, 0.03)) to -16.5
        3 -> (dyad(0.0, FS4, A4, FS3, 1.0, BRIGHT, 0.09, 0.05) + dyad(0.07, FS4, A4, FS3, 0.9, BRIGHT, 0.09, 0.04) +
            dyad(0.14, FS4, A4, FS3, 0.85, BRIGHT, 0.15, 0.04)) to -14.0   // 마지막 치기는 길게 — 고리에서 다음 반복까지 꼬리가 이어진다
        // 지남: 첫 음은 왼쪽에서, 둘째 음은 오른쪽으로 — 지나가는 느낌
        else -> listOf(Strike(0.0, E4, 0.8, SOFT, 0.10, 0.02, -0.4), Strike(0.0, E3, 0.45, SOFT, 0.13, 0.0, 0.0),
            Strike(0.09, B4, 0.85, SOFT, 0.14, 0.02, +0.4), Strike(0.09, E4, 0.35, SOFT, 0.14, 0.0, +0.1)) to -19.0
    }

    /** 3단계 1초 고리: 같은 음형을 0·1·2초에 쳐서 렌더하고 가운데 1초를 잘라 쓴다 — 앞 반복의 꼬리가 다음 반복으로 이어져 이음매가 없다.
     *  크기는 한 번 친 3단계와 같은 이득 */
    private fun renderLoop(): ShortArray {
        val (one, target) = score(3)
        val strikes = (0..2).flatMap { k -> one.map { Strike(it.at + k * LOOP_S, it.hz, it.amp, it.tone, it.tau, it.tick, it.pan) } }
        val size = (3 * LOOP_S * SR).toInt() + (0.9 * SR).toInt()
        val (l, r) = synth(strikes, size)
        val oneSize = ((one.maxOf { it.at + min(0.8, it.tau * 7) } + 0.10) * SR).toInt()
        val (l1, r1) = synth(one, oneSize)
        val gain = gainOf(l1, r1, target)
        val a = (LOOP_S * SR).toInt(); val b = (2 * LOOP_S * SR).toInt()
        val out = ShortArray((b - a) * 2)
        for (i in a until b) {
            out[2 * (i - a)] = (tanh(l[i] * gain / 0.9) * 0.9 * 32767).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * (i - a) + 1] = (tanh(r[i] * gain / 0.9) * 0.9 * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 치기들을 좌우로 합성하고 잔향을 섞는다 */
    private fun synth(strikes: List<Strike>, size: Int): Pair<DoubleArray, DoubleArray> {
        val l = DoubleArray(size); val r = DoubleArray(size)
        for (s in strikes) {
            val start = (s.at * SR).toInt(); val n = (min(0.8, s.tau * 7) * SR).toInt()
            val gl = sqrt(0.5 * (1 - s.pan)); val gr = sqrt(0.5 * (1 + s.pan))              // 같은 크기 팬
            for ((buf, cents, g) in listOf(Triple(l, -DETUNE, gl), Triple(r, DETUNE, gr))) {
                val f = s.hz * 2.0.pow(cents / 1200)
                for (i in 0 until n) {
                    val t = i.toDouble() / SR
                    var y = 0.0
                    for ((k, db) in s.tone) y += 10.0.pow(db / 20) * exp(-t / (s.tau / k.toDouble().pow(0.7))) * sin(2 * PI * f * k * t + 0.3 * k)
                    if (s.tick > 0) y += s.tick * exp(-t / 0.008) * sin(2 * PI * f * 5 * t)
                    val att = if (t < ATTACK) sin(0.5 * PI * t / ATTACK).pow(2) else 1.0     // 13 ms 사인 곡선 오르막
                    val rel = min(1.0, (n - i) / (0.03 * SR))                                   // 끝 30 ms 를 0 으로(딸깍 방지)
                    val k = start + i
                    if (k < size) buf[k] += y * att * rel * s.amp * g
                }
            }
        }
        // 잔향: 가운데 신호를 좌우 길이가 조금 다른 방(오른쪽 +1.3 ms)에 넣어 섞는다 — 좌우가 달라져 넓게 들린다
        val mid = DoubleArray(size) { (l[it] + r[it]) / 2 }
        val wl = room(mid, 0.0); val wr = room(mid, 1.3)
        for (i in 0 until size) { l[i] += WET * wl[i]; r[i] += WET * wr[i] }
        return l to r
    }

    /** 크기: 가운데 신호의 소리 나는 구간(최댓값의 1 % 위) RMS 를 목표 dBFS 로 맞추는 이득 */
    private fun gainOf(l: DoubleArray, r: DoubleArray, target: Double): Double {
        var mx = 0.0
        for (i in l.indices) mx = maxOf(mx, abs((l[i] + r[i]) / 2))
        var sum = 0.0; var cnt = 0
        for (i in l.indices) { val m = (l[i] + r[i]) / 2; if (abs(m) > mx * 0.01) { sum += m * m; cnt++ } }
        return 10.0.pow(target / 20) / sqrt(sum / maxOf(1, cnt))
    }

    /** 좌우 번갈아 든 16비트 PCM(L, R, L, R …) */
    private fun render(stage: Int): ShortArray {
        if (stage == LOOP3) return renderLoop()
        val (strikes, target) = score(stage)
        val total = strikes.maxOf { it.at + min(0.8, it.tau * 7) } + 0.10
        val size = (total * SR).toInt()
        val (l, r) = synth(strikes, size)
        val gain = gainOf(l, r, target)
        // 봉우리만 tanh 로 부드럽게
        for (i in 0 until size) { l[i] = tanh(l[i] * gain / 0.9) * 0.9; r[i] = tanh(r[i] * gain / 0.9) * 0.9 }
        // 끝의 거의 무음(−60 dBFS 아래)을 잘라 3단계가 1초 반복 안에 들어가게, 마지막 20 ms 는 0 으로
        var last = size - 1
        while (last > 0 && maxOf(abs(l[last]), abs(r[last])) <= 0.001) last--
        val len = last + 1; val fade = (0.02 * SR).toInt()
        val out = ShortArray(len * 2)
        for (i in 0 until len) {
            val f = if (i >= len - fade) 1.0 - (i - (len - fade)).toDouble() / (fade - 1) else 1.0
            out[2 * i] = (l[i] * f * 32767).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (r[i] * f * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 작은 실내 잔향: 되먹임 안에서 고역을 깎는 빗살 필터 4개 + 올패스 2개(Schroeder). extraMs 만큼 지연을 늘려 좌우를 다르게 */
    private fun room(x: DoubleArray, extraMs: Double): DoubleArray {
        val out = DoubleArray(x.size)
        for ((ms, g) in listOf(23.7 to 0.62, 29.3 to 0.60, 33.1 to 0.58, 37.9 to 0.56)) {
            val d = (SR * (ms + extraMs) / 1000).toInt(); val c = DoubleArray(x.size); var lp = 0.0
            for (i in x.indices) { val fb = if (i >= d) c[i - d] else 0.0; lp = 0.55 * fb + 0.45 * lp; c[i] = x[i] + g * lp }
            for (i in x.indices) out[i] += c[i]
        }
        var cur = out
        for ((ms, g) in listOf(5.0 to 0.6, 1.7 to 0.6)) {
            val d = (SR * (ms + extraMs * 0.3) / 1000).toInt(); val y = DoubleArray(cur.size)
            for (i in cur.indices) { val xd = if (i >= d) cur[i - d] else 0.0; val yd = if (i >= d) y[i - d] else 0.0; y[i] = -g * cur[i] + xd + g * yd }
            cur = y
        }
        return DoubleArray(x.size) { cur[it] / 4 }
    }

    companion object {
        const val PASSED = 9
        const val LOOP3 = 33                  // 3단계 1초 고리
        private const val LOOP_S = 1.0
        private const val SR = 44100
        private const val ATTACK = 0.013      // 어택 13 ms(순정 10–25 ms)
        private const val DETUNE = 5.0        // 좌우 음 차이(센트): 왼쪽 −5, 오른쪽 +5
        private const val WET = 0.16          // 잔향 섞는 양
        private val SOFT = listOf(1 to 0.0, 2 to -7.0, 3 to -16.0, 4 to -22.0, 5 to -30.0)
        private val BRIGHT = listOf(1 to 0.0, 2 to -5.0, 3 to -11.0, 4 to -16.0, 5 to -21.0, 6 to -27.0)
        private fun note(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
        private val D3 = note(50); private val E3 = note(52); private val FS3 = note(54)
        private val D4 = note(62); private val E4 = note(64); private val F4 = note(65); private val FS4 = note(66); private val G4 = note(67); private val A4 = note(69); private val B4 = note(71)
    }
}
