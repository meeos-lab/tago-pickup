package ai.meeos.tago.ui.components

import ai.meeos.tago.R
import ai.meeos.tago.ui.CardTone
import ai.meeos.tago.ui.CardUi
import ai.meeos.tago.ui.MemberUi
import ai.meeos.tago.ui.QuestionUi
import ai.meeos.tago.ui.RegionUi
import ai.meeos.tago.ui.VoiceUi
import ai.meeos.tago.ui.theme.Dim
import ai.meeos.tago.ui.theme.Tago
import ai.meeos.tago.ui.theme.TagoType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalSoundEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoundEffect
import androidx.compose.runtime.CompositionLocalProvider
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.withTimeoutOrNull

/*
 * 컴포넌트 — docs/ui-design.md §5. Pleos 순정 설정 UI 문법: 직각, 1px 회색 테두리, 선택·주 동작은 검정 채움 + 흰 글자, 섹션은 얇은 구분선.
 * 모든 글은 UiState 로 받은 완성 문자열(지역 언어)이다. 눌림 95% 축소(80ms)는 Pleos 기본 버튼의 shrink effect. 그림자는 쓰지 않는다.
 */

enum class ButtonVariant { PRIMARY, SECONDARY, DANGER, DANGER_OUTLINE }

/**
 * 누름 피드백(소리 + 햅틱). Compose foundation(1.12)의 clickable 은 누를 때마다 LocalSoundEffect.playClickSound() 를 부르고,
 * 기본 구현이 View.playSoundEffect(CLICK) = 시스템 키 클릭(AudioManager FX_KEY_CLICK)을 낸다. 시스템 '터치 시 소리' 설정이
 * 꺼져 있으면 AudioManager 가 내지 않는다. 그래서 소리를 따로 내지 않고(직접 내면 한 번 누름에 딸깍이 두 번 — 11-08 에뮬레이터
 * 오디오 서버 트랙 수로 확인) 그 자리를 감싼다. 음원 파일은 넣지 않는다(순정 톤, 권리 문제 없음).
 * - 햅틱: 같은 자리에서 performHapticFeedback(VIRTUAL_KEY) — 시스템 '터치 시 진동' 설정을 따른다. 진동기가 없는 차에서는 아무 일도 없다.
 * - 결정(11-08): 앱 안내 음성(TTS)이 나오는 동안에는 소리를 생략하고 햅틱만 낸다. 위험·경유·합류 안내 문장의 첫 음절을
 *   딸깍 소리가 가리지 않게 하려는 것. 눌림 축소(80 ms)와 햅틱이 남아 피드백은 여전히 둘 이상이다.
 *   Gleo·내비 같은 다른 앱 음성은 읽지 않는다(재생 중 판단에 다른 앱 재생 목록을 뒤지지 않음).
 * - 범위: clickable 을 쓰는 모든 요소(버튼·답 칩·가족 목록·세그먼트·충전 설문 타일). 꺼진 버튼과 빈 곳은 Compose 가 부르지 않는다.
 *   연타 막기(debounced)로 버린 두 번째 누름에도 Compose 는 소리를 부른다 — 눌림을 받았다는 표시일 뿐, 동작은 한 번이다.
 */
class PressFeedback(private val view: View, private val delegate: SoundEffect) : SoundEffect {
    override fun playClickSound() {
        val sound = !ai.meeos.tago.voice.SpeechState.speaking
        if (sound) delegate.playClickSound()
        val haptic = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // 시연·디버그 빌드에서만: 에뮬레이터 검증(오디오 서버 트랙·진동 기록과 대조)용 한 줄. 제품 빌드에는 없다
        if (ai.meeos.tago.BuildConfig.DEMO_HOOKS) android.util.Log.d(ai.meeos.tago.app.AppLog.TAG, "누름 피드백: 소리 ${if (sound) "냄" else "생략(안내 음성 중)"} · 햅틱 $haptic")
    }
}

/** 앱 맨 위(MainActivity setContent)에서 한 번 감싼다 — 그 아래 모든 clickable 의 클릭 소리 자리가 PressFeedback 이 된다 */
@Composable
fun PressFeedbackScope(content: @Composable () -> Unit) {
    val view = LocalView.current
    val base = LocalSoundEffect.current
    val feedback = remember(view, base) { PressFeedback(view, base) }
    CompositionLocalProvider(LocalSoundEffect provides feedback, content = content)
}

/** 연타 막기: 0.8초 안의 두 번째 누름은 버린다(흔들리는 차에서 두 번 눌려 요청·안내가 두 번 가지 않게) */
@Composable
private fun debounced(onClick: () -> Unit): () -> Unit {
    val last = remember { longArrayOf(0L) }
    return {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last[0] >= 800L) { last[0] = now; onClick() }
    }
}
enum class ButtonSize(val height: Dp) { L(Dim.buttonL), M(Dim.buttonM) }

@Composable
fun TagoIcon(res: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(res), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}

