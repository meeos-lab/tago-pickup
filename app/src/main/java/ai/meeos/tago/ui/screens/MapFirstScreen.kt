package ai.meeos.tago.ui.screens

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.R
import ai.meeos.tago.ui.DestUi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import ai.meeos.tago.ui.AlertTone
import ai.meeos.tago.ui.AlertUi
import ai.meeos.tago.ui.BarUi
import ai.meeos.tago.ui.CardUi
import ai.meeos.tago.ui.MarkerKind
import ai.meeos.tago.ui.Panel
import ai.meeos.tago.ui.ParkingUi
import ai.meeos.tago.ui.StripMarker
import ai.meeos.tago.ui.TagoUiState
import ai.meeos.tago.ui.VoiceUi
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import ai.meeos.tago.ui.components.AroundView
import ai.meeos.tago.ui.components.AuroraEdge
import ai.meeos.tago.ui.components.DevPanel
import ai.meeos.tago.ui.components.TagoToast
import ai.meeos.tago.ui.components.debounced
import ai.meeos.tago.ui.theme.MapLook as L
import ai.meeos.tago.ui.theme.LocalTagoColors
import ai.meeos.tago.ui.theme.TagoType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalSoundEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/*
 * 지도 우선 화면(차대차 판) — 사용자 확정 시안 A4(11-13 "앱화면 시안느낌으로 구현해봐"; 그림 reports/app_design_20261113/A4_*.png).
 * 겉모습 값은 전부 ui/theme/MapLook.kt 한 곳(크기 비율 SCALE 하나). 이 파일은 배치와 상태별로 무엇을 그릴지만 정한다.
 * 지도가 앱 영역 전체, 그 위 왼쪽 위에 어두운 유리 판 하나, 판 바로 아래 흰 알약 버튼 하나, 오른쪽 아래 응급 유리 알약.
 * 달리는 중(state.driving): ① 평소 = 한 줄 알약 ② 위험 = 표지·거리·한 줄·진행 선 + [우회 안내] ③ 긴급차량 = 붉은 테두리 판 + 화면 아래 붉은 빛, 버튼 없음.
 * 섰을 때만 판이 아래로 펼쳐져 목록·알약 버튼 둘·"방금 경고가 틀렸어요". 가족 차·정체·다른 위험은 주행 중 지도 표지로만.
 * 지도 위 위험 지점의 번짐 고리는 지도 페이지(link.html ringPaint, 고정 원 세 겹)가 그린다. 기능·판정·조작 제한은 컨트롤러 그대로 — 그리는 층만.
 * 픽업 기능이 없는 판(BuildConfig.PICKUP = false)만 쓴다.
 */

private enum class Glyph { WARN, SIREN, OK, SOS, SLOW }

private fun toneColor(t: AlertTone?): Color = when (t) { AlertTone.DANGER, AlertTone.SIREN -> L.red; AlertTone.CAUTION -> L.amber; null -> L.green }

/** 지도 우선 화면 전체. 지도는 MainActivity 의 WebView(map 슬롯) — 지도 페이지는 왼쪽 판 폭만큼 비켜 맞춘다(link.html padl) */
@Composable
internal fun MapFirstScreen(state: TagoUiState, actions: TagoActions, map: @Composable (Modifier) -> Unit) {
    val c = LocalTagoColors.current
    BoxWithConstraints(Modifier.fillMaxSize().background(c.mapBg)) {
        val fullH = maxHeight
        // 주행 중 왼쪽 주변 차 판(11-14, 순정 주행 화면 문법): 있으면 지도·판·알약은 그 오른쪽에서 시작한다. 좁은 창(분할 화면)에서는 판을 두지 않는다
        val around = state.around?.takeIf { maxWidth >= 900.dp }
        val aroundW = if (around != null) minOf(L.aroundW, maxWidth * L.aroundMaxFrac) else 0.dp
        val fullW = maxWidth - aroundW   // 아래 계산은 모두 지도 쪽 폭 기준
        map(Modifier.fillMaxSize().padding(start = aroundW))
        if (state.mapUrl == null) Box(Modifier.fillMaxSize().padding(start = aroundW).background(c.mapBg), contentAlignment = Alignment.Center) {
            Text(state.mapEmptyText, style = TagoType.bodyM, color = c.text2, modifier = Modifier.padding(horizontal = 48.dp))
        }
        val p = state.panel
        val road = p as? Panel.RoadLink
        val idle = p as? Panel.Idle
        val alert = state.alert
        // 긴급차량: 뒤에서 온다는 뜻으로 화면 아래 가장자리 오로라 빛(11-13 사용자 "오로라 느낌으로", 시안 A5). 판·버튼보다 아래 층.
        // 구급차가 2 km → 300 m 로 가까워질수록 진하고 높아지고(1.5초에 걸쳐), 안내가 끝나면 2초에 걸쳐 사라진다
        val sirenOn = alert?.tone == AlertTone.SIREN
        val lastLevel = remember { floatArrayOf(0f) }
        alert?.distM?.takeIf { sirenOn }?.let { d -> lastLevel[0] = ((L.auroraFarM - d) / (L.auroraFarM - L.auroraNearM)).coerceIn(0f, 1f) }
        val level by animateFloatAsState(lastLevel[0], tween(1500), label = "aurora-level")
        val shown by animateFloatAsState(if (sirenOn) 1f else 0f, tween(if (sirenOn) 800 else 2000), label = "aurora-shown")
        if (shown > 0.01f) AuroraEdge(level, shown, isSystemInDarkTheme(), Modifier.align(Alignment.BottomStart).padding(start = aroundW).fillMaxWidth().height(L.auroraH))
        // 음성이 듣는 동안(말로 검색·질문의 답): 더 낮고 옅고 느린 파랑·보라·분홍 빛(11-14 사용자 결정). 긴급차량 빛이 있으면 그쪽만
        val listenOn = state.voice == VoiceUi.LISTENING && !sirenOn
        val listenShown by animateFloatAsState(if (listenOn) 1f else 0f, tween(if (listenOn) 600 else 1200), label = "aurora-listen")
        if (listenShown > 0.01f) AuroraEdge(0f, listenShown, isSystemInDarkTheme(), Modifier.align(Alignment.BottomStart).padding(start = aroundW).fillMaxWidth().height(L.auroraListenH),
            blobs = L.auroraListenBlobs, ray = L.auroraListenRay)

        // 설정 판(11-14): 첫 화면 판의 톱니로 화면 안에서 연다. 정차(P)에서만 — 주행 중·기어 D 가 되면 저절로 닫힌다
        var settingsOpen by remember { mutableStateOf(false) }
        val settings = idle?.settings
        LaunchedEffect(state.driving, settings?.locked, settings == null) { if (state.driving || settings == null || settings.locked) settingsOpen = false }
        val settingsShown = settingsOpen && settings != null && !settings.locked
        BackHandler(enabled = settingsShown) { settingsOpen = false }   // 뒤로 = 설정만 닫는다(앱 전체의 뒤로는 앱을 뒤로 보낸다)

        // 판 바로 아래 흰 알약(하나): 우회 안내 > (섰고 주차 기록이 있는 첫 화면) 고치기 > 첫 화면의 시작 버튼.
        // 주차 판일 때만 시작 버튼을 옆에 유리 알약(보조)으로 둔다 — 흰 채움은 여전히 하나
        val parkIdle = idle?.parking?.takeIf { !state.driving && it.card != null }
        // 주변 차 연결 / 일행 나누기(11-14): 차대차 판 첫 화면에는 흰 알약이 없다 — 주 동작은 "주행하면 자동 연결"(판 안 한 줄)이고,
        // 판 아래에는 유리 알약 [지금 연결](손으로 켜기)·[일행 만들기](btn_convoy, 정차 중에만)만 둔다. 첫 실행 동의 판이 떠 있으면 알약도 감춘다
        val linkIdle = idle?.link != null
        val consentShown = idle?.consent != null && !settingsShown
        val go: Pair<String, () -> Unit>? = when {
            alert?.tone == AlertTone.SIREN -> null   // 긴급차량 안내 중에는 버튼 없음(읽고 비키기만) — 우회 안내는 안내가 끝나면 다시 보인다
            consentShown -> null    // 동의 판을 연 동안은 아래 알약을 감춘다(설정은 오른쪽 위 모달이라 왼쪽 판은 그대로)
            state.homeMin && idle != null -> null    // 최소 표시 첫 화면: 판 아래 알약 없음(일행·주차·설정은 상태 점을 눌러 펼친 판 안에)
            road?.detourLabel != null -> road.detourLabel to actions.onDetour
            parkIdle != null && parkIdle.edit == null -> parkIdle.fixLabel to actions.onParkFix
            idle?.roadLinkLabel != null -> idle.roadLinkLabel to actions.onRoadLink
            else -> null
        }
        val linkNowLabel = idle?.roadLinkLabel
        val goWhite = !(linkIdle && linkNowLabel != null && go?.first == linkNowLabel)   // 차대차 판의 [지금 연결]은 유리 알약
        val convoyLabel = idle?.convoyLabel
        val goSecondary = mutableListOf<Pair<String, () -> Unit>>()
        if (go != null && idle != null) {
            if (parkIdle != null && parkIdle.edit == null && linkNowLabel != null) goSecondary.add(linkNowLabel to actions.onRoadLink)
            if (convoyLabel != null && !state.driving) goSecondary.add(convoyLabel to actions.onConvoy)
        }
        val goIcon = when { road?.detourLabel != null -> Glyph.WARN; parkIdle != null && parkIdle.edit == null -> null; else -> Glyph.OK }
        val wide = p is Panel.Study || p is Panel.Licenses || p is Panel.ChargeReport
        BackHandler(enabled = p is Panel.Licenses) { actions.onLicensesClose() }   // 고지 화면: 뒤로 = 닫기(동의 판 [자세히]에서 들어와도 나갈 수 있게, 11-15)
        val pillOnly = state.driving && road != null && alert == null && state.question == null
        val playerH = if (state.player != null) L.TOUCH - L.sosH + L.px(12) else 0.dp   // 시연 주행 재생 막대가 있으면 판이 그 위에서 끝나게
        val maxH = fullH - L.margin * 2 - (if (go != null) L.goH + L.gapBelowIsle else 0.dp) - L.sosH - playerH   // 판이 아래 버튼·응급 알약과 겹치지 않게
        val isleW = minOf(L.isleW, fullW - L.margin * 2)

        // 검색 판을 연 동안(11-15 [닫기] 뺌): 판 밖을 누르면 닫힌다, 뒤로도 닫힌다. 듣는 중이면 듣기도 끝난다(destClose)
        val destOpen = idle?.dest?.open == true && !state.driving
        BackHandler(enabled = destOpen && !settingsShown) { actions.onDestClose() }
        if (destOpen) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { actions.onDestClose() })
        Column(Modifier.align(Alignment.TopStart).padding(start = aroundW).padding(L.margin)) {
            when {
                wide -> WhiteCard(minOf(820.dp, fullW - L.margin * 2), fullH - L.margin * 2) { WideBody(state, actions) }
                p is Panel.Emergency -> Isle(isleW, maxH, scroll = true) { EmergencyBody(p, actions) }
                pillOnly -> DrivingPill(state, road, actions, fullW - L.margin * 2)
                road != null -> Isle(isleW, maxH, scroll = !state.driving, alertGlow = alert?.tone == AlertTone.SIREN, appearKey = alert?.let { it.tone to it.what }) {
                    RoadBody(state, road, actions)
                }
                idle != null && consentShown -> Isle(isleW, maxH, scroll = true) { ConsentBody(idle.consent!!, actions) }
                idle != null && state.homeMin -> MinimalHome(state, idle, actions, isleW, maxH)
                idle != null -> Isle(isleW, maxH, scroll = !state.driving) { IdleBody(state, idle, actions) }
                else -> StatusPill(state, actions, fullW - L.margin * 2)
            }
            if (go != null && !wide) {
                Spacer(Modifier.height(L.gapBelowIsle))
                Row(horizontalArrangement = Arrangement.spacedBy(L.px(12))) {
                    GoPill(go.first, goIcon, go.second, white = goWhite)
                    goSecondary.forEach { (label, onClick) -> GoPill(label, if (label == convoyLabel) null else Glyph.OK, onClick, white = false) }
                }
                if (state.goHint != null && !state.driving) Text(state.goHint, style = L.foot, color = L.t1,   // 누르기 전에 무엇을 하는지(시연 구간으로 이동)
                    modifier = Modifier.padding(top = L.px(8)).clip(RoundedCornerShape(L.px(14))).background(L.glass).padding(horizontal = L.px(14), vertical = L.px(8)))
            }
        }
        // 설정 단추(11-15, 사용자 "설정은 따로 우측에 버튼으로 빼자"): 판 안의 톱니·메뉴 줄에서 빼서 지도 오른쪽 위에 따로 둔다.
        // 첫 화면에서 달리지 않을 때만 보인다. 기어 D 로 서 있으면 흐리게(누르지 않는다). 설정 판·동의 판을 연 동안은 감춘다
        // 설정 모달(11-15 사용자 "설정 버튼누르면 해당위치 모달나오게 해줘" · "자연스러운 애니메이션도 넣어놓고"): 톱니 바로 아래에 붙어 뜨는 밝은 카드.
        // 열릴 때 톱니 쪽(오른쪽 위)에서 0.9 → 1 로 커지며 나타나고(살짝 튕기는 스프링), 닫힐 때 줄어들며 사라진다(0.17초). 카드 밖·톱니·[닫기]·뒤로 = 닫힘.
        // 카드는 첫 화면에 있는 동안 늘 그려 두고(닫힌 동안은 화면 밖), 열고 닫을 때는 그리기 단계 값만 바꾼다 — 처음 그리는 멈춤(에뮬레이터 0.3초) 때문에 움직임이 건너뛰지 않게
        val setA = animateFloatAsState(if (settingsShown) 1f else 0f, if (settingsShown) androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 420f) else tween(170), label = "settings")
        if (settingsShown) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { settingsOpen = false })   // 카드 밖 누르면 닫힘
        if (idle != null && settings != null && !state.driving) {
            val top = L.px(12) + L.TOUCH / 2 + L.px(30) + L.px(10)   // 톱니 원(지름 L.px(60)) 아래 끝에서 L.px(10)
            Column(Modifier.align(Alignment.TopEnd).offset { androidx.compose.ui.unit.IntOffset(if (setA.value < 0.01f) 100_000 else 0, 0) }   // 닫힌 동안 화면 밖(누르지 못함)
                .padding(end = L.px(12) + (L.TOUCH - L.px(60)) / 2, top = top)
                .width(minOf(L.px(470), fullW - L.margin * 2)).heightIn(max = fullH - top - L.margin)
                .graphicsLayer { val a = setA.value; alpha = a.coerceIn(0f, 1f); val k = 0.9f + 0.1f * a; scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.93f, 0f); translationY = (1f - a) * -12f }
                .lightPill(RoundedCornerShape(L.isleRadius)).verticalScroll(rememberScrollState())) {
                androidx.compose.runtime.CompositionLocalProvider(LocalLightCard provides true) { SettingsBody(settings, actions) { settingsOpen = false } }
            }
        }
        if (idle != null && settings != null && !state.driving && !consentShown && !wide)
            SettingsMini(settings.title, !settings.locked, { settingsOpen = !settingsOpen }, Modifier.align(Alignment.TopEnd).padding(end = L.px(12), top = L.px(12)))
        // 응급: 최소 표시에서는 작은 빨간 십자 하나(늘 보인다 — 두 번 눌러야 하는 응급은 두지 않는다), 그 밖에는 유리 알약
        if (state.emergencyLabel != null) {
            if (state.homeMin) SosMini(state.emergencyLabel, actions.onEmergency, Modifier.align(Alignment.BottomEnd).padding(end = L.px(12), bottom = L.px(12)))
            else SosPill(state.emergencyLabel, actions.onEmergency, Modifier.align(Alignment.BottomEnd).padding(end = L.margin, bottom = L.margin))
        }
        // 시연 주행 재생 막대(시연 판만, 11-14 사용자 "동영상 재생 처럼"): 화면 아래 왼쪽부터 — 응급 단추 자리는 비워 둔다
        val player = state.player
        if (player != null) {
            val sosW = if (state.emergencyLabel == null) 0.dp else if (state.homeMin) L.TOUCH + L.px(12) else L.px(190)
            PlayerBar(player, actions, Modifier.align(Alignment.BottomStart).padding(start = aroundW + L.margin, bottom = L.px(12)).width(minOf(L.px(900), fullW - L.margin * 2 - sosW)))
        }
        // 말로 검색 듣는 표시(11-15 사용자 "녹음 중이 지도 가운데 아래 에 있으면 좋겠어" → "말하고 있다 느낌으로" · "원형에 기하학적인 모양"):
        // 듣는 동안 지도 가운데 아래 — 흰 원 안 기하학 모양(말소리 크기로 커짐) + 그 아래 "듣고 있어요 0:03"
        if (idle?.dest?.listening == true) {
            val m = Modifier.align(Alignment.BottomCenter).padding(start = aroundW, bottom = L.margin + (if (player != null) L.TOUCH else 0.dp))
            if (ListenStyle.bar) ListenBar(m, onCancel = actions.onDestVoice) else ListenOrb(m)   // 듣는 중 onDestVoice = 멈춤
        }
        if (state.toast != null) TagoToast(state.toast, Modifier.align(Alignment.BottomCenter).padding(bottom = L.margin + L.sosH + 24.dp + (if (player != null) L.TOUCH else 0.dp)))
        // 주변 차 판: 화면 왼쪽 세로 전체(속도 · 도로 · 내 차 · 앱이 아는 차). 지도와의 경계에 가는 선
        if (around != null) Box(Modifier.align(Alignment.TopStart).width(aroundW).height(fullH)) {
            AroundView(around, isSystemInDarkTheme(), Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.CenterEnd).width(1.dp).height(fullH).background(L.aroundEdge))
        }
    }
}

