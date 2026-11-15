package ai.meeos.tago.ui.components

import ai.meeos.tago.ui.theme.MapLook
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.sin

/**
 * 긴급차량 화면 아래 가장자리의 오로라 빛(뒤에서 온다는 뜻). 색은 MapLook.auroraBlobs 등.
 * 11-15 시안 A(사용자 "너무 정지된 느낌" → 크게·빠르게 했다가 "너무 원형 · 뚝뚝 끊김" · "밀도가 너무 쌤" · "너무 구려진 거 아님?" →
 * 움직이는 시안 셋 중 "A로"): 원래 빛 덩어리(바닥에 중심을 둔 타원 방사형)를 낮고 옅게 깔고, 매 프레임 아주 천천히 흐르게 한다.
 * - 덩어리마다 좌우로 ±4 %(주기 약 44초) 흐르고 높이가 ±4 %(약 31초) 숨쉰다 — 크게 빠르게 움직이면 둥근 덩어리가 드러나고 끊겨 보였다
 * - 진하기는 덩어리 색 알파의 55 %(가운데) → 30 %(중간) → 0(가장자리) — 지도가 비쳐 보이게
 * - 세로 빛줄기·밝기 맥박·커튼 띠는 뺐다(시안 A 에 없다)
 * level(0~1) = 구급차가 가까울수록 1 — 진하기·높이가 조금 커진다. alpha = 나타나고 사라지는 정도(부르는 쪽이 2초에 걸쳐 줄인다).
 * ray 는 예전 빛줄기 색(부르는 쪽 호환용, 지금은 그리지 않는다)
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AuroraEdge(level: Float, alpha: Float, night: Boolean, modifier: Modifier = Modifier,
               blobs: List<MapLook.AuroraBlob> = MapLook.auroraBlobs, ray: Color = MapLook.auroraRay) {   // 듣는 중 빛은 MapLook.auroraListen* 를 넘긴다(11-14)
    val t by produceState(0f) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now -> value = (now - start) / 1000f }   // 매 프레임 — 움직임이 아주 느려 한 프레임 걸음이 보이지 않는다
    }
    val gain = (if (night) MapLook.auroraNightGain else 1f) * (0.85f + 0.15f * level) * alpha
    Canvas(modifier) {
        val w = size.width
        val bandH = size.height * (0.55f + 0.15f * level)   // 가까울수록 조금 높게
        blobs.forEachIndexed { i, b ->
            val cx = w * (b.cx + 0.04f * sin(t / 7f + i))
            val rx = w * 0.5f
            val ry = bandH * (0.9f + 0.08f * sin(t / 5f + i * 0.7f))
            val a = (b.color.alpha * gain).coerceIn(0f, 1f)
            val center = Offset(cx, size.height)
            scale(1f, ry / rx, pivot = center) {
                drawCircle(Brush.radialGradient(0f to b.color.copy(alpha = a * 0.55f), 0.45f to b.color.copy(alpha = a * 0.30f), 0.85f to Color.Transparent,
                    center = center, radius = rx), radius = rx, center = center)
            }
        }
    }
}
