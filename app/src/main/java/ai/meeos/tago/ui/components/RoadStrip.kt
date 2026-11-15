package ai.meeos.tago.ui.components

import ai.meeos.tago.ui.BinColor
import ai.meeos.tago.ui.MarkerKind
import ai.meeos.tago.ui.RoadStripUi
import ai.meeos.tago.ui.theme.Tago
import ai.meeos.tago.ui.theme.TagoType
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/** 칸 약어(B-61, WCAG 1.4.1 — 색만으로 뜻을 전하지 않는다): 원활·모름은 글자 없음(초록 면 / 회색 면 + 테두리로 모양이 다르다), 나머지는 칸 안 글자 한 줄.
 *  굵은 띠(72 dp)라 한국어 두 글자가 가로 한 줄에 들어간다. 화면 읽기용 긴 이름은 [binName] */
private fun binMark(b: BinColor, ko: Boolean): String = when (b) {
    BinColor.DANGER -> if (ko) "위험" else "!"
    BinColor.PURPLE -> if (ko) "주의" else "!"
    BinColor.RAIN -> if (ko) "비" else "R"
    BinColor.SLOW -> if (ko) "정체" else "J"
    BinColor.MEDIUM -> if (ko) "서행" else "S"
    BinColor.FREE, BinColor.UNKNOWN -> ""
}
private fun binName(b: BinColor, ko: Boolean): String = when (b) {
    BinColor.DANGER -> if (ko) "위험" else "hazard"
    BinColor.PURPLE -> if (ko) "주의" else "caution"
    BinColor.RAIN -> if (ko) "비" else "rain"
    BinColor.SLOW -> if (ko) "정체" else "jam"
    BinColor.MEDIUM -> if (ko) "서행" else "slow"
    BinColor.FREE -> if (ko) "원활" else "clear"
    BinColor.UNKNOWN -> if (ko) "정보 없음" else "no data"
}
/** 칸 색 위 글자: 검정·흰 중 대비가 큰 쪽(WCAG 대비식). 테마마다 칸 색이 달라도 4.5:1 이상을 고른다 */
private fun inkOn(bg: Color): Color {
    val l = bg.luminance()
    return if ((l + 0.05) / 0.05 >= 1.05 / (l + 0.05)) Color.Black else Color.White
}

/**
 * 앞길 세로 띠 — 내비의 경로 바처럼 거리 축 위에 내 차·위험·일행·휴게소를 놓는다(docs/ui-design.md §5).
 * 위가 멀리(앞 3km, 축 밖의 안내 중 위험·일행은 맨 위에 붙는다), 아래가 내 차(0), 그 아래 뒤 2km.
 * 11-13 디자인 손질: 띠를 20 → 72 dp 로 굵게(도로처럼), 감싸던 빈 테두리 상자를 없애고 패널 폭을 그대로 쓴다. 1 km 눈금.
 * 색은 위험 빨강 · 주의 주황(정체·서행·주의 — 정체는 길이만큼 주황으로 칠한 구간) · 원활 초록만. 비는 회색 면 + "비" 글자, 모름은 옅은 회색 면 + 테두리.
 * 표식이 겹치면 아래에서 위로 60 dp 간격을 지킨다(위치는 500m 칸 단위라 약간 밀려도 뜻은 같다).
 * compact(세로 배치·버튼이 많을 때): 축을 300/64 로 줄여 버튼과 함께 상태 영역 안에 들어가게 한다.
 * 글자는 본문 한 단계(bodyM 28, 안내 중 위험·내 차는 굵게).
 */