/** 유리 면: 어두운 반투명 + 가는 밝은 테두리 + 부드러운 그림자(긴급차량은 붉은 테두리·붉은 빛 그림자) */
private fun Modifier.glass(shape: Shape, alertGlow: Boolean = false): Modifier =
    this.shadow(if (alertGlow) 26.dp else 18.dp, shape, ambientColor = if (alertGlow) L.red2 else L.shadow, spotColor = if (alertGlow) L.red2 else L.shadow)
        .clip(shape).background(L.glass).border(1.dp, if (alertGlow) L.alertBorder else L.hair, shape)

/** 밝은 카드 안이면 true(설정 모달, 11-15) — 설정 판·줄·스위치·꺾쇠가 진한 글·밝은 바탕 색을 쓴다 */
private val LocalLightCard = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 밝은 알약(첫 화면 위 줄, 11-15): 흰 바탕 + 가는 어두운 테두리 + 부드러운 그림자 */
private fun Modifier.lightPill(shape: Shape): Modifier =
    this.shadow(10.dp, shape, ambientColor = L.pillShadow, spotColor = L.pillShadow).clip(shape).background(L.pillBg).border(1.dp, L.pillHair, shape)

/** 누르는 자리: 보이는 크기는 작아도 닿는 높이는 96 이상. 물결 없이 소리·햅틱만(PressFeedback) — 보이지 않는 넓은 자리에 물결이 번지지 않게 */
@Composable
private fun Touch(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.heightIn(min = L.TOUCH).clickable(remember { MutableInteractionSource() }, indication = null, onClick = debounced(onClick)),
        contentAlignment = Alignment.CenterStart) { content() }
}

/** 떠 있는 유리 판(섬). 새 경고가 뜰 때 한 번만 부드럽게 나타난다(0.32초, 위에서 살짝 내려옴) — 그 밖의 움직임은 없다 */
@Composable
private fun Isle(width: Dp, maxH: Dp, scroll: Boolean = false, alertGlow: Boolean = false, appearKey: Any? = null, light: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(L.isleRadius)
    val a = remember(appearKey) { Animatable(if (appearKey == null) 1f else 0f) }
    LaunchedEffect(appearKey) { a.animateTo(1f, tween(320)) }
    Column(Modifier.width(width).heightIn(max = maxH).graphicsLayer { alpha = a.value; translationY = (1f - a.value) * -12f }.then(if (light) Modifier.lightPill(shape) else Modifier.glass(shape, alertGlow))
        .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier), content = content)
}

/** 정차 전용 넓은 판(연구 기록·오픈소스 고지·충전기 고장 신고)은 기존 내용을 그대로 쓰므로 밝은 면 판(앱 테마 색) */
@Composable
private fun WhiteCard(width: Dp, maxH: Dp, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalTagoColors.current
    val shape = RoundedCornerShape(L.isleRadius)
    Column(Modifier.width(width).heightIn(max = maxH).shadow(18.dp, shape, ambientColor = L.shadow, spotColor = L.shadow).clip(shape).background(c.surface)
        .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp), content = content)
}

/** 주행 중 끝내기 펼침이 저절로 접히기까지(아무것도 안 누를 때) */
private const val END_FOLD_MS = 5_000L

/**
 * 함께 가는 중 달릴 때의 알약 + 끝내기 펼침(11-13 사용자 "종료 버튼이 없네"). 알약을 누르면 바로 아래에 [끝내기] 하나가 펼쳐지고,
 * 5초 동안 아무것도 안 누르면 접힌다. 두 번 눌러야 끝나므로 흔들리는 차에서 잘못 눌러 끝나는 일이 없다.
 * 위험·긴급차량 안내가 뜨면 이 알약 대신 경고 판이 그려지므로(pillOnly 가 아님) 펼침도 함께 사라진다 — 안내가 우선이다.
 * 정차 중 판의 [다같이 휴게소]·[끝내기]는 그대로(RoadBody).
 */
@Composable
private fun DrivingPill(state: TagoUiState, p: Panel.RoadLink, actions: TagoActions, maxW: Dp) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) { delay(END_FOLD_MS); open = false } }
    Row(verticalAlignment = Alignment.Top) {
        StatusPill(state, actions, maxW, open = open, onTap = { open = !open })
        // 일행이 있으면 둘째 알약 "일행 3대" 알약 — 주변 차 연결(왼쪽 알약)과 따로 보인다(11-14 나누기). 누르는 것은 없다
        if (state.convoyPill != null) {
            Spacer(Modifier.width(L.px(10)))
            Row(Modifier.height(L.pillH).glass(RoundedCornerShape(L.pillH / 2)).padding(horizontal = L.px(18)), verticalAlignment = Alignment.CenterVertically) {
                HaloDot(L.green, L.px(9), L.px(5))
                Spacer(Modifier.width(L.px(10)))
                Text(state.convoyPill, style = L.pill, color = L.t1, maxLines = 1, softWrap = false)
            }
        }
    }
    // 낮은 단계 위험 한 줄(방향 구분 — 맞은편·방향이 애매한 위험): 알약 아래 작은 유리 줄, 붉은 판·음성 없음
    if (state.sideNote != null) Text(state.sideNote, style = L.foot, color = L.t1,
        modifier = Modifier.clip(RoundedCornerShape(L.px(14))).background(L.glass).padding(horizontal = L.px(14), vertical = L.px(8)))
    if (open) {
        Spacer(Modifier.height(L.gapBelowIsle))
        GoPill(p.endLabel, null, { open = false; actions.onEndRoad() }, white = false)
    }
}

/**
 * 주행 중 한 줄 알약: 점(번짐 테두리) + 앱 이름 + 지금 상태(보조색). 앱 이름을 2.5초 누르면 개발 패널(시연 빌드만).
 * onTap 이 있으면 오른쪽 끝에 작은 꺾쇠(접힘 ∨ / 펼침 ∧)를 두고, 닿는 높이를 96 으로 넓힌다(보이는 알약 자리는 그대로)
 */
@Composable
private fun StatusPill(state: TagoUiState, actions: TagoActions, maxW: Dp, open: Boolean = false, onTap: (() -> Unit)? = null) {
    val notice = state.notice
    val (text0, tone) = when {
        notice != null -> notice to AlertTone.DANGER
        state.status != null -> state.status.text to state.status.tone
        else -> state.headline to null
    }
    val text = text0.removePrefix(BuildConfig.APP_LABEL).trimStart()   // "연결됨 · 주변 차 13대" — 앱 이름은 알약 맨 앞에 한 번만
    val shape = RoundedCornerShape(L.pillH / 2)
    val ext = if (onTap != null) (L.TOUCH - L.pillH).coerceAtLeast(0.dp) / 2 else 0.dp
    Box(Modifier.offset(y = -ext).heightIn(min = L.pillH + ext * 2).then(pillPress(actions, onTap)), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.widthIn(max = maxW).heightIn(min = L.pillH).glass(shape)
            .padding(start = L.px(16), end = L.px(20), top = L.px(8), bottom = L.px(8)), verticalAlignment = Alignment.CenterVertically) {
            HaloDot(toneColor(tone), L.px(9), L.px(5))
            Spacer(Modifier.width(L.px(10)))
            Text(buildAnnotatedString {
                append(BuildConfig.APP_LABEL)
                withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = if (tone == AlertTone.DANGER) L.red else L.t2)) { append(" "); append(text) }
            }, style = L.pill, color = L.t1, modifier = Modifier.weight(1f, fill = false))
            if (onTap != null) {
                Spacer(Modifier.width(L.px(12)))
                Canvas(Modifier.size(L.px(16))) {   // 꺾쇠: 접힘이면 아래(∨), 펼침이면 위(∧)
                    val w = size.width; val h = size.height
                    val (y0, y1) = if (open) h * .66f to h * .34f else h * .34f to h * .66f
                    drawPath(Path().apply { moveTo(w * .18f, y0); lineTo(w * .5f, y1); lineTo(w * .82f, y0) }, L.t2,
                        style = Stroke(width = L.px(2.2f).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/**
 * 알약 누르기: 짧게 = onTap(누름 소리·햅틱·연타 막기는 다른 버튼과 같다), 2.5초 = 개발 패널(시연 빌드만).
 * 한 pointerInput 에서 둘 다 받는다 — clickable 을 따로 겹치면 먼저 받은 쪽이 누름을 가져가 길게 누르기가 안 된다
 */
@Composable
private fun pillPress(actions: TagoActions, onTap: (() -> Unit)?): Modifier {
    if (onTap == null) return devPress(actions)
    val sound = LocalSoundEffect.current
    val tap by rememberUpdatedState(debounced(onTap))
    return Modifier.pointerInput(Unit) {
        var long = false
        detectTapGestures(
            onPress = { long = false; if (BuildConfig.DEMO_HOOKS && withTimeoutOrNull(2500) { tryAwaitRelease() } == null) { long = true; actions.onToggleDev() } },
            onTap = { if (!long) { sound.playClickSound(); tap() } })
    }
}

/** 점 + 옅은 번짐 테두리(정적) */
@Composable
private fun HaloDot(color: Color, d: Dp, halo: Dp) {
    Canvas(Modifier.size(d + halo * 2)) {
        drawCircle(color.copy(alpha = .18f), radius = size.minDimension / 2)
        drawCircle(color, radius = d.toPx() / 2)
    }
}

/** 앱 이름 길게 누르기 → 개발 패널(시연·디버그 빌드만, StatusHeader 와 같은 2.5초) */
@Composable
private fun devPress(actions: TagoActions): Modifier = if (!BuildConfig.DEMO_HOOKS) Modifier else Modifier.pointerInput(Unit) {
    detectTapGestures(onPress = { if (withTimeoutOrNull(2500) { tryAwaitRelease() } == null) actions.onToggleDev() })
}

/** 판 맨 위 줄: 둥근 표지(지름 58, 옅은 번짐) + 큰 글(거리) + 아래 한 줄(굵은 글 · 보조색 글) */
@Composable
private fun TopRow(glyph: Glyph, tone: AlertTone?, big: @Composable () -> Unit, what: String?, em: String?, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = L.px(18), end = L.px(22), top = L.px(18), bottom = L.px(16)).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        SignCircle(glyph, tone)
        Spacer(Modifier.width(L.px(16)))
        Column(Modifier.weight(1f)) {
            big()
            if (!what.isNullOrEmpty()) FitLine(what, em, Modifier.padding(top = L.px(7)))
            if (!sub.isNullOrEmpty()) Text(sub, style = L.row, color = L.t2, modifier = Modifier.padding(top = L.px(4)))
        }
    }
}

/** 굵은 글 + " · 보조" 한 줄. 넘치면 보조를 " · " 단위로 뒤부터 덜어 낸다(말줄임표 없음). 굵은 글만으로 넘치면 줄바꿈 */
@Composable
private fun FitLine(what: String, em: String?, modifier: Modifier = Modifier, style: TextStyle = L.what) {
    val tm = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val avail = constraints.maxWidth
        val parts = em?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val n = (parts.size downTo 0).firstOrNull { k ->
            k == 0 || tm.measure(what + " · " + parts.take(k).joinToString(" · "), style).size.width <= avail
        } ?: 0
        Text(buildAnnotatedString {
            append(what)
            if (n > 0) withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = L.t2)) { append(" · "); append(parts.take(n).joinToString(" · ")) }
        }, style = style, color = L.t1)
    }
}

