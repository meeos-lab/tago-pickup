package ai.meeos.tago.ui.components

import ai.meeos.tago.ui.RearCamUi
import ai.meeos.tago.ui.theme.TagoType
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * 후방 카메라 화면(순정 후방 모니터 문법: 검은 바탕, 영상 가득, 아래 왼쪽에 뒤 센서 세 칸). 기어 R 동안만 상태 영역 전체를 덮는다.
 * 영상은 언리얼이 보낸 JPEG(이미 좌우 반전된 거울상). 센서 칸 색: 0.5m 안 빨강 · 1.0m 안 주황 · 1.5m 안 노랑 · 그 밖 회색.
 */
@Composable
fun RearCamView(cam: RearCamUi, modifier: Modifier = Modifier) {
    val bmp = cam.bitmap ?: remember(cam.stamp) { runCatching { BitmapFactory.decodeByteArray(cam.jpeg, 0, cam.jpeg.size) }.getOrNull() }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = cam.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Text(cam.title, style = TagoType.titleM, color = Color.White)
            Text(cam.hint, style = TagoType.bodyS, color = Color(0xFFBBBBBB))
        }
        Row(Modifier.align(Alignment.BottomStart).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            for ((label, d) in listOf("L" to cam.left, "C" to cam.center, "R" to cam.right)) SensorCell(label, d)
        }
    }
}

@Composable
private fun SensorCell(label: String, dist: Float?) {
    val col = when {
        dist == null -> Color(0xFF3A3A3A)
        dist < 0.5f -> Color(0xFFE53935)
        dist < 1.0f -> Color(0xFFFB8C00)
        dist < 1.5f -> Color(0xFFFDD835)
        else -> Color(0xFF43A047)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(56.dp).height(14.dp).background(col))
        Box(Modifier.width(56.dp).height(14.dp).background(if (dist != null && dist < 1.0f) col else Color(0xFF3A3A3A)))
        Box(Modifier.width(56.dp).height(14.dp).background(if (dist != null && dist < 0.5f) col else Color(0xFF3A3A3A)))
        Spacer(Modifier.height(2.dp))
        Text(if (dist == null) label else String.format("%.1f m", dist), style = TagoType.bodyS, color = Color.White)
    }
}