/** 얇은 구분선(섹션 사이) */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Dim.line).background(Tago.colors.divider))
}

/** Pleos 기본 버튼(Large 100 / Medium 80), 직각. Primary(검정 채움) · Secondary(흰 면 + 테두리) · Danger(빨강 채움) · DangerOutline(흰 면 + 빨간 글) */
@Composable
fun TagoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    size: ButtonSize = ButtonSize.L,
    icon: Int? = null,
    alignStart: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Tago.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(80), label = "shrink")
    // 좁은 창(Connect-L 1274 의 600 패널에 버튼 둘)에서 글이 잘리면 좌우 패딩을 40 → 16 으로 줄여 글을 살린다(높이·간격은 그대로)
    var tight by remember(label) { mutableStateOf(false) }
    val (fill, fg, stroke) = when (variant) {
        ButtonVariant.PRIMARY -> Triple(if (pressed) c.ink.copy(alpha = 0.8f) else c.ink, c.onInk, null)
        ButtonVariant.SECONDARY -> Triple(if (pressed) c.surfaceAlt else c.surface, c.text, c.line)
        ButtonVariant.DANGER -> Triple(if (pressed) c.red.copy(alpha = 0.85f) else c.red, c.onInk, null)
        ButtonVariant.DANGER_OUTLINE -> Triple(if (pressed) c.surfaceAlt else c.surface, c.red, c.line)
    }
    Row(
        modifier = modifier
            .height(size.height)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .background(fill)
            .then(if (stroke != null) Modifier.border(Dim.line, stroke) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = debounced(onClick))
            .padding(horizontal = if (tight) Dim.gapS else Dim.buttonPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignStart) Arrangement.Start else Arrangement.Center,
    ) {
        if (icon != null) {
            TagoIcon(icon, fg, Dim.iconL)
            Spacer(Modifier.width(14.dp))
        }
        Text(label, style = TagoType.bodyL, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow && !tight) tight = true })
    }
}

/** 예/아니오 칩: Primary + Secondary 한 줄. 글은 운전자가 말할 답 그대로 */
@Composable
fun AnswerChips(q: QuestionUi, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // 두 답 사이는 24dp(주행 중 터치 간격 규칙) — 빗나가면 반대 답이 바로 확정된다
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.gap)) {
        TagoButton(q.yes, { onAnswer(true) }, Modifier.weight(1f), ButtonVariant.PRIMARY)
        TagoButton(q.no, { onAnswer(false) }, Modifier.weight(1f), ButtonVariant.SECONDARY)
    }
}

/** 브랜드 + 음성 상태. 시연·디버그 빌드에서만 브랜드 글자를 2.5초 누르면 개발 패널(Pleos 길게 누르기 2~3초). 제품 빌드에는 없다 */
@Composable
fun StatusHeader(brand: String, voice: VoiceUi, voiceText: String?, onBrandLongPress: () -> Unit) {
    val c = Tago.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(brand, style = TagoType.labelMBold, color = c.text,
            modifier = if (!ai.meeos.tago.BuildConfig.DEMO_HOOKS) Modifier else Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = { if (withTimeoutOrNull(2500) { tryAwaitRelease() } == null) onBrandLongPress() })
            })
        Spacer(Modifier.weight(1f))
        if (voiceText != null) VoiceChip(voice, voiceText)
    }
}

/** 음성 상태: 듣는 중(본문색·마이크) / 안내 중(보조색·말풍선). 면 없이 글과 아이콘만(순정 UI 문법). Gleo Chrome(빛 효과)은 흉내 내지 않는다 */
@Composable
fun VoiceChip(state: VoiceUi, text: String) {
    val c = Tago.colors
    val fg = if (state == VoiceUi.LISTENING) c.text else c.text2
    Row(Modifier.widthIn(max = 400.dp), verticalAlignment = Alignment.CenterVertically) {
        TagoIcon(if (state == VoiceUi.SPEAKING) R.drawable.ic_message else R.drawable.ic_mic, fg, Dim.iconS)
        Spacer(Modifier.width(10.dp))
        Text(text, style = TagoType.labelM, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 진행 단계: 점 14 + 선 4px. 완료 검정, 현재 검정 테두리 + 굵은 글자, 예정 회색 */
@Composable
fun Stepper(labels: List<String>, done: Int, modifier: Modifier = Modifier) {
    val c = Tago.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            val isDone = i < done
            val isNow = i == done
            if (i > 0) Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(4.dp).background(if (isDone) c.ink else c.line))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (isDone) c.ink else c.line).then(if (isNow) Modifier.border(3.dp, c.ink, CircleShape) else Modifier))
                Spacer(Modifier.width(8.dp))
                Text(label, style = if (isDone || isNow) TagoType.labelMBold else TagoType.labelM, color = if (isDone || isNow) c.text else c.text2, maxLines = 1)
            }
        }
    }
}