/** 큰 거리: "300" + "m 앞"(보조색) */
@Composable
private fun BigDistance(a: AlertUi) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(a.num, style = L.big, color = L.t1, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(L.px(4)))
        Text(a.unit + (a.trail?.let { " $it" } ?: ""), style = L.bigUnit, color = L.t2, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
    }
}

/** 둥근 표지: 위에서 아래로 짙어지는 색 원 + 옅은 번짐(정적) + 흰 그림 */
@Composable
private fun SignCircle(glyph: Glyph, tone: AlertTone?) {
    val (hi, lo) = when (tone) { AlertTone.DANGER, AlertTone.SIREN -> L.redLight to L.red2; AlertTone.CAUTION -> L.amberLight to L.amber2; null -> L.greenLight to L.green2 }
    val halo = L.px(6)
    Canvas(Modifier.size(L.icon + halo * 2)) {
        val r = (size.minDimension / 2) - halo.toPx()
        drawCircle(lo.copy(alpha = .16f), radius = size.minDimension / 2)
        drawCircle(Brush.linearGradient(listOf(hi, lo), start = Offset(center.x - r, center.y - r), end = Offset(center.x + r, center.y + r)), radius = r)
        val g = r * 2 * 32f / 58f   // 그림 32 / 원 58
        drawGlyph(glyph, Offset(center.x - g / 2, center.y - g / 2), g)
    }
}

/** 흰 그림(24 격자): 경고 세모 · 경광등 · 확인 · 응급 십자 · 정체 */
private fun DrawScope.drawGlyph(glyph: Glyph, o: Offset, s: Float) {
    val k = s / 24f
    fun p(x: Float, y: Float) = Offset(o.x + x * k, o.y + y * k)
    val w = Color.White
    when (glyph) {
        Glyph.WARN, Glyph.SLOW -> {
            val tri = Path().apply { moveTo(p(12f, 3.5f).x, p(12f, 3.5f).y); lineTo(p(21.5f, 20f).x, p(21.5f, 20f).y); lineTo(p(2.5f, 20f).x, p(2.5f, 20f).y); close() }
            drawPath(tri, w, style = Stroke(width = 2.2f * k, join = StrokeJoin.Round))
            drawLine(w, p(12f, 10.3f), p(12f, 13.9f), 2f * k, StrokeCap.Round)
            drawCircle(w, 1.15f * k, p(12f, 17.2f))
        }
        Glyph.SIREN -> {
            val dome = Path().apply { moveTo(p(6.5f, 17f).x, p(6.5f, 17f).y); lineTo(p(6.5f, 12.5f).x, p(6.5f, 12.5f).y)
                cubicTo(p(6.5f, 5.2f).x, p(6.5f, 5.2f).y, p(17.5f, 5.2f).x, p(17.5f, 5.2f).y, p(17.5f, 12.5f).x, p(17.5f, 12.5f).y)
                lineTo(p(17.5f, 17f).x, p(17.5f, 17f).y); close() }
            drawPath(dome, w)
            drawRoundRect(w, topLeft = p(4.5f, 17.6f), size = Size(15f * k, 2.8f * k), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.4f * k))
            drawLine(w, p(12f, 2.2f), p(12f, 4.8f), 2f * k, StrokeCap.Round)
            drawLine(w, p(4.6f, 5.4f), p(6.5f, 7.3f), 2f * k, StrokeCap.Round)
            drawLine(w, p(19.4f, 5.4f), p(17.5f, 7.3f), 2f * k, StrokeCap.Round)
        }
        Glyph.OK -> {
            val ck = Path().apply { moveTo(p(5f, 12.5f).x, p(5f, 12.5f).y); lineTo(p(10f, 17.5f).x, p(10f, 17.5f).y); lineTo(p(19f, 7f).x, p(19f, 7f).y) }
            drawPath(ck, w, style = Stroke(width = 2.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Glyph.SOS -> {
            val cross = Path().apply { moveTo(p(9f, 3f).x, p(9f, 3f).y); lineTo(p(15f, 3f).x, p(15f, 3f).y); lineTo(p(15f, 9f).x, p(15f, 9f).y); lineTo(p(21f, 9f).x, p(21f, 9f).y)
                lineTo(p(21f, 15f).x, p(21f, 15f).y); lineTo(p(15f, 15f).x, p(15f, 15f).y); lineTo(p(15f, 21f).x, p(15f, 21f).y); lineTo(p(9f, 21f).x, p(9f, 21f).y)
                lineTo(p(9f, 15f).x, p(9f, 15f).y); lineTo(p(3f, 15f).x, p(3f, 15f).y); lineTo(p(3f, 9f).x, p(3f, 9f).y); lineTo(p(9f, 9f).x, p(9f, 9f).y); close() }
            drawPath(cross, w)
        }
    }
}

/** 내 차 → 위험 지점 가는 진행 선(높이 5): 바탕 .14, 채움은 옅은 색 → 짙은 색, 내 차 흰 점 13, 위험 점 흰 15 + 색 번짐 */
@Composable
private fun Track(b: BarUi, tone: AlertTone) {
    val col = toneColor(tone)
    Canvas(Modifier.fillMaxWidth().padding(start = L.px(22), end = L.px(22), top = L.px(2), bottom = L.px(14)).height(L.px(15))) {
        val y = size.height / 2
        val h = L.px(5).toPx()
        val x = size.width * b.frac
        drawRoundRect(L.track, topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRoundRect(Brush.horizontalGradient(listOf(col.copy(alpha = .25f), col), startX = 0f, endX = x), topLeft = Offset(0f, y - h / 2), size = Size(x, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawCircle(Color.White.copy(alpha = .9f), L.px(6.5f).toPx(), Offset(L.px(6.5f).toPx() - 1f, y))
        drawCircle(col.copy(alpha = .35f), L.px(7.5f).toPx() + L.px(4).toPx(), Offset(x, y))
        drawCircle(Color.White, L.px(7.5f).toPx(), Offset(x, y))
    }
}

/** 연결 중 판: 맨 위 줄(경고 또는 상태) → 주행 중이면 진행 선까지 / 섰으면 목록·알약 버튼·틀림 줄까지 */
@Composable
private fun ColumnScope.RoadBody(state: TagoUiState, p: Panel.RoadLink, actions: TagoActions) {
    val a = state.alert
    val q = state.question
    if (a != null) {
        val glyph = when (a.tone) { AlertTone.SIREN -> Glyph.SIREN; AlertTone.CAUTION -> Glyph.SLOW; else -> Glyph.WARN }
        TopRow(glyph, a.tone, { BigDistance(a) }, a.what ?: a.title, a.note)
        if (state.driving && a.bar != null && a.tone != AlertTone.SIREN) Track(a.bar, a.tone)
    } else {
        val tone = state.status?.tone ?: if (q != null) AlertTone.CAUTION else null
        TopRow(if (tone == null) Glyph.OK else Glyph.SLOW, tone, { Text(state.headline, style = L.head, color = L.t1) }, null, null, sub = state.subline)
    }
    if (state.cam != null) CamRow(state.cam)   // 단속 알림: 같은 판 안 한 줄(11-15 "겹침 하나로 합치고")
    if (q != null) {
        if (state.voiceText != null) Text(state.voiceText, style = L.row, color = L.t2, modifier = Modifier.padding(horizontal = L.px(22)))
        Chips(listOf(q.yes to { actions.onAnswer(true) }, q.no to { actions.onAnswer(false) }), firstWhite = p.detourLabel == null)
    }
    if (state.driving) return
    // ---- 여기부터 정차 중에만(판이 아래로 펼쳐진다)
    if (state.notice != null) Text(state.notice, style = L.row, color = L.red, modifier = Modifier.padding(horizontal = L.px(22), vertical = L.px(6)))
    if (p.connecting != null) Text(p.connecting, style = L.row, color = L.t2, modifier = Modifier.padding(horizontal = L.px(22), vertical = L.px(6)))
    // 일행이 있으면 정차 판에도 "일행 N대" 알약 한 줄(주행 중 둘째 알약과 같은 글)
    if (state.convoyPill != null) Row(Modifier.padding(horizontal = L.px(22), vertical = L.px(8)), verticalAlignment = Alignment.CenterVertically) {
        HaloDot(L.green, L.px(9), L.px(5))
        Spacer(Modifier.width(L.px(10)))
        Text(state.convoyPill, style = L.row.copy(fontWeight = FontWeight.SemiBold), color = L.t1)
    }
    val rows = p.strip?.markers.orEmpty().filter { it.kind == MarkerKind.HAZARD || it.kind == MarkerKind.MEMBER || (it.kind == MarkerKind.SIREN && a?.tone != AlertTone.SIREN) }
        .sortedByDescending { it.distM }
    if (rows.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair))
        Column(Modifier.padding(start = L.px(22), end = L.px(22), top = L.px(6), bottom = L.px(4))) {
            rows.forEachIndexed { i, m -> ListRow(m, last = i == rows.lastIndex) }
        }
    }
    if (p.parking != null) ParkingBlock(p.parking, actions)
    if (p.summary != null) SummaryBlock(p.summary)
    val chips = buildList {
        if (p.showButtons) { add(p.primaryLabel to actions.onRoadPrimary); add(p.endLabel to actions.onEndRoad) }
    }
    if (chips.isNotEmpty()) Chips(chips, firstWhite = false)
    if (p.chargeLabel != null) Chips(listOf(p.chargeLabel to actions.onChargeOpen), firstWhite = false)
    if (p.wrongLabel != null) Foot(p.wrongLabel, actions.onAlertWrong)
    else Spacer(Modifier.height(L.px(12)))
    if (state.devLog != null) Box(Modifier.padding(L.px(12))) { DevPanel(state.devStatus, state.devLog) }
}

/** 단속 알림 한 줄(11-15 사용자 "이것도 겹침 하나로 합치고 디자인 재구성"): 위 판과 가는 선으로 나누고, 왼쪽 흰 원 안 빨간 테 + 제한속도(티맵 표지 문법),
 *  큰 거리 글 + 작은 종류 글. 지도 쪽 단속 카드(#camalert)는 camui=app 으로 끈다 */
@Composable
private fun CamRow(c: ai.meeos.tago.ui.CamUi) {
    Box(Modifier.fillMaxWidth().padding(horizontal = L.px(22)).height(1.dp).background(L.hair))
    Row(Modifier.fillMaxWidth().padding(start = L.px(22), end = L.px(22), top = L.px(12), bottom = L.px(14)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(L.px(58)).clip(RoundedCornerShape(50)).background(Color.White).border(L.px(5), Color(0xFFE5342B), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center) {
            Text("${c.limit}", style = L.row.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", fontSize = L.row.fontSize * 0.86f, letterSpacing = (-0.5).sp), color = Color(0xFF111418), maxLines = 1)
        }
        Spacer(Modifier.width(L.px(16)))
        Text(c.dist, style = L.what.copy(fontFeatureSettings = "tnum"), color = L.t1, maxLines = 1)
        Spacer(Modifier.width(L.px(12)))
        Text(c.label, style = L.row, color = L.t2, maxLines = 1)
    }
}

/** 정차 중 목록 한 줄: 색 점 + 이름 · 보조 + 오른쪽 거리(보조색). 점: 위험 빨강 · 주의 주황 · 앞 가족 초록 · 뒤 가족 회색 */
@Composable
private fun ListRow(m: StripMarker, last: Boolean) {
    val dot = if (m.tone == null && m.distM < 0) L.gray else toneColor(m.tone)
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = L.px(13)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(L.px(9)).clip(RoundedCornerShape(50)).background(dot))
            Spacer(Modifier.width(L.px(12)))
            Text(m.label + (m.meta?.let { " · $it" } ?: ""), style = L.row, color = L.t1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(L.px(12)))
            Text(m.distLabel, style = L.row, color = L.t2)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(L.rowLine))
    }
}

/** 판 안 알약 버튼 줄(높이 54, 닿는 높이 96). firstWhite = 첫 버튼을 흰 면(질문의 "예")으로 */
@Composable
private fun Chips(items: List<Pair<String, () -> Unit>>, firstWhite: Boolean, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = L.px(22)), horizontalArrangement = Arrangement.spacedBy(L.px(10))) {
        items.forEachIndexed { i, (label, onClick) ->
            val white = firstWhite && i == 0
            val red = danger && i == 0
            Touch(onClick, Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(L.chipH).clip(RoundedCornerShape(L.chipH / 2))
                    .background(when { red -> L.red; white -> L.goBg; else -> L.chip }), contentAlignment = Alignment.Center) {
                    Text(label, style = L.chipText, color = if (white) L.goText else L.t1, maxLines = 2)
                }
            }
        }
    }
}

/** 판 맨 아래 작은 글자 줄(17, 옅은 흰색). 닿는 높이 96 */
@Composable
private fun Foot(label: String, onClick: () -> Unit) {
    Touch(onClick, Modifier.padding(horizontal = L.px(22))) { Text(label, style = L.foot, color = L.t3) }
}

