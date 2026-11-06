package ai.meeos.tago

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 차 안 마이크 소리에서 긴급차량 사이렌을 YAMNet(구글 AudioSet 521종 소리 분류, TFLite 4 MB, 학습 없이 그대로)으로 찾는다.
 * 0.975초 창(15,600 표본)을 0.48초마다 넣고, 사이렌 관련 6종 점수의 최댓값이 문턱 이상인 창이 2번 이어지면 켜고 8번(≈4초) 모자라면 끈다.
 * 11-05·06 공개 실녹음 평가(tools/siren_eval, docs/dev/60 1-2 절)에서 문턱 0.7 은 도로 소음과 같은 크기 사이렌 36 % 검출·오경보 1회/시간 안팎,
 * 0.5 는 83 %·8회/시간 — 한 대가 잘못 울려도 허브가 여러 대의 onset 이동·위치 신호를 더 요구하므로 오경보 쪽을 아낀 0.7 로 둔다.
 * 규칙 감지기(SirenDetector)는 실제 도로 소음에서 48~95 % 시간 켜져 앱에서는 더 쓰지 않는다(평가 재현용으로 남김).
 * 계산은 PC 의 tools/siren_eval/yamnet_scores.py·score.py 와 같다(같은 창·간격·켬/끔 규칙).
 */
class SirenYamnet(model: ByteBuffer, threads: Int = 2) : AutoCloseable {
    companion object {
        const val SR = 16000
        const val WIN = 15600                       // 0.975초
        const val HOP = 7680                        // 0.48초
        const val TH = 0.7f
        const val ON_N = 2; const val OFF_N = 8
        const val ASSET = "siren/yamnet.tflite"
        /** yamnet_class_map.csv 의 Emergency vehicle·Police car (siren)·Ambulance (siren)·Fire engine, fire truck (siren)·Siren·Civil defense siren */
        val SIREN_CLASSES = intArrayOf(316, 317, 318, 319, 390, 391)

        /** assets 의 모델(압축 안 함)을 메모리 매핑. 없거나 못 읽으면 null */
        fun loadModel(ctx: Context): ByteBuffer? = runCatching {
            ctx.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
            }
        }.recoverCatching {
            val b = ctx.assets.open(ASSET).use { it.readBytes() }
            ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.rewind() }
        }.getOrNull()
    }

    private val itp = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    private val ring = FloatArray(WIN)              // 최근 WIN 표본(원형)
    private var head = 0                            // 다음에 쓸 자리 = 가장 오래된 표본 자리
    private var total = 0L
    private val input = FloatArray(WIN)
    private val output = Array(1) { FloatArray(521) }
    private var good = 0; private var bad = 0
    var on = false; private set
    /** 마지막 창의 사이렌 점수(0~1), 아직 없으면 -1 */
    var score = -1f; private set
    /** 추론 횟수·누적 시간(ns) — 기록용 */
    var runs = 0; private set
    var nanos = 0L; private set

    /** 표본 n개(-32768..32767)를 넣는다 → 지금 켜져 있는지 */
    fun push(x: ShortArray, n: Int = x.size): Boolean {
        for (i in 0 until n) {
            ring[head] = x[i] / 32768f
            head = if (head + 1 == WIN) 0 else head + 1
            total++
            if (total >= WIN && (total - WIN) % HOP == 0L) infer()
        }
        return on
    }

    private fun infer() {
        System.arraycopy(ring, head, input, 0, WIN - head)
        System.arraycopy(ring, 0, input, WIN - head, head)
        val t0 = System.nanoTime()
        itp.run(input, output)
        nanos += System.nanoTime() - t0; runs++
        var s = 0f
        for (k in SIREN_CLASSES) if (output[0][k] > s) s = output[0][k]
        score = s
        if (s >= TH) { good++; bad = 0 } else { bad++; good = 0 }
        if (!on && good >= ON_N) on = true
        else if (on && bad >= OFF_N) on = false
    }

    override fun close() = itp.close()
}