/** 상태 카드: 직각, 1px 테두리(Safety 빨강 2px · Warning 주황 2px · Done 초록 2px · 그 밖 회색), 제목(아이콘 + Title M) + 줄들(아이콘 32 + Body M) */
@Composable
fun InfoCard(card: CardUi, modifier: Modifier = Modifier, content: (@Composable () -> Unit)? = null) {
    val c = Tago.colors
    val (stroke, strokeWidth, accent) = when (card.tone) {
        CardTone.NEUTRAL -> Triple(c.line, Dim.line, c.text2)
        CardTone.INFO -> Triple(c.ink, Dim.line, c.text)
        CardTone.SAFETY -> Triple(c.red, Dim.lineStrong, c.red)
        CardTone.WARNING -> Triple(c.orange, Dim.lineStrong, c.orange)
        CardTone.DONE -> Triple(c.green, Dim.lineStrong, c.green)
    }
    Column(
        modifier.fillMaxWidth().background(c.surface).border(strokeWidth, stroke).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (card.title != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.titleIcon != null) { TagoIcon(card.titleIcon, accent, Dim.iconL); Spacer(Modifier.width(12.dp)) }
            Text(card.title, style = TagoType.titleM, color = if (card.tone == CardTone.SAFETY) c.red else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        card.lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (line.icon != null) { TagoIcon(line.icon, accent, Dim.iconM); Spacer(Modifier.width(12.dp)) }
                Text(line.text, style = TagoType.bodyM, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        content?.invoke()
    }
}

/** 아이가 보낸 마지막 메시지 하나 */
@Composable
fun MessageBubble(text: String, modifier: Modifier = Modifier) {
    val c = Tago.colors
    Row(modifier.border(Dim.line, c.line).padding(horizontal = 28.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        TagoIcon(R.drawable.ic_message, c.text2, Dim.iconM)
        Spacer(Modifier.width(12.dp))
        Text(text, style = TagoType.bodyM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 가족 목록(순정 설정의 사이드 목록 문법): 아이콘 + 이름 + 관계, 줄 사이 얇은 구분선, 누르면 검정 채움 */
@Composable
fun MemberList(members: List<MemberUi>, onClick: (MemberUi) -> Unit) {
    val c = Tago.colors
    Column(Modifier.fillMaxWidth()) {
        Divider()
        members.forEach { m ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val fg = if (pressed) c.onInk else c.text
            Row(
                Modifier.fillMaxWidth().height(Dim.buttonL).background(if (pressed) c.ink else c.surface)
                    .clickable(interactionSource = interaction, indication = null, onClick = debounced { onClick(m) }).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagoIcon(m.icon, fg, 40.dp)
                Spacer(Modifier.width(24.dp))
                Text(m.name, style = TagoType.bodyLBold, color = fg, maxLines = 1)
                Spacer(Modifier.width(16.dp))
                Text(m.relation, style = TagoType.labelM, color = if (pressed) c.onInk else c.text2, maxLines = 1)
            }
            Divider()
        }
    }
}

/** 세그먼트(순정 설정과 같은 모양): 한 줄 꽉 차게, 1px 테두리, 선택 칸은 검정 채움 + 흰 글자 */
@Composable
fun SegmentedControl(items: List<RegionUi>, selectedId: String, onSelect: (String) -> Unit, height: Dp = Dim.buttonM) {
    val c = Tago.colors
    Row(Modifier.fillMaxWidth().height(height).border(Dim.line, c.line)) {
        items.forEachIndexed { i, r ->
            val on = r.id == selectedId
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            if (i > 0) Box(Modifier.width(Dim.line).fillMaxHeight().background(c.line))
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (on) c.ink else if (pressed) c.surfaceAlt else c.surface)
                    .clickable(interactionSource = interaction, indication = null, onClick = debounced { onSelect(r.id) }),
                contentAlignment = Alignment.Center,
            ) { Text(r.label, style = TagoType.bodyM, color = if (on) c.onInk else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** 섹션 제목(순정 설정의 "차일드락" 같은 소제목) */
@Composable
fun SectionTitle(text: String) {
    Text(text, style = TagoType.titleS, color = Tago.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 토스트: 하단 중앙, 최대 폭 792, 패딩 40/24, 검정 면 위 흰 글(OS 토스트 톤). 3초 */
@Composable
fun TagoToast(text: String, modifier: Modifier = Modifier) {
    val c = Tago.colors
    Box(modifier.widthIn(max = Dim.toastMaxWidth).clip(RoundedCornerShape(Dim.radiusToast)).background(c.ink).padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text(text, style = TagoType.bodyM, color = c.onInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 개발 패널(브랜드 길게 누르면): 차 신호 + 최근 로그. 주행 화면 글자 규칙(≥26) 밖이라 개발용으로만 */
@Composable
fun DevPanel(status: String?, lines: List<String>) {
    val c = Tago.colors
    Column(Modifier.fillMaxWidth().background(c.surfaceAlt).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status != null) Text(status, style = TagoType.labelS, color = c.text, maxLines = 2)
        lines.forEach { Text(it, style = TagoType.labelS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