/** 첫 화면(연결 전): 한 문장. 섰을 때만 주행 요약·주차 위치·작은 메뉴(연구 기록 · 오픈소스 고지 · 시연 한 바퀴) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.IdleBody(state: TagoUiState, p: Panel.Idle, actions: TagoActions) {
    val park = p.parking?.takeIf { !state.driving && it.card != null }
    // 설정은 지도 오른쪽 위 단추로 뺐다(11-15, SettingsMini) — 판 맨 위 줄에는 톱니를 두지 않는다
    val gear: (@Composable () -> Unit)? = null
    if (park != null) {
        // 차가 선 뒤 주차 기록이 있으면 첫 화면 판이 주차 판이 된다: P 표지 · 층·기둥(범위면 범위 그대로) · 근거 한 줄. [고치기]는 판 아래 흰 알약
        ParkTop(park.card!!, trailing = gear)
        if (park.edit != null) ParkEditChips(park, actions)
    } else {
        Row(Modifier.fillMaxWidth().padding(start = L.px(22), end = L.px(8), top = if (gear != null) 0.dp else L.px(20)), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).then(devPress(actions)), verticalAlignment = Alignment.CenterVertically) {
                HaloDot(if (state.notice != null) L.red else L.green, L.px(9), L.px(5))
                Spacer(Modifier.width(L.px(10)))
                Text(BuildConfig.APP_LABEL, style = L.pill, color = L.t2)
            }
            gear?.invoke()
        }
        Text(p.hero ?: state.headline, style = L.hero, color = L.t1, modifier = Modifier.padding(start = L.px(22), end = L.px(22), top = if (gear != null) 0.dp else L.px(10), bottom = L.px(20)))
    }
    if (state.notice != null) Text(state.notice, style = L.row, color = L.red, modifier = Modifier.padding(start = L.px(22), end = L.px(22), bottom = L.px(14)))
    // 주변 차 연결 한 줄(11-14 나누기): "주행하면 주변 차와 자동으로 연결됩니다" + 스위치. 주차 판일 때는 두지 않는다(설정에 같은 스위치가 있다)
    val link = p.link
    if (link != null && park == null) LinkLine(link, enabled = !state.driving, onToggle = { actions.onAutoLink(!link.on) })
    if (state.driving) { if (p.dest?.locked == true) DestBlock(p.dest, actions); return }   // 기어 D 로 서 있을 때: 흐린 검색 칸만(목적지 검색이 있음을 보이고, 누르진 않는다)
    if (p.dest != null) DestBlock(p.dest, actions)
    if (p.summary != null) SummaryBlock(p.summary)
    // 설정이 있으면 연구 기록·오픈소스 고지는 설정 안으로 옮기고(11-14), 맨 아래 작은 글자 줄에는 [시연 한 바퀴]만 남긴다(시연 영상 각본이 이 버튼을 쓴다)
    val links = if (p.settings != null) listOfNotNull(p.demoLabel?.let { it to actions.onDemoLap })
        else listOfNotNull(p.studyLabel?.let { it to actions.onStudyOpen }, p.licensesLabel?.let { it to actions.onLicensesOpen }, p.demoLabel?.let { it to actions.onDemoLap })
    if (links.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = L.px(22)), horizontalArrangement = Arrangement.spacedBy(L.px(24))) {
            links.forEach { (label, onClick) -> Touch(onClick) { Text(label, style = L.foot, color = L.t2) } }
        }
    }
    if (state.devLog != null) Box(Modifier.padding(L.px(12))) { DevPanel(state.devStatus, state.devLog) }
}

/**
 * 목적지 칸(11-14, 3단계 — 첫 화면 판 안, 정차 중에만): 고른 곳이 있으면 "목적지 · 이름 / 경로로 N km" + [목적지 검색][지우기],
 * 없으면 [목적지 검색] 한 줄, 열면 검색 칸(적기 + [검색]) + 결과(DestResults; 밝은 첫 화면은 MinimalHome 이 SearchEditPill + 아래 판으로 그린다)(실제 장소 검색 가운데 시연 경로 가까이·앞에 있는 곳만) + [닫기]. 주행 중엔 그리지 않는다
 */
@Composable
private fun DestBlock(d: DestUi, actions: TagoActions, light: Boolean = false) {
    // light: 최소 표시 첫 화면의 밝은 판(11-15 "밝은 테마로") — 흰 바탕 · 진한 글. 아니면 예전 어두운 유리 판 색
    val t1 = if (light) L.pillText else L.t1; val t2 = if (light) L.pillText2 else L.t2; val t3 = if (light) L.pillText2.copy(alpha = 0.42f) else L.t3
    val chip = if (light) L.pillChip else L.chip
    val goBg = if (light) L.pillText else L.goBg; val goText = if (light) L.pillBg else L.goText
    if (!light) Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair))
    Column(Modifier.fillMaxWidth().padding(start = L.px(22), end = L.px(22), top = L.px(6), bottom = L.px(6))) {
        when {
            d.open -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(L.chipH).clip(RoundedCornerShape(L.chipH / 2)).background(chip).padding(horizontal = L.px(18)),
                        contentAlignment = Alignment.CenterStart) {
                        if (d.listening) Text(d.listenText ?: "", style = L.row, color = t2, maxLines = 1, overflow = TextOverflow.Ellipsis)   // 듣는 동안: 들린 말(부분 인식) 또는 '갈 곳을 말해 주세요' — 듣는 표시는 지도 가운데 아래 구슬(ListenOrb)
                        else BasicTextField(d.query, actions.onDestQuery, singleLine = true, textStyle = L.row.copy(color = t1), cursorBrush = SolidColor(t1),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { actions.onDestSearch() }),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner -> if (d.query.isEmpty()) Text(d.hint, style = L.row, color = t3, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() })   // 마이크가 칸을 좁혀도 예시 글은 한 줄
                    }
                    if (d.mic != null) MicButton(d.mic, d.listening, enabled = !d.locked, onClick = actions.onDestVoice, h = L.chipH, light = light)
                    else Spacer(Modifier.width(L.px(10)))
                    Touch(actions.onDestSearch) {   // 듣는 동안은 흰 강조를 마이크에 넘긴다(한 화면에 흰 알약 하나)
                        Box(Modifier.height(L.chipH).clip(RoundedCornerShape(L.chipH / 2)).background(if (d.listening) chip else goBg).padding(horizontal = L.px(22)), contentAlignment = Alignment.Center) {
                            Text(if (d.busy) "…" else d.searchLabel, style = L.chipText, color = if (d.listening) t1 else goText)
                        }
                    }
                }
                DestResults(d, actions, light)
            }
            d.chosen != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = L.px(8))) {
                    Text(buildAnnotatedString { withStyle(SpanStyle(color = L.t2, fontWeight = FontWeight.Normal)) { append(d.title + " · ") }; append(d.chosen) },
                        style = L.row.copy(fontWeight = FontWeight.SemiBold), color = L.t1)
                    if (d.chosenSub != null) Text(d.chosenSub, style = L.foot, color = L.t2)
                }
                Touch(actions.onDestOpen) { Text(d.openLabel, style = L.foot, color = L.t2) }
                Spacer(Modifier.width(L.px(18)))
                Touch(actions.onDestClear) { Text(d.clearLabel, style = L.foot, color = L.t2) }
            }
            else -> DestSearchBar(d, actions)
        }
    }
}

/** 검색 판의 한 줄 알림 + 결과 목록(번호 원 + 이름·주소). 어두운 옛 판은 DestBlock 안, 밝은 첫 화면은 검색 알약 아래 판(11-15) */
@Composable
private fun DestResults(d: DestUi, actions: TagoActions, light: Boolean) {
    val t1 = if (light) L.pillText else L.t1; val t2 = if (light) L.pillText2 else L.t2; val t3 = if (light) L.pillText2.copy(alpha = 0.42f) else L.t3
    val chip = if (light) L.pillChip else L.chip; val line = if (light) L.pillHair else L.rowLine
    val goBg = if (light) L.pillText else L.goBg; val goText = if (light) L.pillBg else L.goText
    if (d.note != null) Text(d.note, style = L.foot, color = t2, modifier = Modifier.padding(top = L.px(4)))
    // 결과 목록(11-15 사용자 "목록마다 1, 2, 3, 4 인덱스" · "너무 길게 표기 안되게" · "스크롤바 생기는게 맞는듯"): 번호 원 + 이름 한 줄 + 주소 한 줄,
    // 목록 높이는 줄 다섯 개 남짓까지 — 넘으면 안에서 넘기고 오른쪽에 가는 스크롤바. [닫기]는 없다(판 밖을 누르거나 뒤로 = 닫힘)
    if (d.results.isNotEmpty()) {
        val ls = rememberScrollState()
        Column(Modifier.padding(top = L.px(6)).fillMaxWidth().heightIn(max = L.px(470))
            .drawWithContent {
                drawContent()
                if (ls.maxValue > 0) {   // 가는 스크롤바: 보이는 비율만큼의 길이, 넘긴 만큼 내려간다
                    val vis = size.height / (size.height + ls.maxValue); val h = size.height * vis; val y = (size.height - h) * ls.value / ls.maxValue
                    drawRoundRect(t2.copy(alpha = 0.45f), topLeft = Offset(size.width - L.px(5).toPx(), y), size = Size(L.px(5).toPx(), h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(L.px(3).toPx()))
                }
            }.verticalScroll(ls).padding(end = L.px(14))) {
            d.results.forEachIndexed { i, r ->
                Touch({ actions.onDestPick(i) }, Modifier.fillMaxWidth()) {   // 경로 밖 줄을 누르면 고르지 않고 까닭 한 줄(note)
                    Row(Modifier.fillMaxWidth().padding(vertical = L.px(6)), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(L.px(34)).clip(RoundedCornerShape(50)).background(if (r.onRoute) goBg else chip), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = L.chipText, color = if (r.onRoute) goText else t3)
                        }
                        Spacer(Modifier.width(L.px(14)))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = L.row.copy(fontWeight = FontWeight.SemiBold), color = if (r.onRoute) t1 else t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.sub, style = L.foot, color = if (r.onRoute) t2 else t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (i < d.results.lastIndex) Box(Modifier.fillMaxWidth().padding(start = L.px(48)).height(1.dp).background(line))
            }
        }
    }
}

/** 듣는 표시 모양 고르기(11-15): 알약(기본, 사용자 "알약 형태로 디자인 수정해봐") · 구슬. 시연 훅 listen_style(bar|orb)로 바꿔 볼 수 있다 */
internal object ListenStyle { var bar by mutableStateOf(true) }

/**
 * 듣는 표시 두 번째 안 — 알약(11-15 사용자 참고 그림 "정 안되면 이런식으로라도 하자 똑같이 하진말고"). 지도 가운데 아래.
 * 짙은 초록 알약: 왼쪽 초록 원 안 마이크(둘레 빛 고리가 지금 말소리 크기만큼 커진다) · 가운데 소리 막대(지금 소리 크기에 함께 반응하는 종 모양) · "듣고 있어요"/"말씀하세요" · 오른쪽 시계 원(마이크 원과 같은 크기, 남은 초 숫자 10 → 0 + 남은 만큼 가는 민트 테가 줄어들고 남은 3초부터 주황).
 * 크기는 실제 입력으로만(ListenLevel). 참고 그림과 다른 점: 마이크 둘레 고리, 남은 시간 테
 */
@Composable
private fun ListenBar(modifier: Modifier, onCancel: () -> Unit) {
    // 알약을 누르면 오른쪽 시계 원이 ✕ 원으로 돌며 바뀐다(11-15 "여기 안에 알약 누르면 취소버튼 애니메이션으로 나오게해줘" → "재디자인" → 시안 "A로":
    // 알약 폭 그대로). ✕ 를 누르면 듣기 멈춤, 다시 알약을 누르거나 3초가 지나면 시계 원으로 돌아온다(남은 초가 다시 보이게)
    var cancelShown by remember { mutableStateOf(false) }
    LaunchedEffect(cancelShown) { if (cancelShown) { kotlinx.coroutines.delay(3_000); cancelShown = false } }
    val xa by animateFloatAsState(if (cancelShown) 1f else 0f, tween(220), label = "cancel")
    val since by ai.meeos.tago.voice.ListenLevel.since.collectAsState()
    val hist = remember { mutableStateListOf<Float>().apply { repeat(8) { add(0f) } } }
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        var smooth = 0f; var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameMillis { ms ->
                now = android.os.SystemClock.elapsedRealtime()
                if (ms - last >= 80) {
                    last = ms
                    val v = ai.meeos.tago.voice.ListenLevel.level.value
                    smooth = if (v > smooth) smooth * 0.3f + v * 0.7f else smooth * 0.75f + v * 0.25f
                    hist.removeAt(0); hist.add(smooth)
                }
            }
        }
    }
    val elapsed = if (since == 0L) 0L else (now - since).coerceAtLeast(0L)
    val limitMs = 10_000L   // 말로 검색 듣기 제한(VoiceAssistant.ONCE_MS 10초와 같은 값)
    val leftMs = (limitMs - elapsed).coerceIn(0L, limitMs)
    val sec = ((leftMs + 999) / 1000).toInt()   // 남은 초(올림): 10 → 1 → 0
    val left = leftMs.toFloat() / limitMs   // 남은 비율 1 → 0
    val hearing = hist.takeLast(6).any { it > 0.18f }
    val lv = hist.last()
    val h = L.px(88)
    Row(modifier.height(h).shadow(18.dp, RoundedCornerShape(50), ambientColor = L.shadow, spotColor = L.shadow).clip(RoundedCornerShape(50))
        .background(Brush.horizontalGradient(listOf(Color(0xFF0B3A2C), Color(0xFF06211A))))
        .border(1.dp, L.gbMint.copy(alpha = 0.28f), RoundedCornerShape(50))
        .clickable(remember { MutableInteractionSource() }, indication = null) { cancelShown = !cancelShown }
        .padding(start = L.px(10), end = L.px(14)),
        verticalAlignment = Alignment.CenterVertically) {
        // 마이크 원 + 소리 고리
        Box(Modifier.size(h - L.px(14)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.36f
                drawCircle(L.gbMint.copy(alpha = 0.10f + 0.25f * lv), r * (1.12f + 0.26f * lv), c)
                drawCircle(Brush.linearGradient(listOf(Color(0xFF3CCB8F), Color(0xFF138A5E)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)), r, c)
                val w = r * 0.9f; val o = Offset(c.x - w / 2, c.y - w / 2); val st = w * 0.10f
                drawRoundRect(Color.White, topLeft = Offset(o.x + w * 0.35f, o.y + w * 0.04f), size = Size(w * 0.30f, w * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f))
                drawArc(Color.White, 0f, 180f, false, topLeft = Offset(o.x + w * 0.20f, o.y + w * 0.20f), size = Size(w * 0.60f, w * 0.50f), style = Stroke(st, cap = StrokeCap.Round))
                drawLine(Color.White, Offset(o.x + w * 0.5f, o.y + w * 0.70f), Offset(o.x + w * 0.5f, o.y + w * 0.90f), st, StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(L.px(16)))
        // 소리 막대 13개(11-15 "반전되는 느낌" 고침): 모두 지금 소리 크기에 함께 반응하고, 가운데가 가장 높은 종 모양.
        // 막대마다 조금씩 다른 흔들림(0.72~1.0배)을 곱해 살아 움직이게 — 소리가 없으면 흔들림도 0 이라 점 줄로 선다
        Canvas(Modifier.width(L.px(150)).height(h * 0.56f)) {
            val n = 13; val mid = n / 2; val gap = size.width / n; val bw = gap * 0.42f; val minH = size.height * 0.14f
            val t = now / 1000f
            for (i in 0 until n) {
                val k = kotlin.math.abs(i - mid)
                val env = 0.30f + 0.70f * kotlin.math.cos(k / (mid + 1f) * (Math.PI.toFloat() / 2f))   // 가운데 1 → 끝 0.3
                val wob = 0.72f + 0.28f * kotlin.math.sin(t * (6.5f + 1.1f * i) + i * 1.7f)
                val v = lv * env * wob
                val bh = minH + (size.height - minH) * v.coerceIn(0f, 1f)
                drawRoundRect(L.gbMint.copy(alpha = 0.55f + 0.45f * (1f - k / mid.toFloat())), topLeft = Offset(i * gap + (gap - bw) / 2, (size.height - bh) / 2),
                    size = Size(bw, bh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2))
            }
        }
        Spacer(Modifier.width(L.px(18)))
        Text(stringResource(if (hearing) R.string.dest_hearing else R.string.dest_speak_now), style = L.what, color = Color.White, maxLines = 1)
        Spacer(Modifier.width(L.px(18)))
        // 시계 원: 남은 초(10 → 0, 11-15 "카운터가 반대로 되야하지않나") + 남은 만큼 테(가득 → 빈).
        // 11-15 "우측 카운터 원형이랑 색상만 더 손보면 될듯" → "너무 과하지 않냐": 왼쪽 마이크 원 크기에 맞춘 가는 민트 한 색 테만
        // (끝 점·빛·안쪽 원판·그러데이션 없음). 남은 3초부터 테·숫자가 주황(앱의 두 번째 색)으로 바뀌어 곧 끝남을 알린다
        val warn = leftMs in 1..3_000
        // 시계 원 ↔ ✕ 원(시안 A): xa 0 = 시계 원(테 + 남은 초), 1 = 옅은 흰 원 + ✕(90° 돌며 나타남). ✕ 일 때만 이 원을 누르면 멈춤
        Box(Modifier.size(h - L.px(14)).semantics { if (cancelShown) contentDescription = "cancel" }
            .clickable(enabled = cancelShown, interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.36f; val st = size.minDimension * 0.045f
                val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
                val ra = 1f - xa
                drawArc(Color.White.copy(alpha = 0.14f + 0.21f * xa), 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(st))
                if (ra > 0.01f) drawArc((if (warn) L.gbAmber else L.gbMint).copy(alpha = ra), -90f, 360f * left, false, topLeft = tl, size = sz, style = Stroke(st, cap = StrokeCap.Round))
                if (xa > 0.01f) {
                    drawCircle(Color.White.copy(alpha = 0.12f * xa), r - st / 2, c)
                    rotate(90f * (1f - xa), c) {
                        val k = r * 0.24f * (0.6f + 0.4f * xa); val w = st * 1.15f; val col = Color.White.copy(alpha = xa)   // ✕ 크기: 반지름의 0.24(11-15 "x 표시는 너무 크지않나" — 전 0.38·굵기 1.5)
                        drawLine(col, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), w, StrokeCap.Round)
                        drawLine(col, Offset(c.x + k, c.y - k), Offset(c.x - k, c.y + k), w, StrokeCap.Round)
                    }
                }
            }
            if (xa < 0.99f) Text("$sec", style = L.what.copy(fontFeatureSettings = "tnum"), color = (if (warn) L.gbAmber else Color.White).copy(alpha = 1f - xa), maxLines = 1,
                modifier = Modifier.graphicsLayer { scaleX = 1f - 0.4f * xa; scaleY = 1f - 0.4f * xa })
        }
    }
}

