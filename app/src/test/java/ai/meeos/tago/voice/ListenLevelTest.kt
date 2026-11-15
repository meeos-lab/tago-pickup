package ai.meeos.tago.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 말로 검색 소리 막대의 크기: 실제 입력 크기로만 움직인다 */
class ListenLevelTest {
    private fun tone(amp: Double, n: Int = 1600): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (amp * 32767 * sin(2 * PI * 440 * i / 16000)).roundToInt()
            b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test fun pcmSilenceIsZeroAndLoudIsHigh() {
        assertEquals(0f, ListenLevel.levelOfPcm(ByteArray(3200)), 0f)
        assertEquals(0f, ListenLevel.levelOfPcm(ByteArray(0)), 0f)
        val quiet = ListenLevel.levelOfPcm(tone(0.003))     // 약 −53 dBFS
        val talk = ListenLevel.levelOfPcm(tone(0.05))       // 약 −29 dBFS
        val loud = ListenLevel.levelOfPcm(tone(0.5))        // 약 −9 dBFS
        assertTrue("$quiet < $talk < $loud", quiet < talk && talk < loud)
        assertEquals(1f, loud, 0f)
        assertTrue(quiet < 0.1f)
    }

    @Test fun sdkValuesAreAboveTheFloorOfOneListen() {
        ListenLevel.start(now = 1000)
        ListenLevel.sdk(2.0); assertEquals(0f, ListenLevel.level.value, 0f)     // 첫 값 = 바닥 → 0
        ListenLevel.sdk(2.0); assertEquals(0f, ListenLevel.level.value, 0f)     // 그대로 → 조용함
        ListenLevel.sdk(8.0); assertEquals(1f, ListenLevel.level.value, 0f)     // 바닥 + 잡음 1 + 폭 5
        ListenLevel.sdk(5.0); assertEquals(0.4f, ListenLevel.level.value, 1e-6f)
        ListenLevel.stop()
        // 말하지 않을 때 실측 흔들림(에뮬레이터 4.52~4.85)은 잡음 → 0(11-15 "말안하고 있는데 이렇게 되면 어쩌냐")
        ListenLevel.start(now = 2000)
        for (v in listOf(4.517, 4.848, 4.6, 4.848, 4.52)) { ListenLevel.sdk(v); assertEquals(0f, ListenLevel.level.value, 0f) }
        ListenLevel.stop()
        assertEquals(0f, ListenLevel.level.value, 0f); assertEquals(0L, ListenLevel.since.value)
        ListenLevel.sdk(9.0); assertEquals(0f, ListenLevel.level.value, 0f)     // 듣지 않을 때는 무시
    }

    @Test fun pcmWinsOverSdkInTheSameListen() {
        ListenLevel.start(now = 1000)
        ListenLevel.pcm(tone(0.5)); assertEquals(1f, ListenLevel.level.value, 0f)
        ListenLevel.sdk(0.0); ListenLevel.sdk(10.0)
        assertEquals(1f, ListenLevel.level.value, 0f)                           // 녹음을 넣는 중엔 STT 값을 쓰지 않는다
        ListenLevel.stop()
    }
}
