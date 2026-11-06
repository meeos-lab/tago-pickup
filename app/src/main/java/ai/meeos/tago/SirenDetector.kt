package ai.meeos.tago

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * (앱에서는 더 쓰지 않음 — 11-06 부터 SirenYamnet. tools/siren_eval 이 이 클래스를 떼어 가 11-05 평가를 재현한다)
 * 차 안 마이크로 긴급차량 사이렌(웨일·옐프: 450~2000Hz에서 한 음이 몇 초씩 끊기지 않고 미끄러지듯 오르내림)을 찾는 FFT 규칙.
 * 합성 시험 음원으로 맞췄고, 공개 실녹음 평가(11-05)에서 사이렌 없는 실제 도로 소음에도 48~95 % 시간 켜졌다. 32ms 창·16ms 간격 FFT 512.
 */
class SirenDetector {
    companion object {
        const val SR = 16000
        const val N = 512
        const val HOP = 256
        const val BAND_LO = 450.0; const val BAND_HI = 2000.0      // 사이렌 기본음 대역
        const val REF_LO = 300.0; const val REF_HI = 4000.0        // 비교 대역
        const val TONAL = 8.0                                       // 봉우리(±2칸 합) / 비교 대역 중앙값 ≥ 8×5 이면 '음이 하나'
        const val MIN_DB = -55.0                                    // 이보다 조용한 창은 보지 않는다(dBFS)
        const val WIN = (3.0 * SR / HOP).toInt()                    // 3초 창
        const val GLIDE_LO = 2.0; const val GLIDE_HI = 260.0       // 16ms 사이 주파수 변화가 이 안이면 '미끄러짐'
        const val NEED_TONAL = 0.55; const val NEED_GLIDE = 0.6; const val MAX_JUMP = 0.15; const val MIN_SPAN = 250.0
        const val RUN_MIN = (0.4 * SR / HOP).toInt()                // 0.4초 이상 이어진 음만 센다(말소리는 음절마다 끊긴다)
        const val CAND_DB = 10.0                                    // 가장 센 봉우리 10dB 안에서 앞 창과 이어지는 봉우리를 고른다(음악이 섞여도 따라간다)
        const val ON_FRAMES = (0.5 * SR / HOP).toInt()              // 0.5초 연속 만족하면 켬
        const val OFF_FRAMES = (4.0 * SR / HOP).toInt()             // 4초 연속 불만족이면 끔
    }

    private val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
    private val kLo = (0 until N / 2 + 1).first { it * SR.toDouble() / N >= BAND_LO }
    private val kHi = (0 until N / 2 + 1).last { it * SR.toDouble() / N <= BAND_HI }
    private val rLo = (0 until N / 2 + 1).first { it * SR.toDouble() / N >= REF_LO }
    private val rHi = (0 until N / 2 + 1).last { it * SR.toDouble() / N <= REF_HI }
    private val re = DoubleArray(N); private val im = DoubleArray(N); private val p = DoubleArray(N / 2 + 1)
    // FFT 회전 인자(트위들)는 미리 한 번만 — 창마다 cos·sin 2,304번을 다시 계산하지 않는다
    private val twR = DoubleArray(N / 2) { cos(-2 * PI * it / N) }
    private val twI = DoubleArray(N / 2) { sin(-2 * PI * it / N) }
    private val medBuf = DoubleArray(N / 2 + 1)                     // 비교 대역 중앙값용(창마다 새 리스트를 만들지 않게)
    private val tonalH = BooleanArray(WIN); private val freqH = DoubleArray(WIN)
    private var count = 0; private var head = 0                     // 원형 기록(최근 WIN개)
    private var good = 0; private var bad = 0
    var on = false; private set