/** 3D 점 */
private class P3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Float) = P3(x * k, y * k, z * k)
}

/** 정이십면체: 꼭짓점 12개(단위 구 위)와 면 20개(바깥을 보는 순서) — 처음 한 번 계산 */
private val ICO_V: List<P3> = run {
    val f = 1.618034f; val raw = listOf(
        P3(-1f, f, 0f), P3(1f, f, 0f), P3(-1f, -f, 0f), P3(1f, -f, 0f), P3(0f, -1f, f), P3(0f, 1f, f),
        P3(0f, -1f, -f), P3(0f, 1f, -f), P3(f, 0f, -1f), P3(f, 0f, 1f), P3(-f, 0f, -1f), P3(-f, 0f, 1f))
    raw.map { val l = kotlin.math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z); P3(it.x / l, it.y / l, it.z / l) }
}
private val ICO_F: List<IntArray> = run {
    fun d2(a: P3, b: P3) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)
    val e = ICO_V.indices.flatMap { i -> ICO_V.indices.filter { it != i }.map { d2(ICO_V[i], ICO_V[it]) } }.min() * 1.01f
    val out = mutableListOf<IntArray>()
    for (a in 0 until 12) for (b in a + 1 until 12) for (c in b + 1 until 12)
        if (d2(ICO_V[a], ICO_V[b]) <= e && d2(ICO_V[b], ICO_V[c]) <= e && d2(ICO_V[a], ICO_V[c]) <= e) {
            val A = ICO_V[a]; val B = ICO_V[b]; val C = ICO_V[c]
            val n = P3((B.y - A.y) * (C.z - A.z) - (B.z - A.z) * (C.y - A.y), (B.z - A.z) * (C.x - A.x) - (B.x - A.x) * (C.z - A.z), (B.x - A.x) * (C.y - A.y) - (B.y - A.y) * (C.x - A.x))
            out.add(if (n.x * (A.x + B.x + C.x) + n.y * (A.y + B.y + C.y) + n.z * (A.z + B.z + C.z) >= 0) intArrayOf(a, b, c) else intArrayOf(a, c, b))
        }
    out
}

/**
 * 말로 검색 듣는 표시 — 원 안의 입체 기하 도형(11-15 사용자 "원형에 기하학적인 모양 넣어서" · "미래적인 디자인" · "3d 그래픽적으로" ·
 * "아이덴티티가없음" · "기하학적인 모양이랬지"). 지도 가운데 아래.
 * 짙은 초록 유리 구슬(GPU 셰이더, API 33 아래는 캔버스) 안에서 정이십면체가 3D 로 돈다: 면마다 빛 방향에 따라 짙은 초록 ~ 민트로 음영(앞면만, 깊이 순서),
 * 모서리는 민트 빛. 가운데 주황 빛(앱 아이콘의 링크 빛 띠 색)이 있다. 말소리 크기(ListenLevel — 마이크는 STT 값, 시연 녹음은 그 PCM)만큼
 * 면들이 바깥으로 갈라져 틈으로 주황 빛이 새어 나오고 모서리가 주황으로 물들며 조금 빨리 돈다. 조용하면 닫힌 도형이 천천히 돈다.
 * 구슬 아래 알약: "듣고 있어요"/"말씀하세요" + 지난 시간(주황)
 */
@Composable
private fun ListenOrb(modifier: Modifier) {
    val since by ai.meeos.tago.voice.ListenLevel.since.collectAsState()
    val hist = remember { mutableStateListOf<Float>().apply { repeat(8) { add(0f) } } }   // 최근 0.64초(0.08초 간격), 맨 뒤가 지금
    var frameT by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {   // 소리 크기는 0.08초마다, 그림은 화면 갱신마다
        var smooth = 0f; var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameMillis { ms ->
                frameT = ms
                if (ms - last >= 80) {
                    last = ms
                    val v = ai.meeos.tago.voice.ListenLevel.level.value
                    smooth = if (v > smooth) smooth * 0.3f + v * 0.7f else smooth * 0.8f + v * 0.2f
                    hist.removeAt(0); hist.add(smooth)
                }
            }
        }
    }
    val now = android.os.SystemClock.elapsedRealtime()
    val sec = if (since == 0L) 0 else ((now - since) / 1000).toInt().coerceAtLeast(0)
    val hearing = hist.takeLast(6).any { it > 0.18f }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(L.px(210))) {
            // 유리 구슬 + 정이십면체 크리스털(GPU 레이마칭, OrbShader.kt). API 33 아래·셰이더 실패면 캔버스 구슬 + 캔버스 정이십면체
            OrbShader({ hist.last() }, Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2); val R0 = size.minDimension * 0.36f
                    drawCircle(Brush.radialGradient(listOf(L.gbGlass, L.gbDeep, Color(0xFF001A12)), Offset(c.x - R0 * 0.35f, c.y - R0 * 0.4f), R0 * 1.55f), R0, c)
                    drawCircle(L.gbMint, R0, c, alpha = 0.45f, style = Stroke(size.minDimension * 0.006f))
                }
            Canvas(Modifier.fillMaxSize()) {   // 캔버스 정이십면체: GPU 셰이더를 못 쓸 때만(API 33 아래·컴파일 실패)
                val c = Offset(size.width / 2, size.height / 2); val R0 = size.minDimension * 0.36f
                val t = frameT / 1000f; val lv = hist.last()
                val rr = R0 * 0.64f * (1f + 0.05f * lv)                     // 도형 크기(구슬 반지름의 0.64)
                val ex = 0.36f * lv                                          // 말소리만큼 면이 바깥으로(반지름 비율)
                val shrink = 0.93f - 0.05f * lv                              // 면을 제 가운데로 조금 줄여 늘 얇은 틈(크리스털)
                val yaw = t * (0.5f + 0.7f * lv); val pitch = 0.5f + 0.25f * kotlin.math.sin(t * 0.37f)
                val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw); val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
                fun rot(p: P3): P3 { val x1 = p.x * cy + p.z * sy; val z1 = -p.x * sy + p.z * cy; return P3(x1, p.y * cp - z1 * sp, p.y * sp + z1 * cp) }
                fun rotInv(p: P3): P3 { val x1 = p.x * cy - p.z * sy; val z1 = p.x * sy + p.z * cy; return P3(x1, p.y * sp + z1 * cp, -p.y * cp + z1 * sp) }   // 안쪽 도형: 반대로
                fun proj(p: P3, r: Float = rr): Offset { val k = 3.4f / (3.4f - p.z); return Offset(c.x + p.x * r * k, c.y + p.y * r * k) }
                val light = P3(-0.45f, -0.6f, 0.66f); val half = P3(-0.25f, -0.33f, 0.91f)   // 빛 방향 · 반사광 방향(빛과 눈 사이)
                val edgeCol = androidx.compose.ui.graphics.lerp(L.gbMint, L.gbAmber, (lv * 1.3f).coerceAtMost(1f))
                val join = androidx.compose.ui.graphics.StrokeJoin.Round
                // 가운데 뜨거운 빛: 흰 심 + 주황 번짐(늘 조금, 말하면 크고 밝게)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4DC).copy(alpha = 0.55f + 0.45f * lv), L.gbAmber.copy(alpha = 0.6f + 0.4f * lv), L.gbAmber.copy(alpha = 0f)),
                    c, rr * (0.75f + 0.85f * lv)), rr * (0.75f + 0.85f * lv), c)
                // 안쪽 정팔면체(주황 선, 반대로 돈다) — 면 틈으로 보인다
                val oct = listOf(P3(1f, 0f, 0f), P3(-1f, 0f, 0f), P3(0f, 1f, 0f), P3(0f, -1f, 0f), P3(0f, 0f, 1f), P3(0f, 0f, -1f)).map { rotInv(it) }
                val octR = rr * (0.42f + 0.12f * lv)
                for (i in 0 until 6) for (j in i + 1 until 6) {
                    if ((i / 2) == (j / 2)) continue   // 마주 보는 꼭짓점끼리는 잇지 않는다
                    val pa = oct[i]; val pb = oct[j]
                    drawLine(L.gbAmber.copy(alpha = 0.35f + 0.25f * ((pa.z + pb.z) / 4f + 0.5f) + 0.3f * lv), proj(pa, octR), proj(pb, octR), size.minDimension * 0.005f, StrokeCap.Round)
                }
                class Face(val pts: List<Offset>, val z: Float, val shade: Float, val spec: Float, val facing: Float)
                val faces = ICO_F.map { f ->
                    val a0 = rot(ICO_V[f[0]]); val b0 = rot(ICO_V[f[1]]); val d0 = rot(ICO_V[f[2]])
                    val n0 = P3((b0.y - a0.y) * (d0.z - a0.z) - (b0.z - a0.z) * (d0.y - a0.y), (b0.z - a0.z) * (d0.x - a0.x) - (b0.x - a0.x) * (d0.z - a0.z), (b0.x - a0.x) * (d0.y - a0.y) - (b0.y - a0.y) * (d0.x - a0.x))
                    val nl0 = kotlin.math.sqrt(n0.x * n0.x + n0.y * n0.y + n0.z * n0.z); val n = P3(n0.x / nl0, n0.y / nl0, n0.z / nl0)
                    val cen = P3((a0.x + b0.x + d0.x) / 3f, (a0.y + b0.y + d0.y) / 3f, (a0.z + b0.z + d0.z) / 3f)
                    val off = n * ex
                    val q = listOf(a0, b0, d0).map { v -> P3(cen.x + (v.x - cen.x) * shrink, cen.y + (v.y - cen.y) * shrink, cen.z + (v.z - cen.z) * shrink) + off }
                    val hs = (n.x * half.x + n.y * half.y + n.z * half.z).coerceAtLeast(0f)
                    Face(q.map { proj(it) }, (q[0].z + q[1].z + q[2].z) / 3f, (n.x * light.x + n.y * light.y + n.z * light.z).coerceIn(0f, 1f),
                        hs * hs * hs * hs * hs * hs * hs * hs * hs * hs * hs * hs * hs * hs * hs * hs, n.z)
                }
                faces.sortedBy { it.z }.forEach { fc ->
                    val path = Path().apply { moveTo(fc.pts[0].x, fc.pts[0].y); lineTo(fc.pts[1].x, fc.pts[1].y); lineTo(fc.pts[2].x, fc.pts[2].y); close() }
                    if (fc.facing < 0f) {   // 뒷면: 옅은 유리 면 + 옅은 테(부피감)
                        drawPath(path, L.gbMint.copy(alpha = 0.06f))
                        drawPath(path, edgeCol.copy(alpha = 0.16f), style = Stroke(size.minDimension * 0.004f, join = join))
                    } else {
                        // 앞면: 면 위쪽(빛 쪽)이 밝은 그라데이션 + 반사광 + 모서리 빛(넓고 옅게 · 가늘고 밝게)
                        val top = fc.pts.minBy { it.y }; val bot = fc.pts.maxBy { it.y }
                        val hi = androidx.compose.ui.graphics.lerp(Color(0xFF0E5843), L.gbMint, 0.25f + 0.75f * fc.shade)
                        val lo = androidx.compose.ui.graphics.lerp(Color(0xFF02281E), hi, 0.45f)
                        val a = 0.80f + 0.18f * fc.facing
                        drawPath(path, Brush.linearGradient(listOf(hi.copy(alpha = a), lo.copy(alpha = a)), top, bot))
                        if (fc.spec > 0.02f) drawPath(path, Color.White.copy(alpha = (0.75f * fc.spec).coerceAtMost(0.75f)))
                        drawPath(path, edgeCol.copy(alpha = 0.18f + 0.2f * lv), style = Stroke(size.minDimension * 0.016f, join = join))
                        drawPath(path, edgeCol.copy(alpha = 0.6f + 0.35f * fc.facing), style = Stroke(size.minDimension * 0.0055f, join = join))
                    }
                }
                // 유리 반사광(왼쪽 위) — 도형 위에 얹어 구슬 안에 든 것처럼
                drawOval(Brush.radialGradient(listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)), Offset(c.x - R0 * 0.40f, c.y - R0 * 0.52f), R0 * 0.5f),
                    topLeft = Offset(c.x - R0 * 0.80f, c.y - R0 * 0.78f), size = Size(R0 * 0.78f, R0 * 0.5f))
            }
            }
        }
        Row(Modifier.height(L.px(52)).clip(RoundedCornerShape(50)).background(L.gbDeep.copy(alpha = 0.94f)).border(1.dp, L.gbMint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = L.px(22)), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (hearing) R.string.dest_hearing else R.string.dest_speak_now), style = L.row.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
            Spacer(Modifier.width(L.px(12)))
            Text("%d:%02d".format(sec / 60, sec % 60), style = L.what.copy(fontFeatureSettings = "tnum"), color = L.gbAmber, maxLines = 1)
        }
    }
}

