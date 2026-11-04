package ai.meeos.tago.ui.screens

import ai.meeos.tago.R
import ai.meeos.tago.ui.CardTone
import ai.meeos.tago.ui.CardUi
import ai.meeos.tago.ui.Panel
import ai.meeos.tago.ui.TagoUiState
import ai.meeos.tago.ui.components.AnswerChips
import ai.meeos.tago.ui.components.ButtonVariant
import ai.meeos.tago.ui.components.DevPanel
import ai.meeos.tago.ui.components.InfoCard
import ai.meeos.tago.ui.components.MemberList
import ai.meeos.tago.ui.components.MessageBubble
import ai.meeos.tago.ui.components.RoadStrip
import ai.meeos.tago.ui.components.SectionTitle
import ai.meeos.tago.ui.components.SegmentedControl
import ai.meeos.tago.ui.components.StatusHeader
import ai.meeos.tago.ui.components.Stepper
import ai.meeos.tago.ui.components.TagoButton
import ai.meeos.tago.ui.components.TagoToast
import ai.meeos.tago.ui.theme.Dim
import ai.meeos.tago.ui.theme.Tago
import ai.meeos.tago.ui.theme.TagoType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 화면이 부를 수 있는 동작. 컨트롤러 메서드를 MainActivity 가 묶어 준다 */
class TagoActions(
    val onMember: (String) -> Unit,
    val onRoadLink: () -> Unit,
    val onAnswer: (Boolean) -> Unit,
    val onNavi: () -> Unit,
    val onEmergency: () -> Unit,
    val onDial: () -> Unit,
    val onEndEmergency: () -> Unit,
    val onRoadPrimary: () -> Unit,
    val onEndRoad: () -> Unit,
    val onRegion: (String) -> Unit,
    val onToggleDev: () -> Unit,
)

/**
 * 앱 화면 — docs/ui-design.md §4. 순정 설정 UI처럼 흰 바탕에 왼쪽 상태 영역(680) + 얇은 세로 구분선 + 오른쪽 지도.
 * 세로(841×1210, 순정 내비와 1:1 분할)는 데리러 가기(만날 곳·경로·아이 위치가 지도에 있다)만 상태 영역 위(내용만큼, 최대 74%) + 지도 아래.
 * 대기·함께 가기·응급은 상태 영역이 창 전체 — 옆에 순정 내비가 있고, 앞길 스트립·응급 카드가 지도보다 먼저다(실측: 62% 칸에 스트립·버튼·응급이 잘렸다).
 * 지도는 MainActivity 가 가진 WebView(AndroidView)를 map 슬롯으로 받는다.
 */
@Composable
fun TagoScreen(state: TagoUiState, actions: TagoActions, map: @Composable (Modifier) -> Unit) {
    val c = Tago.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val portrait = maxWidth < Dim.portraitBreakpoint
        val panelMaxHeight = maxHeight * Dim.portraitPanelMax
        val panelWidth = (maxWidth * Dim.panelRatio).coerceIn(Dim.panelWidthMin, Dim.panelWidth)   // 가로: 창 폭의 40%(600~680)
        if (portrait && !(state.mapUrl != null && state.panel is Panel.Pickup)) {
            StatusPanel(state, actions, fillHeight = true, compact = true, Modifier.fillMaxSize())
            if (state.toast != null) TagoToast(state.toast, Modifier.align(Alignment.BottomCenter).padding(24.dp))
        } else if (portrait) {
            Column(Modifier.fillMaxSize()) {
                StatusPanel(state, actions, fillHeight = false, compact = true, Modifier.fillMaxWidth().heightIn(max = panelMaxHeight))
                Box(Modifier.fillMaxWidth().height(Dim.line).background(c.divider))
                MapPane(state, map, Modifier.fillMaxWidth().weight(1f).padding(Dim.mapPad))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                StatusPanel(state, actions, fillHeight = true, compact = false, Modifier.width(panelWidth).fillMaxHeight())
                Box(Modifier.width(Dim.line).fillMaxHeight().background(c.divider))
                MapPane(state, map, Modifier.weight(1f).fillMaxHeight().padding(Dim.mapPad))
            }
        }
    }
}

/**
 * 상태 영역: 브랜드 → 헤드라인 → 보조 → 상태별 내용(넘치면 스크롤) → 응급(항상 맨 아래, 내용에 밀려 숨지 않는다).
 * fillHeight = 주어진 높이를 다 쓴다(가로, 세로 전체). false 면 내용만큼만 쓰고 나머지를 지도에 준다. compact = 세로 배치(스트립 축을 줄인다)
 */