    /** 창 하나(N개, -1..1) → 지금 사이렌이 들리는지 */
    fun frame(x: DoubleArray): Boolean {
        var ss = 0.0
        for (v in x) ss += v * v
        val rms = sqrt(ss / N) + 1e-12
        var tonal = false; var f = 0.0
        if (20 * log10(rms) > MIN_DB) {
            for (i in 0 until N) { re[i] = x[i] * win[i]; im[i] = 0.0 }
            fft(re, im)
            for (i in 0..N / 2) p[i] = re[i] * re[i] + im[i] * im[i]
            var k = kLo
            for (i in kLo..kHi) if (p[i] > p[k]) k = i
            val top = p[k]
            val prev = if (count > 0 && tonalH[idx(count - 1)]) freqH[idx(count - 1)] else null
            if (prev != null) {
                var near = k
                for (i in kLo..kHi) if (p[i] >= top / Math.pow(10.0, CAND_DB / 10) && p[i] >= p[i - 1] && p[i] >= p[i + 1]
                    && abs(i * SR.toDouble() / N - prev) < abs(near * SR.toDouble() / N - prev)) near = i
                if (abs(near * SR.toDouble() / N - prev) <= GLIDE_HI) k = near
            }
            var peak = 0.0
            for (i in maxOf(0, k - 2)..minOf(N / 2, k + 2)) peak += p[i]
            val m = rHi - rLo + 1
            System.arraycopy(p, rLo, medBuf, 0, m); java.util.Arrays.sort(medBuf, 0, m)
            val med = (if (m % 2 == 1) medBuf[m / 2] else (medBuf[m / 2 - 1] + medBuf[m / 2]) / 2) + 1e-20
            if (peak / med >= TONAL * 5) {
                val a = ln(p[k - 1] + 1e-20); val b = ln(p[k] + 1e-20); val c = ln(p[k + 1] + 1e-20)
                val d = a - 2 * b + c
                val off = if (d != 0.0) 0.5 * (a - c) / d else 0.0
                f = (k + off.coerceIn(-0.5, 0.5)) * SR / N; tonal = true
            }
        }
        val at = idx(count); tonalH[at] = tonal; freqH[at] = f
        count++
        if (check()) { good++; bad = 0; if (good >= ON_FRAMES) on = true }
        else { bad++; good = 0; if (bad >= OFF_FRAMES) on = false }
        return on
    }

    /** count 번째 기록의 원형 위치 */
    private fun idx(i: Int) = i % WIN

    private fun check(): Boolean {
        if (count < WIN) return false
        val t = BooleanArray(WIN) { tonalH[idx(count - WIN + it)] }
        val fr = DoubleArray(WIN) { freqH[idx(count - WIN + it)] }
        val keep = BooleanArray(WIN)                                // 길게 이어진 음(RUN_MIN 이상)에 든 창만
        var i = 0
        while (i < WIN) {
            if (!t[i]) { i++; continue }
            var j = i
            while (j < WIN && t[j]) j++
            if (j - i >= RUN_MIN) for (q in i until j) keep[q] = true
            i = j
        }
        if (keep.count { it } < NEED_TONAL * WIN) return false
        val pairs = (1 until WIN).filter { keep[it] && keep[it - 1] }.map { abs(fr[it] - fr[it - 1]) }
        if (pairs.size < 10) return false
        val glide = pairs.count { it in GLIDE_LO..GLIDE_HI }.toDouble() / pairs.size
        val jump = pairs.count { it > GLIDE_HI }.toDouble() / pairs.size
        val fs = (0 until WIN).filter { keep[it] }.map { fr[it] }.sorted()
        val span = fs[(0.9 * (fs.size - 1)).toInt()] - fs[(0.1 * (fs.size - 1)).toInt()]
        return glide >= NEED_GLIDE && jump <= MAX_JUMP && span >= MIN_SPAN
    }