/**
 * 목적지 검색 칸(11-14 사용자 "목적지 검색도 만들어주고" — 판 안 작은 글 '목적지 검색'은 버튼으로 안 보였다): 내비처럼 돋보기 + "어디로 갈까요?" 한 줄 알약,
 * 닿는 높이 96. 기어 D 로 서 있으면 흐리게 + "정차(P) 때 검색할 수 있어요"(누르지 않는다)
 */
@Composable
private fun DestSearchBar(d: DestUi, actions: TagoActions) {
    val a = if (d.locked) 0.45f else 1f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Touch(if (d.locked) ({}) else actions.onDestOpen, Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().height(L.px(60)).clip(RoundedCornerShape(L.px(30))).background(L.chip.copy(alpha = L.chip.alpha * 1.6f * a))
                    .border(1.dp, L.hair, RoundedCornerShape(L.px(30))).padding(horizontal = L.px(20)), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(L.px(26))) {   // 돋보기: 원 + 손잡이
                        val w = size.minDimension; val st = w * 0.11f; val c = L.t1.copy(alpha = a)
                        drawCircle(c, radius = w * 0.32f, center = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(st))
                        drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.66f, w * 0.66f), androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.92f), st, androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    Spacer(Modifier.width(L.px(14)))
                    Text(d.prompt, style = L.what, color = L.t1.copy(alpha = if (d.locked) 0.45f else 0.85f))
                }
            }
            // 검색 칸 오른쪽 마이크(11-14 사용자 "검색에 stt 기능도 넣어줘"): 누르면 바로 듣고, 들은 말로 검색. 기어 D 면 칸과 함께 흐리게(누르지 않는다)
            if (d.mic != null) MicButton(d.mic, d.listening, enabled = !d.locked, onClick = actions.onDestVoice, h = L.px(60))
        }
        if (d.locked) Text(d.lockNote, style = L.foot, color = L.t2, modifier = Modifier.padding(top = L.px(6), start = L.px(4)))
    }
}

/**
 * 마이크 버튼(말로 검색): 보이는 원은 칸 높이 [h], 닿는 자리는 96×96. 평소 유리 원 + 흰 마이크, 듣는 중이면 흰 원 + 진한 마이크(그 화면의 흰 강조가 된다).
 * 기어 D 면 흐리게 하고 누르지 않는다
 */
@Composable
private fun MicButton(label: String, listening: Boolean, enabled: Boolean, onClick: () -> Unit, h: Dp, light: Boolean = false) {
    Touch(if (enabled) onClick else ({}), Modifier.width(L.TOUCH).semantics(mergeDescendants = true) { contentDescription = label }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val bg = when { light && listening -> L.pillText; light -> L.pillChip.copy(alpha = L.pillChip.alpha * if (enabled) 1f else 0.45f)
                listening -> L.goBg; else -> L.chip.copy(alpha = L.chip.alpha * 1.6f * if (enabled) 1f else 0.45f) }   // 밝은 알약 안(light): 평소 옅은 회색 원, 듣는 중 진한 원
            Box(Modifier.size(h).clip(RoundedCornerShape(50)).background(bg)
                .border(1.dp, if (light) L.pillHair else L.hair, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                val col = when { light && listening -> L.pillBg; light -> L.pillText.copy(alpha = if (enabled) 1f else 0.45f); listening -> L.goText; else -> L.t1.copy(alpha = if (enabled) 1f else 0.45f) }
                Canvas(Modifier.size(L.px(26))) {   // 마이크: 캡슐 + 받침 호 + 기둥 + 바닥
                    val w = size.minDimension; val st = w * 0.09f
                    drawRoundRect(col, topLeft = Offset(w * 0.35f, w * 0.04f), size = Size(w * 0.30f, w * 0.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f))
                    drawArc(col, 0f, 180f, false, topLeft = Offset(w * 0.20f, w * 0.20f), size = Size(w * 0.60f, w * 0.50f), style = Stroke(st, cap = StrokeCap.Round))
                    drawLine(col, Offset(w * 0.5f, w * 0.70f), Offset(w * 0.5f, w * 0.90f), st, StrokeCap.Round)
                    drawLine(col, Offset(w * 0.34f, w * 0.92f), Offset(w * 0.66f, w * 0.92f), st, StrokeCap.Round)
                }
            }
        }
    }
}

/** 설정 단추(11-15, 사용자 "설정은 따로 우측에 버튼으로 빼자"): 지도 오른쪽 위의 작은 유리 원 안 톱니 — 응급 단추(오른쪽 아래)와 같은 크기·같은 안쪽 여백.
 *  밝은 흰 원 60(11-15 "밝은 테마로"), 닿는 자리 96×96. 잠기면(기어 D) 흐리게, 누르지 않는다 */
@Composable
private fun SettingsMini(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.size(L.TOUCH).semantics { contentDescription = label }
        .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = debounced(onClick)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(L.px(60)).lightPill(RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            val col = L.pillText.copy(alpha = if (enabled) 0.92f else 0.4f)
            Canvas(Modifier.size(L.px(26))) {   // 톱니: 이 8개 + 고리
                val w = size.minDimension; val c = Offset(w / 2, w / 2)
                for (i in 0 until 8) rotate(i * 45f, pivot = c) {
                    drawRoundRect(col, topLeft = Offset(c.x - w * 0.085f, c.y - w * 0.49f), size = Size(w * 0.17f, w * 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
                }
                drawCircle(col, radius = w * 0.29f, center = c, style = Stroke(w * 0.15f))
            }
        }
    }
}

/** 켬/끔 스위치(순정 설정 문법, 흑백): 켬 = 흰 바탕 + 진한 손잡이(오른쪽), 끔 = 옅은 바탕 + 흰 손잡이(왼쪽) */
@Composable
private fun Toggle(on: Boolean) {
    val lt = LocalLightCard.current   // 밝은 카드: 켬 = 진한 바탕 + 흰 손잡이, 끔 = 옅은 회색 바탕 + 흰 손잡이(테두리)
    val w = L.px(58); val h = L.px(34); val k = L.px(26)
    Box(Modifier.width(w).height(h).clip(RoundedCornerShape(50)).background(if (lt) (if (on) L.pillText else L.pillChip) else if (on) L.goBg else L.chip).border(1.dp, if (lt) L.pillHair else L.hair, RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(horizontal = L.px(4)).size(k).clip(RoundedCornerShape(50)).background(if (lt) L.pillBg else if (on) L.goText else L.t1).then(if (lt) Modifier.border(1.dp, L.pillHair, RoundedCornerShape(50)) else Modifier))
    }
}

/**
 * 설정 판(11-14 사용자 "설정 버튼도 만드렁줘"): 순정 설정의 목록 + 오른쪽 스위치 문법을 유리 판 안에. 맨 위 제목 + [닫기], 줄마다 닿는 높이 96,
 * 맨 아래 앱 버전 한 줄. 항목은 실제로 동작하는 것만(SettingsUi)
 */
@Composable
private fun ColumnScope.SettingsBody(st: ai.meeos.tago.ui.SettingsUi, actions: TagoActions, onClose: () -> Unit) {
    val lt = LocalLightCard.current
    val t1 = if (lt) L.pillText else L.t1; val t2 = if (lt) L.pillText2 else L.t2; val t3 = if (lt) L.pillText2.copy(alpha = 0.42f) else L.t3
    val chip = if (lt) L.pillChip else L.chip; val hair = if (lt) L.pillHair else L.hair; val line = if (lt) L.pillHair else L.rowLine
    Row(Modifier.fillMaxWidth().padding(start = L.px(22), end = L.px(8)), verticalAlignment = Alignment.CenterVertically) {
        Text(st.title, style = L.head, color = t1, modifier = Modifier.weight(1f))
        Touch(onClose) { Text(st.closeLabel, style = L.chipText, color = t1, modifier = Modifier.clip(RoundedCornerShape(50)).background(chip).padding(horizontal = L.px(20), vertical = L.px(10))) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
    Column(Modifier.padding(horizontal = L.px(22))) {
        if (st.autoLinkLabel.isNotEmpty()) {   // 주행 시 자동 연결(주변 차 연결 — 맨 위, 11-14)
            SettingRow(st.autoLinkLabel, st.autoLinkSub, onClick = { actions.onAutoLink(!st.autoLinkOn) }) { Toggle(st.autoLinkOn) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        }
        SettingRow(st.realLocLabel, st.realLocSub, onClick = { actions.onRealLoc(!st.realLocOn) }) { Toggle(st.realLocOn) }
        if (st.parkClearLabel != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            SettingRow(st.parkClearLabel, null, onClick = actions.onParkClear) {
                Text(st.parkClearBtn, style = L.chipText, color = t1, modifier = Modifier.clip(RoundedCornerShape(50)).background(chip).padding(horizontal = L.px(16), vertical = L.px(8)))
            }
        }
        if (st.studyLabel != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            SettingRow(st.studyLabel, null, onClick = actions.onStudyOpen) { Row(verticalAlignment = Alignment.CenterVertically) { Text(st.studyState ?: "", style = L.row, color = t2); Spacer(Modifier.width(L.px(10))); Chevron() } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        if (st.withdrawLabel != null) {   // 동의 거두기(11-15): 동의했을 때만. 누르면 바로 거두고 연결을 끊는다
            SettingRow(st.withdrawLabel, st.withdrawSub, onClick = actions.onConsentWithdraw) {
                Text(st.withdrawBtn, style = L.chipText, color = t1, modifier = Modifier.clip(RoundedCornerShape(50)).background(chip).padding(horizontal = L.px(16), vertical = L.px(8)))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        }
        SettingRow(st.licensesLabel, null, onClick = actions.onLicensesOpen) { Chevron() }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
    Text(st.version, style = L.foot, color = t3, modifier = Modifier.padding(start = L.px(22), end = L.px(22), top = L.px(14), bottom = L.px(18)))
}

/**
 * 주변 차 연결 한 줄(첫 화면 판): 초록 점(켜짐)·회색 점(꺼짐) + 글 + 오른쪽 스위치. 줄 전체가 닿는 자리(96) — 누르면 켬/끔(동의 전이면 동의 판).
 * 기어 D 로 서 있을 때는 글만 흐리게 보이고 누르지 않는다
 */
@Composable
private fun LinkLine(l: ai.meeos.tago.ui.LinkLineUi, enabled: Boolean, onToggle: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair))
    Touch(if (enabled) onToggle else ({}), Modifier.fillMaxWidth().padding(horizontal = L.px(22))) {
        Row(Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.55f }, verticalAlignment = Alignment.CenterVertically) {
            HaloDot(if (l.on) L.green else L.gray, L.px(9), L.px(5))
            Spacer(Modifier.width(L.px(10)))
            Text(l.text, style = L.row, color = L.t1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(L.px(12)))
            Toggle(l.on)
        }
    }
}

/**
 * 첫 실행 한 번의 동의 판(주변 차 연결, 11-14): 제목(두 줄) + 보내는 것·보내지 않는 것·끄는 법 세 줄 + [동의하고 켜기](흰) [나중에].
 * 이 판이 떠 있는 동안 판 아래 알약은 감춘다. 차가 서 있을 때만 뜬다
 */
@Composable
private fun ColumnScope.ConsentBody(c: ai.meeos.tago.ui.ConsentUi, actions: TagoActions) {
    Row(Modifier.padding(start = L.px(22), end = L.px(22), top = L.px(20)), verticalAlignment = Alignment.CenterVertically) {
        HaloDot(L.green, L.px(9), L.px(5))
        Spacer(Modifier.width(L.px(10)))
        Text(BuildConfig.APP_LABEL, style = L.pill, color = L.t2)
    }
    Text(c.title, style = L.head, color = L.t1, modifier = Modifier.padding(start = L.px(22), end = L.px(22), top = L.px(10), bottom = L.px(14)))
    Column(Modifier.padding(horizontal = L.px(22))) {
        c.lines.forEach { Text(it, style = L.row, color = L.t2, modifier = Modifier.padding(bottom = L.px(10))) }
    }
    Chips(listOfNotNull(c.agreeLabel to { actions.onConsent(true) }, c.laterLabel to { actions.onConsent(false) },
        c.moreLabel?.let { it to actions.onLicensesOpen }), firstWhite = true)   // [자세히] → 고지 화면(11-15, 매니패스트 S-YNQPDP)
    Spacer(Modifier.height(L.px(10)))
}

/** 설정 한 줄: 이름(+ 아래 보조 한 줄) · 오른쪽 조작. 줄 전체가 닿는 자리(96 이상) */
@Composable
private fun SettingRow(label: String, sub: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Touch(onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = L.px(10)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = L.row.copy(fontWeight = FontWeight.SemiBold), color = if (LocalLightCard.current) L.pillText else L.t1)
                if (sub != null) Text(sub, style = L.foot, color = if (LocalLightCard.current) L.pillText2 else L.t2, modifier = Modifier.padding(top = L.px(2)))
            }
            Spacer(Modifier.width(L.px(12)))
            trailing()
        }
    }
}

/** 오른쪽 꺾쇠(›): 다른 판으로 들어간다 */
@Composable
private fun Chevron() {
    val col = if (LocalLightCard.current) L.pillText2 else L.t2
    Canvas(Modifier.size(L.px(16))) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * .34f, h * .18f); lineTo(w * .68f, h * .5f); lineTo(w * .34f, h * .82f) }, col,
            style = Stroke(width = L.px(2.2f).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 응급: 붉은 십자 표지 + 제목 + 카드 줄 + [119 전화 화면](빨강) [응급 끝내기] */
@Composable
private fun ColumnScope.EmergencyBody(p: Panel.Emergency, actions: TagoActions) {
    TopRow(Glyph.SOS, AlertTone.DANGER, { Text(p.card.title ?: "", style = L.head, color = L.t1) }, null, null)
    Column(Modifier.padding(horizontal = L.px(22))) {
        p.card.lines.forEach { Text(it.text, style = L.row, color = L.t1, modifier = Modifier.padding(bottom = L.px(8))) }
    }
    Chips(listOf(p.dialLabel to actions.onDial, p.endLabel to actions.onEndEmergency), firstWhite = false, danger = true)
    Spacer(Modifier.height(L.px(8)))
}

/** 정차 전용 넓은 판(연구 기록·오픈소스 고지·충전기 고장 신고): 제목 + 기존 내용 그대로(스크롤) */
@Composable
private fun ColumnScope.WideBody(state: TagoUiState, actions: TagoActions) {
    val c = LocalTagoColors.current
    Text(state.headline, style = TagoType.titleL, color = c.text)
    if (state.subline.isNotEmpty()) Text(state.subline, style = TagoType.bodyM, color = c.text2, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
    when (val p = state.panel) {
        is Panel.Study -> StudyContent(p.ui, actions)
        is Panel.Licenses -> LicensesContent(p.ui, actions)
        is Panel.ChargeReport -> ChargeReportContent(p.ui, actions)
        else -> {}
    }
}

/** 함께 가는 중 정차 판 안의 주차 칸(구분선 아래): P 표지 줄 + [고치기] 알약(고치는 중이면 한 층 위·아래 / 완료·지우기) */
@Composable
private fun ParkingBlock(p: ParkingUi, actions: TagoActions) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair))
    if (p.card != null) ParkTop(p.card)
    else Text(p.line, style = L.row.copy(fontWeight = FontWeight.SemiBold), color = L.t1, modifier = Modifier.padding(start = L.px(22), end = L.px(22), top = L.px(14)))
    // 일행의 주차 한 줄("일행 2대 주차함 · 가까이", 11-14 시연 4막) — 센 값 그대로, "같은 주차장"이라고 쓰지 않는다
    if (p.mates != null) Row(Modifier.padding(start = L.px(22), end = L.px(22), bottom = L.px(12)), verticalAlignment = Alignment.CenterVertically) {
        HaloDot(L.green, L.px(9), L.px(5))
        Spacer(Modifier.width(L.px(10)))
        Text(p.mates, style = L.row, color = L.t1)
    }
    if (p.edit == null) Chips(listOf(p.fixLabel to actions.onParkFix), firstWhite = false) else ParkEditChips(p, actions)
}

