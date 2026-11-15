package ai.meeos.tago.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 디자인 토큰 — docs/ui-design.md §2·§3. Pleos Connect 순정 설정 UI와 같은 모습: 흰 바탕·검정 글자·검정 채움(선택/주 동작)·
 * 직각 모서리·얇은 회색 테두리. 색은 뜻이 있는 곳(위험 빨강·주의 주황·원활 초록·비 파랑)에만 쓴다. Light/Dark 는 시스템 모드를 따른다.
 */
data class TagoColors(
    val bg: Color, val surface: Color, val surfaceAlt: Color, val line: Color, val divider: Color,
    val text: Color, val text2: Color, val text3: Color,
    val ink: Color, val onInk: Color,
    val blue: Color, val red: Color, val orange: Color, val green: Color, val yellow: Color, val purple: Color,
    val mapBg: Color,
    /** 누름 물결(순정 설정 목록처럼 눌린 곳에서 퍼지는 회색): 바탕 위 / 채움(검정·빨강) 위 */
    val ripple: Color, val rippleOnFill: Color,
)

val LightColors = TagoColors(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFF2F2F2), line = Color(0xFFD4D4D4), divider = Color(0xFFE6E6E6),
    text = Color(0xFF000000), text2 = Color(0xFF6E6E6E), text3 = Color(0xFFB5B5B5),
    ink = Color(0xFF000000), onInk = Color(0xFFFFFFFF),
    blue = Color(0xFF0064FF), red = Color(0xFFF62E24), orange = Color(0xFFFF8A00), green = Color(0xFF02C265), yellow = Color(0xFFFFC224), purple = Color(0xFF5A46FA),
    mapBg = Color(0xFFF2F2F2),
    ripple = Color(0xFF3A3A3A), rippleOnFill = Color(0xFFFFFFFF),   // 라이트: 어두운 회색 물결(채움 위는 흰색)
)

/** 순정 다크 테마: 짙은 회색 바탕, 흰 글자, 선택·주 동작은 밝은 회색 채움 + 검정 글자 */
val DarkColors = TagoColors(
    bg = Color(0xFF1C1C1C), surface = Color(0xFF1C1C1C), surfaceAlt = Color(0xFF2A2A2A), line = Color(0xFF3A3A3A), divider = Color(0xFF303030),
    text = Color(0xFFF2F2F2), text2 = Color(0xFF9E9E9E), text3 = Color(0xFF5A5A5A),
    ink = Color(0xFFE8E8E8), onInk = Color(0xFF111111),
    blue = Color(0xFF0082FF), red = Color(0xFFFF4339), orange = Color(0xFFFF8A00), green = Color(0xFF00E074), yellow = Color(0xFFFFC224), purple = Color(0xFF6A57FF),
    mapBg = Color(0xFF242424),
    ripple = Color(0xFFD0D0D0), rippleOnFill = Color(0xFF111111),   // 다크: 밝은 회색 물결(밝은 채움 위는 검정)
)

/**
 * Pleos Font Hierarchy(크기/행간). 시스템 폰트(Pleos 차량은 Asta Sans).
 * 줄바꿈은 어절 단위(WordBreak.Phrase): 기본값은 한글을 음절마다 끊어 좁은 창(628·672dp)에서 "주세/요"처럼 끝 글자만 넘어간다
 */
/**
 * Pleos 순정 글꼴(Asta Sans). Pleos 에뮬레이터 시스템 이미지의 /product/etc/fonts_customization.xml(11-12 system.img 확인)이
 * 이름 붙은 가족 "pretendard"(AstaSansOTF Light·Regular·Medium·Bold·ExtraBold)를 더하고, **기본 sans-serif 도 같은 AstaSans 로 지정한다**.
 * 그래서 이 명시 지정이 없어도 화면은 같다(에뮬 5554 실측: 제목 424↔425 px, 부제 332↔332 px) — 실차 이미지가 기본 글꼴을 다르게 둘 경우를 대비한 보험이다.
 * 글꼴 파일은 앱에 넣지 않고 이름만 부른다. 이름이 없는 기기(일반 안드로이드)에서는 Compose 가 목록의 다음 항목인 sans-serif 로 그린다
 * (없는 이름으로 시험해 같은 폭으로 그려짐을 확인). 실차에서도 같은 이름인지는 확인할 길이 없다(미확인).
 */