    /** 제자리 기수-2 FFT(N=512) */
    private fun fft(xr: DoubleArray, xi: DoubleArray) {
        val n = xr.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = xr[i]; xr[i] = xr[j]; xr[j] = t; t = xi[i]; xi[i] = xi[j]; xi[j] = t }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (s in 0 until n step len) for (k in 0 until len / 2) {
                val wr = twR[k * step]; val wi = twI[k * step]
                val ur = xr[s + k]; val ui = xi[s + k]
                val vr = xr[s + k + len / 2] * wr - xi[s + k + len / 2] * wi
                val vi = xr[s + k + len / 2] * wi + xi[s + k + len / 2] * wr
                xr[s + k] = ur + vr; xi[s + k] = ui + vi
                xr[s + k + len / 2] = ur - vr; xi[s + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }
}

/**
 * 마이크(또는 시험용 wav)를 읽어 SirenYamnet 에 넣는 스레드. 켜짐/꺼짐이 바뀔 때 onChange(on)을 부른다.
 * 음성 인식(STT)이 마이크를 쓰는 동안은 stop()으로 놓아 준다(동시에 녹음하면 한쪽이 무음을 받는다).
 * model: 스레드 안에서 부른다(4 MB 매핑을 화면 스레드에서 하지 않게). null 이면 감지하지 않는다.
 */
class SirenMic(private val model: () -> java.nio.ByteBuffer?, private val onChange: (Boolean) -> Unit, private val log: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var gen = 0          // start 마다 하나씩: 멈춘 뒤 바로 다시 켜도 지난 스레드가 함께 돌지 않게
    private var thread: Thread? = null
    val active get() = running

    /** wav: 16kHz 모노 16bit 파일(시험용, 실시간 속도로 흘려 넣는다). null이면 마이크 */
    @SuppressLint("MissingPermission")   // 호출 쪽에서 RECORD_AUDIO 권한을 확인한다
    fun start(wav: File? = null) {
        if (running) return
        running = true
        val my = ++gen
        thread = Thread({
            var det: SirenYamnet? = null
            var last = false
            val hop = ShortArray(SirenDetector.HOP)
            var rec: AudioRecord? = null
            var file: RandomAccessFile? = null
            try {
                det = model()?.let { SirenYamnet(it) }
                if (det == null) { log("사이렌 감지: 모델(${SirenYamnet.ASSET}) 없음 — 끔"); return@Thread }
                if (wav != null) { file = RandomAccessFile(wav, "r"); file.seek(44) }
                else {
                    val min = AudioRecord.getMinBufferSize(SirenDetector.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    rec = AudioRecord(MediaRecorder.AudioSource.MIC, SirenDetector.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8 * SirenDetector.HOP * 2))
                    rec.startRecording()
                }
                log(if (wav != null) "사이렌 감지: 시험 파일 ${wav.name}" else "사이렌 감지: 마이크 켬")
                val bytes = ByteArray(SirenDetector.HOP * 2)
                var errors = 0
                while (running && my == gen) {
                    var n = 0
                    if (file != null) {
                        val r = file.read(bytes); if (r <= 0) break
                        for (i in 0 until r / 2) hop[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                        n = r / 2
                        Thread.sleep(1000L * SirenDetector.HOP / SirenDetector.SR)   // 실시간 속도
                    } else {
                        while (n < hop.size && running) { val r = rec!!.read(hop, n, hop.size - n); if (r <= 0) break; n += r }
                    }
                    if (n < hop.size) {
                        // 녹음 오류(다른 앱이 마이크를 쥠 등): 쉬지 않고 돌지 않게 0.2초 쉬고, 5초 넘게 이어지면 놓는다
                        if (++errors > 25) { log("사이렌 감지: 마이크 오류가 이어져 멈춤"); break }
                        Thread.sleep(200); continue
                    }
                    errors = 0
                    val on = det.push(hop, n)
                    if (on != last) { last = on; onChange(on); log("사이렌 감지: ${if (on) "켬" else "끔"}(점수 %.2f)".format(det.score)) }
                }
            } catch (e: Exception) {
                log("사이렌 감지 오류: ${e.message}")
            } finally {
                runCatching { rec?.stop() }; runCatching { rec?.release() }; runCatching { file?.close() }
                det?.let { if (it.runs > 0) log("사이렌 감지: 추론 ${it.runs}회, 평균 %.1f ms".format(it.nanos / 1e6 / it.runs)); it.close() }
                if (last) onChange(false)
                if (my == gen) running = false
            }
        }, "siren").apply { isDaemon = true; start() }
    }

    /** 음성 인식이 바로 마이크를 쓰도록 녹음 스레드가 마이크를 놓을 때까지 잠깐(최대 0.1초, 한 번 읽기 16ms) 기다린다. 예전 0.5초보다 짧게 */
    fun stop() {
        running = false
        gen++
        thread?.join(100)
        thread = null
    }
}