/** 고치는 중: [한 층 위][한 층 아래] / [완료](흰)[지우기] */
@Composable
private fun ParkEditChips(p: ParkingUi, actions: TagoActions) {
    val e = p.edit ?: return
    Chips(listOf(e.upLabel to { actions.onParkFloor(true) }, e.downLabel to { actions.onParkFloor(false) }), firstWhite = false)
    Chips(listOf(e.doneLabel to actions.onParkFix, e.clearLabel to actions.onParkClear), firstWhite = true)
    Spacer(Modifier.height(L.px(8)))
}

/** 주차 판 맨 위 줄: 둥근 "P" 표지(흰 원 · 진한 글자) + 큰 글(층·기둥 또는 범위) + 사진이면 작은 [사진] 표시 + 근거(실제 값만, 넘치면 줄바꿈) */
@Composable
private fun ParkTop(c: ai.meeos.tago.ui.ParkCardUi, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = L.px(18), end = if (trailing != null) L.px(8) else L.px(22), top = L.px(18), bottom = L.px(16)),
        verticalAlignment = Alignment.CenterVertically) {
        ParkTopBody(c, Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun ParkTopBody(c: ai.meeos.tago.ui.ParkCardUi, modifier: Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(L.icon).clip(RoundedCornerShape(50)).background(L.goBg), contentAlignment = Alignment.Center) {
            Text("P", style = L.big.copy(fontSize = L.sp(32), lineHeight = L.sp(36)), color = L.goText)
        }
        Spacer(Modifier.width(L.px(16)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.big, style = L.big, color = L.t1)
                if (c.photo) {
                    Spacer(Modifier.width(L.px(10)))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(L.chip).padding(horizontal = L.px(10), vertical = L.px(3))) {
                        Text(androidx.compose.ui.res.stringResource(ai.meeos.tago.R.string.park_tag_photo), style = L.chipText, color = L.t1)
                    }
                }
            }
            // 근거는 덜어 내지 않는다(정차 중에만 보이는 판) — 넘치면 줄바꿈. 첫 항목(출처)만 흰 글
            if (c.evidence.isNotEmpty()) Text(buildAnnotatedString {
                val parts = c.evidence.split(" · ")
                withStyle(SpanStyle(color = L.t1, fontWeight = FontWeight.SemiBold)) { append(parts.first()) }
                if (parts.size > 1) append(" · " + parts.drop(1).joinToString(" · "))
            }, style = L.row, color = L.t2, modifier = Modifier.padding(top = L.px(7)))
        }
    }
}

/** 주행 요약(정차 중): 제목 + 줄 */
@Composable
private fun SummaryBlock(s: CardUi) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair))
    Column(Modifier.fillMaxWidth().padding(horizontal = L.px(22), vertical = L.px(12))) {
        if (s.title != null) Text(s.title, style = L.row.copy(fontWeight = FontWeight.SemiBold), color = L.t1)
        s.lines.forEach { Text(it.text, style = L.row, color = L.t2, modifier = Modifier.padding(top = L.px(4))) }
    }
}

/** 판 바로 아래 흰 알약 버튼(하나): 높이 62, 닿는 높이 96(보이는 알약은 판 아래 14 자리에 그대로 둔다) */
@Composable
private fun GoPill(label: String, glyph: Glyph?, onClick: () -> Unit, white: Boolean = true) {
    val ext = (L.TOUCH - L.goH).coerceAtLeast(0.dp) / 2
    Touch(onClick, Modifier.offset(y = -ext)) {
        Row(Modifier.height(L.goH).shadow(14.dp, RoundedCornerShape(L.goH / 2), ambientColor = L.shadow, spotColor = L.shadow)
            .clip(RoundedCornerShape(L.goH / 2)).then(if (white) Modifier.background(L.goBg) else Modifier.glass(RoundedCornerShape(L.goH / 2)))
            .padding(start = L.px(if (glyph == null) 24 else 20), end = L.px(24)), verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) {
                Canvas(Modifier.size(L.px(20))) {
                    val k = size.width / 24f
                    if (glyph == Glyph.WARN) {   // 우회 = 내비 화살
                        val arrow = Path().apply { moveTo(12f * k, 2f * k); lineTo(21f * k, 21f * k); lineTo(12f * k, 17f * k); lineTo(3f * k, 21f * k); close() }
                        drawPath(arrow, L.goText)
                    } else drawCircle(L.green, 5f * k)
                }
                Spacer(Modifier.width(L.px(10)))
            }
            Text(label, style = L.go, color = if (white) L.goText else L.t1, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * 최소 표시 첫 화면(시안 C — 11-14 사용자 "최소한으로 표시되게 디자인" · "c로 ㄱㄱ"): 평소에는 왼쪽 위에 상태 점 단추 + 검색 한 줄(마이크)만.
 * 상태 점을 누르면 바로 아래 작은 판이 펼쳐진다: 주행 시 자동 연결(상태) / 일행 만들기(btn_convoy) / 주차 위치. (시연 판) 시연 주행은 검색 줄 아래 알약(11-15).
 * 검색 줄을 누르면 같은 자리에 검색 판(DestBlock 의 열린 모습), 주차 줄을 누르면 주차 판. 기어 D 로 서 있으면 검색 줄은 흐리게, 판은 열리지 않는다.
 * 구호·지난 주행 요약·안내 글·판 아래 알약은 그리지 않는다(요약은 도착해 선 정차 판에만 남는다)
 */
@Composable
private fun MinimalHome(state: TagoUiState, p: Panel.Idle, actions: TagoActions, isleW: Dp, maxH: Dp) {
    var menu by remember { mutableStateOf(false) }
    var parkOpen by remember { mutableStateOf(false) }
    val locked = state.driving
    LaunchedEffect(locked) { if (locked) { menu = false; parkOpen = false } }
    val d = p.dest
    val park = p.parking?.takeIf { !locked && it.card != null }
    BackHandler(enabled = menu || (parkOpen && park != null)) { menu = false; parkOpen = false }
    if (d != null && d.open) {
        // 검색 열림(11-15 시안 A, "시안이랑 다름" 고침): 접힌 줄과 똑같은 자리·모양 — 상태 점 + 흰 검색 알약에서 바로 적고([검색] 단추 없음, 자판의 검색 키),
        // 알림·결과는 그 아래 밝은 판. 상태 점을 누르면 검색을 닫고 메뉴를 연다
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDotButton(p.menuLabel ?: "", if (state.notice != null) L.red else if (p.link?.on == true) L.green else L.gray, enabled = !locked, actions = actions) { actions.onDestClose(); parkOpen = false; menu = true }
            Spacer(Modifier.width(L.px(8))); SearchEditPill(d, actions, minOf(L.px(420), isleW - L.TOUCH - L.px(8)))
        }
        if (d.note != null || d.results.isNotEmpty()) {
            Spacer(Modifier.height(L.px(10)))
            Isle(isleW, maxH - L.TOUCH - L.px(10), light = true) {
                Column(Modifier.padding(start = L.px(22), end = L.px(22), top = L.px(8), bottom = L.px(10))) { DestResults(d, actions, light = true) }
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDotButton(p.menuLabel ?: "", if (state.notice != null) L.red else if (p.link?.on == true) L.green else L.gray, enabled = !locked, actions = actions) { parkOpen = false; menu = !menu }
        if (d != null) { Spacer(Modifier.width(L.px(8))); MinSearchBar(d, actions, minOf(L.px(420), isleW - L.TOUCH - L.px(8))) }
    }
    val small = Modifier.padding(top = L.px(8)).clip(RoundedCornerShape(L.px(10))).background(L.glass).padding(horizontal = L.px(14), vertical = L.px(8))
    if (state.notice != null) Text(state.notice, style = L.foot, color = L.red, modifier = small)
    if (d != null && d.locked) Text(d.lockNote, style = L.foot, color = L.t2, modifier = small)   // 기어 D 로 서 있을 때만: 왜 흐린지 한 줄
    val demo = p.demoLabel
    if (demo != null && !locked && !menu && !parkOpen) { Spacer(Modifier.height(L.px(10))); DemoDrivePill(demo, actions.onDemoLap) }   // 시연 판만(11-15 "시연 주행 버튼 만들어줘 기본화면에")
    val card = park?.card
    if (parkOpen && park != null && card != null) {
        Spacer(Modifier.height(L.px(10)))
        Isle(isleW, maxH - L.TOUCH - L.px(10), scroll = true) {
            ParkTop(card)
            if (park.edit == null) Chips(listOfNotNull(park.fixLabel to actions.onParkFix, p.settings?.closeLabel?.let { it to { parkOpen = false } }), firstWhite = true)
            else ParkEditChips(park, actions)
            Spacer(Modifier.height(L.px(10)))
        }
    } else if (menu) {
        Spacer(Modifier.height(L.px(10)))
        Isle(minOf(L.px(350), isleW), maxH - L.TOUCH - L.px(10), scroll = true) {
            val link = p.link; val st = p.settings
            val convoy = p.convoyLabel
            val rows = mutableListOf<@Composable () -> Unit>()
            // 연결 상태 한 줄(11-15 사용자 "왜 주행중자동연결이 두군데에 있어"): 켜고 끄는 스위치는 설정(오른쪽 위 톱니)에만 — 여기는 지금 상태만, 누르지 않는다
            if (link != null && st != null) rows.add {
                Row(Modifier.fillMaxWidth().heightIn(min = L.TOUCH).padding(start = L.px(20), end = L.px(18)), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(L.px(32)), contentAlignment = Alignment.Center) { HaloDot(if (link.on) L.green else L.gray, L.px(10), L.px(5)) }
                    Spacer(Modifier.width(L.px(14)))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.link_status_title), style = L.row.copy(fontWeight = FontWeight.SemiBold), color = L.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (link.short.isNotEmpty()) Text(link.short, style = L.foot, color = L.t2, maxLines = 1)
                    }
                }
            }
            if (convoy != null) rows.add { MenuRow({ menu = false; actions.onConvoy() }, { MenuGlyph(0) }, convoy, null) { Chevron() } }
            if (card != null) rows.add { MenuRow({ menu = false; parkOpen = true }, {
                Box(Modifier.size(L.px(30)).clip(RoundedCornerShape(50)).background(L.goBg), contentAlignment = Alignment.Center) { Text("P", style = L.chipText, color = L.goText) }
            }, card.big, null) { Chevron() } }
            rows.forEachIndexed { i, r -> if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(L.hair)); r() }
        }
    }
    if (state.devLog != null) { Spacer(Modifier.height(L.px(10))); Isle(isleW, maxH - L.TOUCH - L.px(10), scroll = true) { Box(Modifier.padding(L.px(12))) { DevPanel(state.devStatus, state.devLog) } } }
}