@Composable
fun RoadStrip(s: RoadStripUi, compact: Boolean = false) {
    val c = Tago.colors
    val top = 44f            // 축 위 여백(라벨 자리)
    val aheadPx = if (compact) 300f else 440f
    val behindPx = if (compact) 64f else 96f
    val rowH = 60f
    val roadW = 72f          // 띠 폭(예전 20 의 3.6 배)
    // 왼쪽 거리 글 칸: 기본 144 dp("후방 1.3km"), 실제 글("1.3km behind" 등)이 더 넓으면 잰 폭만큼 넓힌다 — 거리 글이 잘리지 않게
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = (s.markers.map { it.distLabel } + s.aheadLabel).filter { it.isNotEmpty() }
        .maxOfOrNull { tm.measure(it, TagoType.bodyMBold, softWrap = false, maxLines = 1).size.width } ?: 0
    val leftW = max(144f, with(density) { widest.toDp().value } + 6f)
    val lineX = leftW + 16f + roadW / 2   // 띠 가운데 x
    val rightX = lineX + roadW / 2 + 18f
    val ko = LocalConfiguration.current.locales[0].language != "en"
    fun yOf(d: Int): Float = if (d >= 0) top + aheadPx * (s.aheadM - d.coerceIn(0, s.aheadM)) / s.aheadM
        else top + aheadPx + behindPx * (-d).coerceAtMost(s.behindM) / s.behindM
    val placed = s.markers.map { it to yOf(it.distM) }.sortedBy { it.second }
    // 겹침 풀기: 내 차는 제자리(띠 위 0 지점)에 두고, 그 위 표식은 위로, 아래 표식은 아래로 rowH 씩 민다.
    // 위로 밀다 띠 위쪽 여백을 넘으면 예전 방식(위에서 아래로 차례로)으로 둔다
    val ys = ArrayList<Float>(placed.size)
    val iMe = placed.indexOfFirst { it.first.kind == MarkerKind.ME }
    placed.forEach { ys += it.second }
    if (iMe >= 0) {
        for (i in iMe - 1 downTo 0) ys[i] = minOf(placed[i].second, ys[i + 1] - rowH)
        for (i in iMe + 1 until placed.size) ys[i] = max(placed[i].second, ys[i - 1] + rowH)
    }
    if (iMe < 0 || (ys.firstOrNull() ?: top) < top - 8f) placed.forEachIndexed { i, p -> ys[i] = if (i == 0) p.second else max(p.second, ys[i - 1] + rowH) }
    val total = max(top + aheadPx + behindPx + 32f, (ys.lastOrNull() ?: 0f) + rowH / 2 + 8f)
    val roadLeft = lineX - roadW / 2

    Box(Modifier.fillMaxWidth().height(total.dp)) {
        // 축 끝 글("앞 3km"): 축 끝 가까이(맨 위 한 줄) 표식이 있으면 그 거리 글과 겹치므로 뺀다(축 밖이라 맨 위에 붙인 것 포함)
        if (s.markers.none { it.distM >= s.aheadM - s.aheadM * rowH / aheadPx }) Text(s.aheadLabel, style = TagoType.bodyM, color = c.text2, maxLines = 1, softWrap = false,
            modifier = Modifier.offset(x = 0.dp, y = 0.dp).width(leftW.dp), textAlign = TextAlign.End)
        // 띠 앞쪽: 500m 칸 색(먼 칸이 위) + 칸 안 약어(색만으로 뜻을 전하지 않게, B-61). 칸 사이 틈 없이 이어 칠해 정체 구간이 한 덩어리로 보인다.
        // 화면 읽기: 띠 하나에 '앞 0~500m 원활, …' 를 붙인다(칸마다 읽으면 표식과 섞인다)
        val binM = if (s.bins.isEmpty()) 0 else s.aheadM / s.bins.size
        val axisDesc = s.bins.mapIndexed { i, b -> (if (ko) "앞 ${i * binM}~${(i + 1) * binM}m " else "${i * binM}-${(i + 1) * binM} m ahead ") + binName(b, ko) }.joinToString(", ")
        val binPx = if (s.bins.isEmpty()) 0f else aheadPx / s.bins.size
        if (s.bins.isNotEmpty()) Column(Modifier.offset(x = roadLeft.dp, y = top.dp).width(roadW.dp).height(aheadPx.dp).semantics { contentDescription = axisDesc }) {
            s.bins.reversed().forEachIndexed { j, b ->
                // 칸 글자는 그 칸 위에 표식이 없을 때만(표식 밑에 깔려 반쯤 가려진 글자는 읽히지 않는다 — 그때는 표식 이름이 뜻을 전한다)
                val y0 = top + j * binPx
                val covered = ys.any { it > y0 - 26f && it < y0 + binPx + 26f }
                val color = when (b) {
                    BinColor.DANGER -> c.red
                    BinColor.SLOW, BinColor.MEDIUM, BinColor.PURPLE -> c.orange
                    BinColor.RAIN -> c.text2
                    BinColor.UNKNOWN -> c.surfaceAlt
                    BinColor.FREE -> c.green
                }
                // 서행은 주황 테두리(속이 빈 칸)로 정체(주황 면)와 모양을 달리한다. 모름은 옅은 회색 면 + 회색 테두리
                val cell = when (b) {
                    BinColor.MEDIUM -> Modifier.border(4.dp, color)
                    BinColor.UNKNOWN -> Modifier.background(color).border(1.dp, c.line)
                    else -> Modifier.background(color)
                }
                Box(Modifier.weight(1f).fillMaxWidth().then(cell), contentAlignment = Alignment.Center) {
                    val mark = binMark(b, ko)
                    if (mark.isNotEmpty() && !covered) Text(mark, style = TagoType.bodyMBold, color = if (b == BinColor.MEDIUM) c.text else inkOn(color),
                        textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                }
            }
        } else Box(Modifier.offset(x = roadLeft.dp, y = top.dp).width(roadW.dp).height(aheadPx.dp).background(c.surfaceAlt).border(1.dp, c.line))
        // 띠 뒤쪽(내 차 뒤 2km): 옅은 회색 도로
        Box(Modifier.offset(x = roadLeft.dp, y = (top + aheadPx).dp).width(roadW.dp).height(behindPx.dp).background(c.surfaceAlt).border(1.dp, c.line))
        // 1 km 눈금: 띠를 가로지르는 바탕색 줄 + 왼쪽 작은 눈금. 거리 글은 그 높이에 표식 거리 글이 없을 때만
        for (km in 1 until s.aheadM / 1000) {
            val y = yOf(km * 1000)
            Box(Modifier.offset(x = roadLeft.dp, y = (y - 1.5f).dp).width(roadW.dp).height(3.dp).background(c.bg))
            Box(Modifier.offset(x = (roadLeft - 14).dp, y = (y - 1).dp).width(12.dp).height(2.dp).background(c.text2))
            if (ys.none { abs(it - y) < rowH * 0.8f }) Text("${km}km", style = TagoType.bodyM, color = c.text3, maxLines = 1, softWrap = false,
                modifier = Modifier.offset(x = 0.dp, y = (y - 18).dp).width((leftW - 8).dp), textAlign = TextAlign.End)
        }

        placed.forEachIndexed { i, (m, _) ->
            val y = ys[i]
            val top1 = m.kind == MarkerKind.HAZARD_TOP || m.kind == MarkerKind.SIREN
            val labelColor = when (m.kind) { MarkerKind.HAZARD_TOP, MarkerKind.SIREN -> c.red; MarkerKind.REST -> c.text2; else -> c.text }
            // 거리(띠 왼쪽): 본문 크기, 안내 중인 위험·긴급차량은 굵은 빨강
            if (m.distLabel.isNotEmpty()) Text(m.distLabel, style = if (top1) TagoType.bodyMBold else TagoType.bodyM, color = if (top1) c.red else c.text2, maxLines = 1, softWrap = false,
                modifier = Modifier.offset(x = 0.dp, y = (y - 18).dp).width(leftW.dp), textAlign = TextAlign.End)
            // 표식(띠 위)
            val (fill, stroke, iconTint) = when (m.kind) {
                MarkerKind.ME -> Triple(c.ink, c.ink, c.onInk)
                MarkerKind.HAZARD_TOP, MarkerKind.SIREN -> Triple(c.red, c.red, c.onInk)
                MarkerKind.HAZARD -> Triple(c.surface, c.line, c.text2)
                MarkerKind.MEMBER -> Triple(c.surface, c.ink, c.text)
                MarkerKind.REST -> Triple(c.surface, c.line, c.text2)
            }
            if (m.sign != null) {
                // 주의 표지(삼각, 색 그대로): 지도 표지판과 같은 그림. 안내 중인 위험은 한 단계 크게
                val sz = if (top1) 64f else 52f
                Image(painterResource(m.sign), contentDescription = null, modifier = Modifier.offset(x = (lineX - sz / 2).dp, y = (y - sz / 2 - 2).dp).size(sz.dp))
            } else if (m.kind == MarkerKind.ME) {
                // 내 차: 띠 폭에 가까운 검정 네모(64) + 흰 차 아이콘 — 가장 먼저 눈에 들어오게
                Box(Modifier.offset(x = (lineX - 32).dp, y = (y - 32).dp).size(64.dp).background(fill).border(2.dp, c.bg), contentAlignment = Alignment.Center) {
                    TagoIcon(m.icon, iconTint, 40.dp)
                }
            } else {
                Box(Modifier.offset(x = (lineX - 26).dp, y = (y - 26).dp).size(52.dp).clip(CircleShape).background(fill)
                    .border(2.dp, stroke, CircleShape), contentAlignment = Alignment.Center) {
                    TagoIcon(m.icon, iconTint, 30.dp)
                }
            }
            // 이름 + 보조(띠 오른쪽). 말줄임표를 쓰지 않는다(11-13): 보조 글이 남은 폭에 안 들어가면 " · " 단위로 뒤부터 덜고,
            // 다 덜어도 안 되면 보조 글을 뺀다. 이름만으로도 넘치면 이름을 두 줄로 쓴다(이름 = 뜻이라 자르지 않는다)
            val labelStyle = if (top1 || m.kind == MarkerKind.ME) TagoType.bodyMBold else TagoType.bodyM
            BoxWithConstraints(Modifier.offset(y = (y - 18).dp).padding(start = rightX.dp).fillMaxWidth()) {
                val avail = constraints.maxWidth
                val labelW = tm.measure(m.label, labelStyle, softWrap = false, maxLines = 1).size.width
                val gap = with(density) { 10.dp.roundToPx() }
                val meta = if (m.meta == null || labelW >= avail) null else {
                    val parts = m.meta.split(" · ")
                    (parts.size downTo 1).map { n -> "· " + parts.take(n).joinToString(" · ") }
                        .firstOrNull { labelW + gap + tm.measure(it, TagoType.bodyM, softWrap = false, maxLines = 1).size.width <= avail }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, style = labelStyle, color = labelColor, maxLines = 2)
                    if (meta != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(meta, style = TagoType.bodyM, color = c.text2, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
