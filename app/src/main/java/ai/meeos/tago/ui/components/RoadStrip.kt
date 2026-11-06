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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 앞길 세로 스트립 — 내비의 경로 바처럼 거리 축 위에 내 차·위험·일행·휴게소를 놓는다(docs/ui-design.md §5).
 * 위가 멀리(앞 3km, 축 밖의 안내 중 위험·일행은 맨 위에 붙는다), 아래가 내 차(0), 그 아래 뒤 2km. 축 색은 500m 칸마다 앞차 평균 속도(초록 원활·주황·빨강 정체), 비(파랑), 위험(빨강·보라).
 * 표식이 겹치면 아래에서 위로 52px 간격을 지킨다(위치는 500m 칸 단위라 약간 밀려도 뜻은 같다).
 * compact(세로 배치, 내비와 분할): 축을 300/60px 로 줄여 헤드라인·버튼과 함께 상태 영역 안에 들어가게 한다(실측 — 440px 이면 현재 위험·내 차·버튼이 잘렸다).
 */
@Composable
fun RoadStrip(s: RoadStripUi, compact: Boolean = false) {
    val c = Tago.colors
    val top = 36f            // 축 위 여백(라벨 자리)
    val aheadPx = if (compact) 300f else 440f
    val behindPx = if (compact) 60f else 88f
    val rowH = 52f
    val lineX = 176f         // 축의 x (왼쪽 거리 라벨 "후방 1.5km" 가 한 줄에 들어가는 폭)
    fun yOf(d: Int): Float = if (d >= 0) top + aheadPx * (s.aheadM - d.coerceIn(0, s.aheadM)) / s.aheadM
        else top + aheadPx + behindPx * (-d).coerceAtMost(s.behindM) / s.behindM
    val placed = s.markers.map { it to yOf(it.distM) }.sortedBy { it.second }
    val ys = ArrayList<Float>(placed.size)
    placed.forEachIndexed { i, p -> ys += if (i == 0) p.second else max(p.second, ys[i - 1] + rowH) }
    val total = max(top + aheadPx + behindPx + 28f, (ys.lastOrNull() ?: 0f) + rowH / 2 + 8f)

    Box(Modifier.fillMaxWidth().height(total.dp)) {
        // 축 끝 글("앞 3km"): 축 끝 가까이(맨 위 한 줄 = rowH) 표식이 있으면 그 거리 글과 겹치므로 뺀다(축 밖이라 맨 위에 붙인 것 포함)
        if (s.markers.none { it.distM >= s.aheadM - s.aheadM * rowH / aheadPx }) Text(s.aheadLabel, style = TagoType.labelM, color = c.text2, maxLines = 1, modifier = Modifier.offset(x = 0.dp, y = 0.dp).width((lineX - 20).dp), textAlign = TextAlign.End)
        // 축: 앞쪽은 칸 색(먼 칸이 위), 뒤쪽은 가는 회색 선
        if (s.bins.isNotEmpty()) Column(Modifier.offset(x = (lineX - 6).dp, y = top.dp).width(12.dp).height(aheadPx.dp)) {
            s.bins.reversed().forEach { b ->
                val color = when (b) {
                    BinColor.DANGER, BinColor.SLOW -> c.red
                    BinColor.PURPLE -> c.purple
                    BinColor.RAIN -> c.blue
                    BinColor.UNKNOWN -> c.line
                    BinColor.MEDIUM -> c.orange
                    BinColor.FREE -> c.green
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(color))
            }
        } else Box(Modifier.offset(x = (lineX - 6).dp, y = top.dp).width(12.dp).height(aheadPx.dp).background(c.line))
        Box(Modifier.offset(x = (lineX - 2).dp, y = (top + aheadPx).dp).width(4.dp).height(behindPx.dp).background(c.line))

        placed.forEachIndexed { i, (m, _) ->
            val y = ys[i]
            val top1 = m.kind == MarkerKind.HAZARD_TOP || m.kind == MarkerKind.SIREN
            val labelColor = when (m.kind) { MarkerKind.HAZARD_TOP, MarkerKind.SIREN -> c.red; MarkerKind.REST -> c.text2; else -> c.text }
            // 거리(축 왼쪽)
            if (m.distLabel.isNotEmpty()) Text(m.distLabel, style = if (top1) TagoType.bodySBold else TagoType.bodyS, color = if (top1) c.red else c.text2, maxLines = 1, softWrap = false,
                modifier = Modifier.offset(x = 0.dp, y = (y - 17).dp).width((lineX - 32).dp), textAlign = TextAlign.End)
            // 표식(축 위)
            val (fill, stroke, iconTint) = when (m.kind) {
                MarkerKind.ME -> Triple(c.ink, c.ink, c.onInk)
                MarkerKind.HAZARD_TOP, MarkerKind.SIREN -> Triple(c.red, c.red, c.onInk)
                MarkerKind.HAZARD -> Triple(c.surface, c.line, c.text2)
                MarkerKind.MEMBER -> Triple(c.surface, c.ink, c.text)
                MarkerKind.REST -> Triple(c.surface, c.line, c.text2)
            }
            if (m.sign != null) {
                // 주의 표지(삼각, 색 그대로): 지도 표지판과 같은 그림. 안내 중인 위험은 한 단계 크게
                val sz = if (top1) 52f else 44f
                Image(painterResource(m.sign), contentDescription = null, modifier = Modifier.offset(x = (lineX - sz / 2).dp, y = (y - sz / 2 - 2).dp).size(sz.dp))
            } else {
                val shapeMod = if (m.kind == MarkerKind.ME) Modifier else Modifier.clip(CircleShape)
                Box(Modifier.offset(x = (lineX - 22).dp, y = (y - 22).dp).size(44.dp).then(shapeMod).background(fill)
                    .then(if (m.kind == MarkerKind.ME) Modifier else Modifier.border(2.dp, stroke, CircleShape)), contentAlignment = Alignment.Center) {
                    TagoIcon(m.icon, iconTint, 26.dp)
                }
            }
            // 이름 + 보조(축 오른쪽). 남은 폭 안에서 재야 말줄임이 걸린다(offset 만 쓰면 카드 밖으로 나간다)
            Row(Modifier.offset(y = (y - 18).dp).padding(start = (lineX + 36).dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.label, style = if (top1 || m.kind == MarkerKind.ME) TagoType.bodySBold else TagoType.bodyS, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.meta != null) {
                    Spacer(Modifier.width(10.dp))
                    Text("· ${m.meta}", style = TagoType.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