val PleosFont = FontFamily(
    Font(DeviceFontFamilyName("pretendard"), FontWeight.Light),
    Font(DeviceFontFamilyName("pretendard"), FontWeight.Normal),
    Font(DeviceFontFamilyName("pretendard"), FontWeight.Medium),
    Font(DeviceFontFamilyName("pretendard"), FontWeight.Bold),
    Font(DeviceFontFamilyName("pretendard"), FontWeight.ExtraBold),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold),
)

object TagoType {
    private val base = TextStyle(fontFamily = PleosFont, lineBreak = LineBreak.Heading)   // Balanced + Loose + Phrase
    /** 경고 화면·첫 화면의 세 단계(11-13 디자인 손질): 큰 숫자·첫 문장 display / 제목 titleM / 본문 bodyM(+Bold) */
    val display = base.copy(fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold)
    val headlineS = base.copy(fontSize = 48.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold)
    val titleL = base.copy(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val titleM = base.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val titleS = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val bodyL = base.copy(fontSize = 30.sp, lineHeight = 38.sp)
    val bodyLBold = base.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
    val bodyM = base.copy(fontSize = 28.sp, lineHeight = 36.sp)
    val bodyMBold = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    val bodyS = base.copy(fontSize = 26.sp, lineHeight = 34.sp)
    val bodySBold = base.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val labelM = base.copy(fontSize = 24.sp, lineHeight = 28.sp)
    val labelMBold = base.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val labelS = base.copy(fontSize = 20.sp, lineHeight = 24.sp)
}

/** 크기 토큰(1dp = 1px on Connect-S-17). 다른 Pleos 디스플레이도 dp 로 같다(앱 창 가로 1256~1700, 세로 628~840 — research_notes/pleos-ui-design-guide.md) */
object Dim {
    val panelWidth = 680.dp          // 가로 배치의 상태 패널 최대 폭(순정 설정의 사이드 영역처럼 왼쪽). Connect-S-17 앱 창 1700 의 40%
    val panelWidthMin = 600.dp       // 좁은 가로 창(Connect-L 1274·WF 1256·S-14.6 1360)에서도 지도가 패널보다 넓게: 창 폭의 40%, 이 값 아래로는 줄이지 않는다
    const val panelRatio = 0.4f
    val panelPadH = 48.dp
    val panelPadTop = 40.dp
    val panelPadBottom = 32.dp
    val mapPad = 24.dp
    val section = 40.dp              // 섹션 사이(제목 → 설명 → 조작의 묶음 사이)
    val gap = 24.dp
    val gapS = 16.dp
    val buttonL = 100.dp
    val buttonM = 80.dp
    val buttonPadH = 40.dp
    val radiusToast = 8.dp
    val line = 1.dp
    val lineStrong = 2.dp
    val iconL = 36.dp
    val iconM = 32.dp
    val iconS = 28.dp
    val toastMaxWidth = 792.dp
    val portraitBreakpoint = 1200.dp // 이보다 좁으면 세로 배치(내비와 분할)
    const val portraitPanelMax = 0.74f // 세로 배치에서 상태 영역이 차지할 수 있는 최대 높이 비율(나머지가 지도). 실측 841×1210: 압축 스트립 + 버튼까지 들어가는 값
}

val LocalTagoColors = staticCompositionLocalOf { LightColors }

/**
 * 누름 반응 = 순정 설정과 같은 물결(0.15 s 에 퍼지고 0.65 s 에 줄 전체가 옅은 회색 면, 11-11 순정 관찰). 알파는 낮게(눌림 .16·포커스 .16·호버 .08).
 * 우리 면은 직각이라 bounded 물결이 직사각형 안에서만 퍼진다. 소리·햅틱은 PressFeedback 이 따로 낸다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalTagoColors provides colors,
        LocalTextStyle provides TextStyle(fontFamily = PleosFont),   // 스타일을 안 준 Text 도 같은 글꼴
        LocalRippleConfiguration provides RippleConfiguration(color = colors.ripple, rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.16f, hoveredAlpha = 0.08f, pressedAlpha = 0.16f)),
        content = content,
    )
}

object Tago {
    val colors: TagoColors
        @Composable get() = LocalTagoColors.current
}
