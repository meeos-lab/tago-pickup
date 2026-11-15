package ai.meeos.tago.ui.screens

import ai.meeos.tago.R
import ai.meeos.tago.ui.AlertTone
import ai.meeos.tago.ui.AlertUi
import ai.meeos.tago.ui.CardTone
import ai.meeos.tago.ui.CardUi
import ai.meeos.tago.ui.ParkingUi
import ai.meeos.tago.ui.StudyUi
import ai.meeos.tago.ui.ChargeReportUi
import ai.meeos.tago.ui.SurveyTileUi
import ai.meeos.tago.ui.components.ButtonSize
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration

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
    // 연구 기록(경고 반응 로그) — 대기 화면 버튼, 동의 화면, 기록 상태
    val onStudyOpen: () -> Unit = {},
    val onStudyAge: (String) -> Unit = {},
    val onStudyYears: (Int) -> Unit = {},
    val onStudyCar: (String) -> Unit = {},
    val onStudyAgree: () -> Unit = {},
    val onStudyClose: () -> Unit = {},
    val onStudyExport: () -> Unit = {},
    val onStudyUpload: (String) -> Unit = {},
    val onStudyStop: () -> Unit = {},
    val onLicensesOpen: () -> Unit = {},
    val onLicensesClose: () -> Unit = {},
    // 충전기 고장 신고(1분 설문)
    val onChargeOpen: () -> Unit = {},
    val onChargePick: (String) -> Unit = {},
    val onChargeSkip: () -> Unit = {},
    val onChargeBack: () -> Unit = {},
    val onChargeClose: () -> Unit = {},
    // 최소 우회 안내 — [우회 안내]를 누를 때만 순정 내비에 나들목을 보낸다(FR-G-36)
    val onDetour: () -> Unit = {},
    val onAlertWrong: () -> Unit = {},   // [경고 틀림](정차 중, 기록만)
    val onDemoLap: () -> Unit = {},      // [시연 한 바퀴](시연 빌드 전용)
    val onParkFix: () -> Unit = {},      // 주차 위치 [고치기]·[완료]
    val onParkFloor: (Boolean) -> Unit = {},   // 한 층 위(true)·아래(false)
    val onParkClear: () -> Unit = {},    // 주차 기록 지우기
    // 목적지 검색·지정(차대차 판 첫 화면, 정차 중)
    val onDestOpen: () -> Unit = {},
    val onDestQuery: (String) -> Unit = {},
    val onDestSearch: () -> Unit = {},
    val onDestPick: (Int) -> Unit = {},
    val onDestClear: () -> Unit = {},
    val onDestClose: () -> Unit = {},
    val onDestVoice: () -> Unit = {},          // 말로 검색(마이크, 정차 중) — 다시 누르면 멈춤
    val onRealLoc: (Boolean) -> Unit = {},     // 설정: 내 실제 위치 쓰기 켬/끔
    // 주변 차 연결 / 일행 나누기(11-14)
    val onAutoLink: (Boolean) -> Unit = {},    // 주행 시 자동 연결 켬/끔(첫 화면 한 줄·설정) — 동의 전이면 동의 판을 띄운다
    val onConsentWithdraw: () -> Unit = {},   // 설정 [동의 거두기](11-15)
    val onConsent: (Boolean) -> Unit = {},     // 첫 실행 동의 판: true = 동의하고 켜기, false = 나중에
    val onConvoy: () -> Unit = {},             // [일행 만들기](btn_convoy) — 연결 전이면 먼저 연결한 뒤 일행을 만든다
    val onPlayToggle: () -> Unit = {},         // 시연 주행 재생 막대(시연 판만): 재생·멈춤
    val onPlaySpeed: () -> Unit = {},          // 배속 바꾸기(×1 → ×2 → ×4 → ×8 → ×1)
    val onPlayStop: () -> Unit = {},           // 막대의 ✕: 시연 주행 끝내기
    val onPlaySeek: (Float) -> Unit = {},      // 진행 줄에서 누른 자리(0~1)로 옮기기 — 앞·뒤 모두
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
        if (state.rearCam != null) {
            // 후진 중: 언리얼 장면의 후방 카메라가 화면 전체를 덮는다(순정 후방 모니터처럼). 기어가 R 에서 벗어나거나 프레임이 끊기면 원래 화면으로
            ai.meeos.tago.ui.components.RearCamView(state.rearCam, Modifier.fillMaxSize())
        } else if (!ai.meeos.tago.BuildConfig.PICKUP && state.panel !is Panel.Pickup) {
            // 픽업 기능이 없는 판: 지도 우선 화면(11-13 시안 A — 지도 가득 + 떠 있는 판 하나, 주행 중엔 필요한 것만). MapFirstScreen.kt
            MapFirstScreen(state, actions, map)
        } else if (portrait && !(state.mapUrl != null && state.panel is Panel.Pickup)) {
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPanel(state: TagoUiState, actions: TagoActions, fillHeight: Boolean, compact: Boolean, modifier: Modifier) {
    val c = Tago.colors
    Box(modifier) {
    Column((if (fillHeight) Modifier.fillMaxSize() else Modifier).padding(start = Dim.panelPadH, end = Dim.panelPadH, top = Dim.panelPadTop, bottom = Dim.panelPadBottom)) {
        Column(Modifier.weight(1f, fill = fillHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dim.section)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusHeader(ai.meeos.tago.BuildConfig.APP_LABEL, state.voice, state.voiceText, actions.onToggleDev)
                Spacer(Modifier.height(12.dp))
                when {
                    // 경고 중: 거리 숫자가 화면에서 가장 크게 → 제목 한 줄 → 본문 한 줄(11-13 디자인 손질, 뜻은 헤드라인과 같다)
                    state.alert != null -> AlertHead(state.alert)
                    else -> {
                        Text(state.headline, style = TagoType.titleL, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (state.subline.isNotEmpty()) Text(state.subline, style = TagoType.bodyM, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.notice != null) Text(state.notice, style = TagoType.bodyM, color = c.red, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (val p = state.panel) {
                is Panel.Idle -> IdleContent(p, actions)
                is Panel.Pickup -> PickupContent(state, p, actions)
                is Panel.RoadLink -> RoadContent(state, p, actions, compact)
                is Panel.Emergency -> EmergencyContent(p, actions)
                is Panel.Study -> StudyContent(p.ui, actions)
                is Panel.Licenses -> LicensesContent(p.ui, actions)
                is Panel.ChargeReport -> ChargeReportContent(p.ui, actions)
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
    // 긴급차량 배너(청각 보조): 레이아웃을 밀지 않고 맨 위에 **겹쳐** 뜬다 — 브랜드·음성 칩 자리(위 여백 + 머리 줄)만 덮고 헤드라인·버튼은 가리지 않는다.
    // 흑백 반전 면 + 큰 글자 한 줄, 정적(깜빡임 없음). 소리를 못 듣는 운전자도 진로 양보를 화면으로 안다. 배너가 뜨고 사라질 때 아래 버튼이 움직이지 않아 오터치가 없다
    if (state.banner != null) Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(Dim.panelPadTop + 40.dp).background(c.text).padding(horizontal = Dim.panelPadH), contentAlignment = Alignment.CenterStart) {
        Text(state.banner, style = TagoType.titleS, color = c.bg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    }
}

/** 응급 상황 브리핑 버튼: 흰 면 + 테두리 + 빨간 아이콘·글. 빨간 풀카드는 OS 차량 알림 스타일이라 앱에서는 쓰지 않는다 */
@Composable
private fun EmergencyButton(label: String, actions: TagoActions) {
    TagoButton(label, actions.onEmergency, Modifier.fillMaxWidth(), ButtonVariant.DANGER_OUTLINE, icon = R.drawable.ic_medical)
}

/** 경고 화면 머리: [후방] 큰 숫자 + 단위(같은 줄, 글자 바닥 맞춤) → 제목(titleM) → 본문(bodyM). 위험이면 숫자를 빨강, 정체·긴급차량은 본문색(긴급차량은 검은 배너가 따로 뜬다) */
@Composable
private fun AlertHead(a: AlertUi) {
    val c = Tago.colors
    val numColor = if (a.tone == AlertTone.DANGER) c.red else c.text
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) {}) {
            if (a.lead != null) { Text(a.lead, style = TagoType.titleM, color = numColor, modifier = Modifier.alignByBaseline()); Spacer(Modifier.width(14.dp)) }
            Text(a.num, style = TagoType.display, color = numColor, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(10.dp))
            Text(a.unit, style = TagoType.titleM, color = numColor, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        }
        Text(a.title, style = TagoType.titleM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.body.isNotEmpty()) Text(a.body, style = TagoType.bodyM, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 작은 글자 줄 버튼(첫 화면의 부가 항목, "방금 경고가 틀렸어요"): 본문 크기·보조색·밑줄. 누르는 자리는 높이 64 dp 를 지킨다 */
@Composable
private fun TextLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tago.colors
    Box(modifier.heightIn(min = 64.dp).clickable(onClick = ai.meeos.tago.ui.components.debounced(onClick)), contentAlignment = Alignment.CenterStart) {
        Text(label, style = TagoType.bodyM.copy(textDecoration = TextDecoration.Underline), color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColumnScope.IdleContent(p: Panel.Idle, actions: TagoActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Dim.gap)) {
        if (p.roadLinkLabel != null) TagoButton(p.roadLinkLabel, actions.onRoadLink, Modifier.fillMaxWidth(), ButtonVariant.PRIMARY, icon = R.drawable.ic_car, alignStart = true)
        MemberList(p.members) { actions.onMember(it.name) }
        if (p.studyLabel != null) TagoButton(p.studyLabel, actions.onStudyOpen, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, ButtonSize.M, icon = R.drawable.ic_timer, alignStart = true)
        if (p.licensesLabel != null) TagoButton(p.licensesLabel, actions.onLicensesOpen, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, ButtonSize.M, alignStart = true)
        if (p.demoLabel != null) TagoButton(p.demoLabel, actions.onDemoLap, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, ButtonSize.M, icon = R.drawable.ic_car, alignStart = true)
        if (p.summary != null) InfoCard(p.summary)
        if (p.parking != null) ParkingCard(p.parking, actions)
    }
}

/** 주차 위치 한 줄 + [고치기]. 순정 문법: 흰 면·검정 선·직각, 둥근 카드·색 강조 없음. 고치는 중에는 한 층 위·아래·지우기·완료 */
@Composable
private fun ParkingCard(p: ParkingUi, actions: TagoActions) {
    InfoCard(CardUi(CardTone.INFO, p.line, R.drawable.ic_parking, emptyList())) {
        if (p.edit == null) {
            TagoButton(p.fixLabel, actions.onParkFix, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, ButtonSize.M, alignStart = true)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                TagoButton(p.edit.upLabel, { actions.onParkFloor(true) }, Modifier.weight(1f), ButtonVariant.SECONDARY, ButtonSize.M)
                TagoButton(p.edit.downLabel, { actions.onParkFloor(false) }, Modifier.weight(1f), ButtonVariant.SECONDARY, ButtonSize.M)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                TagoButton(p.edit.clearLabel, actions.onParkClear, Modifier.weight(1f), ButtonVariant.SECONDARY, ButtonSize.M)
                TagoButton(p.edit.doneLabel, actions.onParkFix, Modifier.weight(1f), ButtonVariant.PRIMARY, ButtonSize.M)
            }
        }
    }
}

/** 연구 기록: 동의 안내(얇은 테두리 카드) → 연령대 → 운전 경력 → 차종 → [동의하고 기록 시작][동의 안 함]. 기록 중이면 상태 → 내보내기 → 업로드 → 끄기 */
@Composable
internal fun ColumnScope.StudyContent(u: StudyUi, actions: TagoActions) {
    val c = Tago.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dim.section)) {
        if (!u.active) {
            // 동의 문구는 잘리면 안 된다: 카드 줄(최대 3줄) 대신 줄 제한 없이 그린다
            InfoCard(CardUi(CardTone.NEUTRAL, u.consentTitle, null, emptyList())) {
                u.consentLines.forEach { Text(it, style = TagoType.bodyS, color = c.text) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                SectionTitle(u.ageTitle)
                u.ageRows.forEach { row -> SegmentedControl(row, u.ageSel, actions.onStudyAge) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                SectionTitle(u.yearsTitle)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
                    TagoButton("−", { actions.onStudyYears(-1) }, Modifier.width(120.dp), ButtonVariant.SECONDARY, ButtonSize.M)
                    Text(u.yearsLabel, style = TagoType.titleS, color = c.text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    TagoButton("+", { actions.onStudyYears(1) }, Modifier.width(120.dp), ButtonVariant.SECONDARY, ButtonSize.M)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                SectionTitle(u.carTitle)
                SegmentedControl(u.carItems, u.carSel, actions.onStudyCar)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                TagoButton(u.agreeLabel, actions.onStudyAgree, Modifier.weight(1.4f), ButtonVariant.PRIMARY, enabled = u.agreeEnabled)
                TagoButton(u.declineLabel, actions.onStudyClose, Modifier.weight(1f), ButtonVariant.SECONDARY)
            }
        } else {
            TagoButton(u.exportLabel, actions.onStudyExport, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, icon = R.drawable.ic_check, alignStart = true)
            Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                SectionTitle(u.uploadTitle)
                SegmentedControl(u.uploadItems, u.uploadSel, actions.onStudyUpload)
                Text(u.uploadNote, style = TagoType.bodyS, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                TagoButton(u.stopLabel, actions.onStudyStop, Modifier.weight(1f), ButtonVariant.SECONDARY)
                TagoButton(u.closeLabel, actions.onStudyClose, Modifier.weight(1f), ButtonVariant.PRIMARY)
            }
        }
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
        // 질문 중에는 답 칩을 스트립 **위**에 둔다(V-07): 좁은 창에서 스트립이 길면 칩이 스크롤 밖으로 밀려 안 보였다(충돌 "괜찮으세요?"의 [괜찮아요][도움 필요])
        if (state.question != null) AnswerChips(state.question, actions.onAnswer)
        // 주차 위치(정차 중에만): 도착해 세운 뒤에는 이것이 가장 쓸모 있어 스트립 위에 둔다(아래에 두면 길쭉한 스트립 밑 스크롤 밖으로 밀렸다)
        if (p.parking != null) ParkingCard(p.parking, actions)
        // 띠를 감싸던 빈 테두리 상자는 없앴다(11-13 디자인 손질) — 띠가 패널 폭을 그대로 쓴다
        if (p.connecting != null) Text(p.connecting, style = TagoType.bodyM, color = c.text2)
        if (p.strip != null) RoadStrip(p.strip, compact || p.chargeLabel != null || p.detourLabel != null || p.wrongLabel != null || p.summary != null || p.parking != null || !p.showButtons)   // 휴게소 정차·질문 중에는 짧은 축 — 신고 버튼·답 칩 자리
        // 검은 채움은 한 화면에 하나: 우회 안내가 있으면 그것, 경고·질문·주차 고치기 중이면 [가족 차 묶기]는 테두리 버튼으로 내린다
        val blackTaken = state.alert != null || p.detourLabel != null || state.question != null || p.parking?.edit != null
        if (p.detourLabel != null) TagoButton(p.detourLabel, actions.onDetour, Modifier.fillMaxWidth(), ButtonVariant.PRIMARY, icon = R.drawable.ic_navigation)
        if (p.showButtons) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
            TagoButton(p.primaryLabel, actions.onRoadPrimary, Modifier.weight(1.4f), if (blackTaken) ButtonVariant.SECONDARY else ButtonVariant.PRIMARY, icon = p.primaryIcon)
            TagoButton(p.endLabel, actions.onEndRoad, Modifier.weight(1f), ButtonVariant.SECONDARY)
        }
        if (p.chargeLabel != null) TagoButton(p.chargeLabel, actions.onChargeOpen, Modifier.fillMaxWidth(), ButtonVariant.SECONDARY, icon = R.drawable.ic_flag, alignStart = true)
        if (p.wrongLabel != null) TextLink(p.wrongLabel, actions.onAlertWrong)   // 작은 글자 줄(정차 중, 기록만)
        if (p.summary != null) InfoCard(p.summary)
    }
}

/** 충전기 고장 신고 1분 설문: 한 화면에 한 문항. 그림 타일(3D 렌더 그림, GPT Image 2.6 — 사용자: '3d 처럼', '현대 자동차 스타일로')을 누르면 다음 문항으로 — 사용자: "그림으로 표기", "한번에 다 띄우려고 하지마".
 *  타일은 순정 설정 문법(흰 면·회색 선·직각), 그림은 색이 있어 고른 타일은 검정 굵은 테(3dp)로 표시. 기기 번호는 그림 없이 숫자만 크게(고르면 검정 면) */
@Composable
internal fun ColumnScope.ChargeReportContent(u: ChargeReportUi, actions: TagoActions) {
    val c = Tago.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
        Text(u.progress, style = TagoType.bodyM, color = c.text2)
        Text(u.question, style = TagoType.titleS, color = c.text)
        u.tiles.chunked(u.columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
                row.forEach { tile -> SurveyTile(tile, tile.id == u.sel, Modifier.weight(1f)) { actions.onChargePick(tile.id) } }
                repeat(u.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(Dim.gapS)) {
            if (u.backLabel != null) TagoButton(u.backLabel, actions.onChargeBack, Modifier.weight(1f), ButtonVariant.SECONDARY)
            TagoButton(u.skipLabel, actions.onChargeSkip, Modifier.weight(1.4f), ButtonVariant.SECONDARY)
            TagoButton(u.closeLabel, actions.onChargeClose, Modifier.weight(1f), ButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun SurveyTile(t: SurveyTileUi, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Tago.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.then(if (t.icon != null) Modifier.padding(0.dp) else Modifier.height(96.dp)).border(if (on) 3.dp else Dim.line, if (on) c.ink else c.line)
            .background(if (on && t.icon == null) c.ink else c.surface)
            .clickable(interactionSource = interaction, indication = ripple(color = if (on && t.icon == null) c.rippleOnFill else c.ripple), onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (t.icon != null) {
            // 그림은 타일 폭의 86 %(실측: 112dp 고정은 자리·고장 대수 그림의 강조가 안 보였다)
            Image(painterResource(t.icon), null, Modifier.padding(top = 10.dp).fillMaxWidth(0.86f).aspectRatio(1f))
            Spacer(Modifier.height(4.dp))
            Text(t.label, style = TagoType.bodyM, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
        } else Text(t.label, style = TagoType.titleS, color = if (on) c.onInk else c.text)
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