@Composable
private fun StatusPanel(state: TagoUiState, actions: TagoActions, fillHeight: Boolean, compact: Boolean, modifier: Modifier) {
    val c = Tago.colors
    Column(modifier.padding(start = Dim.panelPadH, end = Dim.panelPadH, top = Dim.panelPadTop, bottom = Dim.panelPadBottom)) {
        Column(Modifier.weight(1f, fill = fillHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dim.section)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusHeader(ai.meeos.tago.BuildConfig.APP_LABEL, state.voice, state.voiceText, actions.onToggleDev)
                Spacer(Modifier.height(12.dp))
                Text(state.headline, style = TagoType.titleL, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.subline.isNotEmpty()) Text(state.subline, style = TagoType.bodyM, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (val p = state.panel) {
                is Panel.Idle -> IdleContent(p, actions)
                is Panel.Pickup -> PickupContent(state, p, actions)
                is Panel.RoadLink -> RoadContent(state, p, actions, compact)
                is Panel.Emergency -> EmergencyContent(p, actions)
            }
            if (state.regions != null && state.regionTitle != null) Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                SectionTitle(state.regionTitle)
                SegmentedControl(state.regions, state.regionId, actions.onRegion)
            }
            if (state.devLog != null) DevPanel(state.devStatus, state.devLog)
        }
        if (state.emergencyLabel != null) {
            Spacer(Modifier.height(Dim.gap))
            EmergencyButton(state.emergencyLabel, actions)
        }
    }
}

/** 응급 상황 브리핑 버튼: 흰 면 + 테두리 + 빨간 아이콘·글. 빨간 풀카드는 OS 차량 알림 스타일이라 앱에서는 쓰지 않는다 */
@Composable
private fun EmergencyButton(label: String, actions: TagoActions) {
    TagoButton(label, actions.onEmergency, Modifier.fillMaxWidth(), ButtonVariant.DANGER_OUTLINE, icon = R.drawable.ic_medical)
}

@Composable
private fun ColumnScope.IdleContent(p: Panel.Idle, actions: TagoActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
        if (p.roadLinkLabel != null) TagoButton(p.roadLinkLabel, actions.onRoadLink, Modifier.fillMaxWidth(), ButtonVariant.PRIMARY, icon = R.drawable.ic_car, alignStart = true)
        MemberList(p.members) { actions.onMember(it.name) }
    }
}

@Composable
private fun ColumnScope.PickupContent(state: TagoUiState, p: Panel.Pickup, actions: TagoActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
        if (state.question != null) AnswerChips(state.question, actions.onAnswer)
        if (p.card != null) InfoCard(p.card)
        if (p.naviLabel != null) TagoButton(p.naviLabel, actions.onNavi, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, icon = R.drawable.ic_navigation)
        if (p.step in 1..4) Stepper(p.stepLabels, p.step, Modifier.padding(top = 8.dp))
        if (p.message != null) MessageBubble(p.message)
        if (p.members.isNotEmpty()) MemberList(p.members) { actions.onMember(it.name) }
    }
}

@Composable
private fun ColumnScope.RoadContent(state: TagoUiState, p: Panel.RoadLink, actions: TagoActions, compact: Boolean) {
    val c = Tago.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
        InfoCard(CardUi(CardTone.NEUTRAL, null, null, emptyList())) {
            if (p.connecting != null) Text(p.connecting, style = TagoType.bodyM, color = c.text2)
            if (p.strip != null) RoadStrip(p.strip, compact)
        }
        if (state.question != null) AnswerChips(state.question, actions.onAnswer)
        if (p.showButtons) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
            TagoButton(p.primaryLabel, actions.onRoadPrimary, Modifier.weight(1.4f), ButtonVariant.PRIMARY, icon = p.primaryIcon)
            TagoButton(p.endLabel, actions.onEndRoad, Modifier.weight(1f), ButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun ColumnScope.EmergencyContent(p: Panel.Emergency, actions: TagoActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
        InfoCard(p.card)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
            TagoButton(p.dialLabel, actions.onDial, Modifier.weight(1f), ButtonVariant.DANGER)
            TagoButton(p.endLabel, actions.onEndEmergency, Modifier.weight(1f), ButtonVariant.SECONDARY)
        }
    }
}

/** 지도 영역: 1px 테두리 안에 WebView + 빈 상태 글 + 토스트(하단 중앙, 여백 24) */
@Composable
private fun MapPane(state: TagoUiState, map: @Composable (Modifier) -> Unit, modifier: Modifier) {
    val c = Tago.colors
    Box(modifier.background(c.mapBg).border(Dim.line, c.line)) {
        map(Modifier.fillMaxSize())
        if (state.mapUrl == null) Box(Modifier.fillMaxSize().background(c.mapBg), contentAlignment = Alignment.Center) {
            Text(state.mapEmptyText, style = TagoType.bodyM, color = c.text2, modifier = Modifier.padding(horizontal = 48.dp))
        }
        if (state.toast != null) TagoToast(state.toast, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}