/** 첫 화면 [▶ 시연 주행](시연 빌드만): 검색 줄과 같은 흰 알약, 높이 62(닿는 높이 96). 누르면 시연 경로를 한 바퀴 달린다(RoadLinkController.startDemoLap) */
@Composable
private fun DemoDrivePill(label: String, onClick: () -> Unit) {
    val ext = (L.TOUCH - L.goH).coerceAtLeast(0.dp) / 2
    Touch(onClick, Modifier.offset(y = -ext).semantics { contentDescription = label }) {
        Row(Modifier.height(L.goH).lightPill(RoundedCornerShape(50)).padding(start = L.px(22), end = L.px(26)), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(L.px(18))) {   // ▶
                val w = size.width
                drawPath(Path().apply { moveTo(w * 0.18f, w * 0.06f); lineTo(w * 0.94f, w * 0.5f); lineTo(w * 0.18f, w * 0.94f); close() }, L.pillText)
            }
            Spacer(Modifier.width(L.px(12)))
            Text(label, style = L.go, color = L.pillText, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * 상태 점 단추(최소 표시 첫 화면 맨 왼쪽): 색 점(초록 = 자동 연결 켜짐 · 회색 = 꺼짐·동의 전 · 빨강 = 서비스 알림) + 아래 작은 점 셋(누르면 펼쳐진다는 표시).
 * 보이는 크기 = 닿는 크기 96. 2.5초 누르면 개발 패널(시연 빌드만). 기어 D 로 서 있으면 점 셋이 없고 누르지 않는다
 */
@Composable
private fun StatusDotButton(label: String, color: Color, enabled: Boolean, actions: TagoActions, onTap: () -> Unit) {
    Column(Modifier.size(L.TOUCH).lightPill(RoundedCornerShape(50)).semantics { contentDescription = label }.then(pillPress(actions, if (enabled) onTap else null)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        HaloDot(color, L.px(11), L.px(5))
        if (enabled) Canvas(Modifier.padding(top = L.px(6)).width(L.px(24)).height(L.px(6))) {
            val r = size.height * 0.42f
            for (i in 0..2) drawCircle(L.pillText2, r, Offset(size.width * (0.18f + 0.32f * i), size.height / 2))
        }
    }
}

/** 검색 한 줄(최소 표시 첫 화면): 돋보기 + "어디로 갈까요?"(목적지를 골랐으면 그 이름 + 거리 한 줄, ✕ 로 지움) + 마이크. 높이 96. 기어 D 면 흐리게 */
@Composable
private fun MinSearchBar(d: DestUi, actions: TagoActions, width: Dp) {
    val a = if (d.locked) 0.45f else 1f
    Row(Modifier.width(width).height(L.TOUCH).lightPill(RoundedCornerShape(50))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = debounced(if (d.locked) ({}) else actions.onDestOpen)).padding(start = L.px(30), end = L.px(4)),
        verticalAlignment = Alignment.CenterVertically) {
        SearchGlass(L.pillText.copy(alpha = a))
        Spacer(Modifier.width(L.px(14)))
        if (d.chosen != null) Column(Modifier.weight(1f)) {
            Text(d.chosen, style = L.what, color = L.pillText.copy(alpha = a), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.chosenSub != null) Text(d.chosenSub, style = L.foot, color = L.pillText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (d.query.isNotBlank()) Text(d.query, style = L.what, color = L.pillText.copy(alpha = a), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))   // 적다 만 글(11-15 "기흥이라고 적어놨는데 … 안내문구가 나옴")
        else Text(d.prompt, style = L.what, color = L.pillText2.copy(alpha = a), maxLines = 1, modifier = Modifier.weight(1f))
        if (d.chosen != null && !d.locked) Touch(actions.onDestClear, Modifier.width(L.px(44)).semantics { contentDescription = d.clearLabel }) {
            Canvas(Modifier.fillMaxWidth().height(L.px(18))) {   // ✕: 고른 목적지 지우기
                val s = size.height; val x0 = (size.width - s) / 2
                drawLine(L.pillText2, Offset(x0, 0f), Offset(x0 + s, s), L.px(2.2f).toPx(), StrokeCap.Round)
                drawLine(L.pillText2, Offset(x0 + s, 0f), Offset(x0, s), L.px(2.2f).toPx(), StrokeCap.Round)
            }
        }
        if (d.mic != null) MicButton(d.mic, d.listening, enabled = !d.locked, onClick = actions.onDestVoice, h = L.px(60), light = true)
    }
}

/** 열린 검색 알약(11-15 시안 A): 접힌 MinSearchBar 와 같은 폭·높이·돋보기·예시 글("어디로 갈까요?")·회색 마이크 원, 그 자리에서 적는다.
 *  찾기는 자판의 검색 키, 찾는 중엔 마이크 앞에 "…". 빈 채로 열리면 바로 적을 수 있게 커서를 둔다 */
@Composable
private fun SearchEditPill(d: DestUi, actions: TagoActions, width: Dp) {
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val edit = { if (!d.listening) { runCatching { fr.requestFocus() }; kb?.show() } }
    // 커서 자리를 가진 글 상태: 열 때·말로 들은 글이 들어올 때 커서는 글 끝(전에는 다시 열면 맨 앞). 적는 중(조합 포함)은 그대로 둔다
    var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(d.query, androidx.compose.ui.text.TextRange(d.query.length))) }
    LaunchedEffect(d.query) { if (d.query != tfv.text) tfv = androidx.compose.ui.text.input.TextFieldValue(d.query, androidx.compose.ui.text.TextRange(d.query.length)) }
    // 11-15 "안되는뎅": 글자 밖(돋보기·빈 곳)을 누르면 자판이 안 떠 찾을 수 없었다 → 알약 어디를 눌러도 적는 칸 + 자판, 결과가 없으면 열 때 커서
    LaunchedEffect(Unit) { if (d.results.isEmpty() && !d.listening) runCatching { fr.requestFocus() } }
    Row(Modifier.width(width).height(L.TOUCH).lightPill(RoundedCornerShape(50))
        .clickable(remember { MutableInteractionSource() }, indication = null) { edit() }.padding(start = L.px(16), end = L.px(4)),
        verticalAlignment = Alignment.CenterVertically) {
        // 돋보기 = 검색(적은 글이 있으면 찾고, 비었으면 적는 칸으로) — 자판의 [검색] 키와 같다
        Box(Modifier.size(L.px(56)).clip(RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (d.query.isNotBlank()) actions.onDestSearch() else edit() },
            contentAlignment = Alignment.Center) { SearchGlass(L.pillText) }
        Spacer(Modifier.width(L.px(2)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (d.listening) Text(d.listenText ?: d.prompt, style = L.what, color = L.pillText2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else BasicTextField(tfv, { tfv = it; if (it.text != d.query) actions.onDestQuery(it.text) }, singleLine = true, textStyle = L.what.copy(color = L.pillText), cursorBrush = SolidColor(L.pillText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { actions.onDestSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
                decorationBox = { inner -> if (d.query.isEmpty()) Text(d.prompt, style = L.what, color = L.pillText, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() })   // 접힌 줄과 같은 진한 글(그쪽은 pillText2 에 alpha 1 = 진한 색)
        }
        if (d.busy) Text("…", style = L.what, color = L.pillText2, modifier = Modifier.padding(horizontal = L.px(10)))
        if (d.mic != null) MicButton(d.mic, d.listening, enabled = !d.locked, onClick = actions.onDestVoice, h = L.px(60), light = true)
    }
}

/** 돋보기: 원 + 손잡이(접힌 검색 알약·펼친 검색 칸이 같이 쓴다) */
@Composable
private fun SearchGlass(c: Color) {
    Canvas(Modifier.size(L.px(26))) {
        val w = size.minDimension; val st = w * 0.11f
        drawCircle(c, radius = w * 0.32f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(st))
        drawLine(c, Offset(w * 0.66f, w * 0.66f), Offset(w * 0.92f, w * 0.92f), st, StrokeCap.Round)
    }
}

/** 펼친 판 한 줄: 왼쪽 그림 · 이름(+ 아래 작은 상태 글) · 오른쪽 조작. 줄 전체가 닿는 자리(96 이상) */
@Composable
private fun MenuRow(onClick: () -> Unit, leading: @Composable () -> Unit, label: String, sub: String?, trailing: @Composable () -> Unit) {
    Touch(onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = L.px(20), end = L.px(18)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(L.px(32)), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(L.px(14)))
            Column(Modifier.weight(1f)) {
                Text(label, style = L.row.copy(fontWeight = FontWeight.SemiBold), color = L.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrEmpty()) Text(sub, style = L.foot, color = L.t2, maxLines = 1)
            }
            Spacer(Modifier.width(L.px(10)))
            trailing()
        }
    }
}

/** 펼친 판의 흰 그림: 0 = 두 사람(일행) · 1 = 톱니(설정) · 2 = 재생 세모(시연 한 바퀴) */
@Composable
private fun MenuGlyph(kind: Int) {
    Canvas(Modifier.size(L.px(28))) {
        val w = size.minDimension; val col = L.t1
        when (kind) {
            0 -> {
                drawCircle(col, w * 0.15f, Offset(w * 0.36f, w * 0.30f))
                drawArc(col, 180f, 180f, true, topLeft = Offset(w * 0.08f, w * 0.52f), size = Size(w * 0.56f, w * 0.66f))
                drawCircle(col, w * 0.12f, Offset(w * 0.72f, w * 0.36f))
                drawArc(col, 180f, 180f, true, topLeft = Offset(w * 0.52f, w * 0.58f), size = Size(w * 0.42f, w * 0.54f))
            }
            1 -> {
                val c = Offset(w / 2, w / 2)
                for (i in 0 until 8) rotate(i * 45f, pivot = c) {
                    drawRoundRect(col, topLeft = Offset(c.x - w * 0.085f, c.y - w * 0.49f), size = Size(w * 0.17f, w * 0.22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
                }
                drawCircle(col, radius = w * 0.29f, center = c, style = Stroke(w * 0.15f))
            }
            else -> drawPath(Path().apply { moveTo(w * 0.28f, w * 0.16f); lineTo(w * 0.84f, w * 0.5f); lineTo(w * 0.28f, w * 0.84f); close() }, col)
        }
    }
}

/** 최소 표시의 응급 단추: 작은 유리 원 안 빨간 십자 하나(오른쪽 아래, 늘 보인다). 닿는 자리 96×96 */
@Composable
private fun SosMini(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.size(L.TOUCH).semantics { contentDescription = label }
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = debounced(onClick)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(L.px(52)).glass(RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(L.px(22))) {
                val k = size.width / 24f
                val cross = Path().apply { moveTo(9f * k, 3f * k); lineTo(15f * k, 3f * k); lineTo(15f * k, 9f * k); lineTo(21f * k, 9f * k); lineTo(21f * k, 15f * k); lineTo(15f * k, 15f * k)
                    lineTo(15f * k, 21f * k); lineTo(9f * k, 21f * k); lineTo(9f * k, 15f * k); lineTo(3f * k, 15f * k); lineTo(3f * k, 9f * k); lineTo(9f * k, 9f * k); close() }
                drawPath(cross, L.red)
            }
        }
    }
}

/** 시연 주행 재생 막대(시연 판만): 동영상 재생 막대와 같은 문법 — [재생·멈춤] 진행 줄(누르거나 끌면 그 자리로, 앞·뒤 모두) 지나온 거리 [배속].
 *  진행 줄 위 점: 앞의 위험(빨강) · 휴게소(흰색). 단추·줄 모두 닿는 높이 96 */
@Composable
private fun PlayerBar(p: ai.meeos.tago.ui.PlayerUi, actions: TagoActions, modifier: Modifier) {
    val seek by androidx.compose.runtime.rememberUpdatedState(actions.onPlaySeek)
    Row(modifier.height(L.TOUCH).glass(RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(L.TOUCH).semantics { contentDescription = if (p.paused) p.playLabel else p.pauseLabel }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions.onPlayToggle), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(L.px(26))) {
                val w = size.width; val h = size.height
                if (p.paused) drawPath(Path().apply { moveTo(w * 0.2f, h * 0.06f); lineTo(w * 0.96f, h * 0.5f); lineTo(w * 0.2f, h * 0.94f); close() }, L.t1)
                else for (x in listOf(0.14f, 0.62f)) drawRoundRect(L.t1, Offset(w * x, h * 0.08f), androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.84f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
            }
        }
        // 진행 줄: 1초마다 오는 자리를 고르게 잇고, 크게 뛰면(옮기기·멈춤) 바로 그 자리로
        val anim = remember { Animatable(p.frac) }
        LaunchedEffect(p.frac, p.paused) {
            if (p.paused || kotlin.math.abs(p.frac - anim.value) > 0.03f) anim.snapTo(p.frac)
            else anim.animateTo(p.frac, tween(1000, easing = androidx.compose.animation.core.LinearEasing))
        }
        var drag by remember { mutableStateOf<Float?>(null) }
        Canvas(Modifier.weight(1f).height(L.TOUCH).semantics { contentDescription = p.seekLabel }
            .pointerInput(Unit) { detectTapGestures { o -> val r = 11.dp.toPx(); seek(((o.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> val r = 11.dp.toPx(); drag = ((o.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { seek(it) }; drag = null }, onDragCancel = { drag = null },
                ) { change, dx -> change.consume(); drag = ((drag ?: 0f) + dx / (size.width - 2 * 11.dp.toPx())).coerceIn(0f, 1f) }
            }) {
            val r = 11.dp.toPx(); val y = size.height / 2; val x0 = r; val span = size.width - 2 * r
            val at = x0 + span * (drag ?: anim.value)
            drawLine(L.t1.copy(alpha = 0.22f), Offset(x0, y), Offset(x0 + span, y), 6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(L.t1, Offset(x0, y), Offset(at, y), 6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            p.marks.forEach { m -> drawCircle(if (m.danger) L.red else L.t1, 5.dp.toPx(), Offset(x0 + span * m.frac, y)) }
            drawCircle(L.t1, r, Offset(at, y))
        }
        Column(Modifier.padding(start = L.px(12)), horizontalAlignment = Alignment.End) {
            Text(p.pos, style = L.foot, color = L.t1, maxLines = 1)
            if (p.note != null) Text(p.note, style = L.foot, color = L.t2, maxLines = 1)
        }
        Box(Modifier.size(L.TOUCH).semantics { contentDescription = p.rateLabel }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions.onPlaySpeed), contentAlignment = Alignment.Center) {
            Box(Modifier.height(L.px(40)).width(L.px(58)).border(1.dp, L.t2, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Text(p.rate, style = L.foot.copy(fontWeight = FontWeight.Bold), color = L.t1, maxLines = 1)
            }
        }
        // ✕ 시연 끝내기(11-14 사용자 "시연 영상 취소하는게 없네"): 막대 오른쪽 끝, 닿는 크기 96
        Box(Modifier.size(L.TOUCH).semantics { contentDescription = p.stopLabel }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = debounced(actions.onPlayStop)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(L.px(20))) {
                val w = size.width; val sw = w * 0.16f
                drawLine(L.t1, Offset(0f, 0f), Offset(w, w), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(L.t1, Offset(w, 0f), Offset(0f, w), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/** 오른쪽 아래 응급 유리 알약: 빨간 십자 + 흰 글. 닿는 높이 96 */
@Composable
private fun SosPill(label: String, onClick: () -> Unit, modifier: Modifier) {
    Touch(onClick, modifier) {
        Row(Modifier.height(L.sosH).glass(RoundedCornerShape(L.sosH / 2)).padding(start = L.px(18), end = L.px(22)), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(L.px(20))) {
                val k = size.width / 24f
                val cross = Path().apply { moveTo(9f * k, 3f * k); lineTo(15f * k, 3f * k); lineTo(15f * k, 9f * k); lineTo(21f * k, 9f * k); lineTo(21f * k, 15f * k); lineTo(15f * k, 15f * k)
                    lineTo(15f * k, 21f * k); lineTo(9f * k, 21f * k); lineTo(9f * k, 15f * k); lineTo(3f * k, 15f * k); lineTo(3f * k, 9f * k); lineTo(9f * k, 9f * k); close() }
                drawPath(cross, L.red)
            }
            Spacer(Modifier.width(L.px(9)))
            Text(label, style = L.sos, color = L.t1)
        }
    }
}
