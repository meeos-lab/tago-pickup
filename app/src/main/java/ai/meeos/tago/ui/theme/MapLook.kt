package ai.meeos.tago.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 지도 우선 화면(차대차 판)의 겉모습 값 전부 — 사용자 확정 시안 A4(11-13, "앱화면 시안느낌으로 구현해봐").
 * 원본 수치: reports/app_design_20261113/A4_source.html(앱 영역 1706×1210 px). 1dp = 1px(Connect-S-17) 이라 px 값을 그대로 dp·sp 로 옮겼다.
 * 겉모습을 바꿀 때는 이 파일만 고친다. 크기는 [SCALE] 하나로 판 전체가 같은 비율로 커지고 작아진다(누르는 높이 [TOUCH] 는 비율과 상관없이 96 이상).
 */
object MapLook {
    /**
     * 크기 비율. 가장 작은 글(정차 중 "방금 경고가 틀렸어요" 17)이 실제 화면에서 3 mm 아래로 내려가지 않게 정했다:
     * 17″ 2560×1440 = 1 px 0.147 mm(docs/dev/89 1-3절) → 3 mm = 20.4 px. 비율 1 로 찍은 에뮬레이터 캡처에서 그 글의 한글 잉크 높이가 16 px(2.35 mm, 글자 크기의 0.94)
     * 이었으므로 20.4 / 16 = 1.275 → 1.28. 이때 17 은 21.8 → 잉크 약 20.5 px = 3.0 mm(ui-design §6b). 시안 그대로 보려면 1.0.
     */
    const val SCALE = 1.28f

    fun px(v: Number): Dp = (v.toFloat() * SCALE).dp
    fun sp(v: Number): TextUnit = (v.toFloat() * SCALE).sp

    /** 누르는 곳의 닿는 높이(보이는 크기가 작아도) */
    val TOUCH = 96.dp

    // ---- 색(A4 :root)
    val red = Color(0xFFFF453A)
    val red2 = Color(0xFFD92D20)
    val redLight = Color(0xFFFF6A5E)
    val amber = Color(0xFFFF9F0A)
    val amberLight = Color(0xFFFFC04D)
    val amber2 = Color(0xFFD97B00)
    val green = Color(0xFF30D158)
    val greenLight = Color(0xFF5BE07F)
    val green2 = Color(0xFF1FA346)
    val gray = Color(0xFF8E97A6)
    /** 유리 판: 시안은 rgba(22,24,30,.74) + 뒤 흐림 26. 이 화면의 뒤는 WebView 지도라 Compose 가 뒤를 흐릴 수 없어 불투명도만 .88 로 올렸다 */
    val glass = Color(0xE016181E)
    val hair = Color(0x1AFFFFFF)          // 가는 밝은 테두리 .10
    val rowLine = Color(0x0FFFFFFF)       // 목록 줄 사이 .06
    val t1 = Color.White
    val t2 = Color(0xA8FFFFFF)            // .66
    val t3 = Color(0x6BFFFFFF)            // .42
    val chip = Color(0x1FFFFFFF)          // 판 안 알약 .12
    val track = Color(0x24FFFFFF)         // 진행 선 바탕 .14
    val shadow = Color(0xFF0A0E16)
    val goBg = Color.White                // 판 아래 흰 알약
    val goText = Color(0xFF111418)
    val alertBorder = Color(0x8CFF635A)   // 긴급차량 판 붉은 테두리 .55
    // ---- 첫 화면 위 줄 밝은 알약(11-15 사용자 "라운드 형태가 있는 알약으로" · "밝은 테마로"): 상태 칸·검색 칸·설정 단추
    val pillBg = Color(0xF5FFFFFF)        // 흰 바탕 .96(지도 위에서도 또렷하게)
    val pillText = Color(0xFF111418)
    val pillText2 = Color(0x99111418)     // .60
    val pillHair = Color(0x1A111418)      // 가는 어두운 테두리 .10
    val pillChip = Color(0x0F111418)      // 알약 안 작은 원(마이크) .06
    val pillShadow = Color(0x40000000)
    val recRed = Color(0xFFE5342B)        // "녹음 중" 점
    // ---- 앱 정체성 색(앱 아이콘): 듣는 구슬(ListenOrb, 11-15 "아이덴티티가없음")
    val gbDeep = Color(0xFF01372A)        // 아이콘 바탕 짙은 초록
    val gbGlass = Color(0xFF0C5A44)       // 빛 받는 쪽 초록
    val gbMint = Color(0xFF8FD3A8)        // 아이콘 차선 민트
    val gbAmber = Color(0xFFF5A623)       // 아이콘 두 차를 잇는 주황 빛 띠(차대차 링크)

    // ---- 긴급차량 화면 아래 오로라 빛(11-13 사용자 "아래 그라데이션 있는거 오로라 느낌으로", 시안 A5 .edge·.rays). 이 빛에만 쓰는 색
    /** 빛 덩어리: 색(알파 포함) · 가로 중심(화면 폭 비율) · 가로 반지름(폭 비율) · 세로 반지름(띠 높이 비율) · 좌우 흐름 주기(초) · 높이 숨쉬기 주기(초) */
    data class AuroraBlob(val color: Color, val cx: Float, val rx: Float, val ry: Float, val driftS: Float, val breathS: Float)
    val auroraBlobs = listOf(
        AuroraBlob(Color(0xE6FF2D55), .12f, .58f, .95f, 7.4f, 6.2f),   // 붉은 분홍 .90
        AuroraBlob(Color(0xCCFF8038), .38f, .46f, .78f, 6.6f, 7.8f),   // 주황 .80
        AuroraBlob(Color(0xD9FF3896), .64f, .52f, 1.05f, 8.0f, 6.8f),  // 자홍 .85
        AuroraBlob(Color(0x9EB054FF), .90f, .44f, .80f, 7.0f, 7.2f),   // 보라 .62(가장 약하게)
    )
    val auroraH = 240.dp                  // 띠 높이(앱 영역 1210 기준). 380 → 240(11-15 사용자 "화면을 너무 가리지 않았으면 좋겠음" — 움직임을 키우면서 높이는 줄임)
    val auroraRay = Color(0x29FFFFFF)     // 세로 빛줄기 .16
    const val auroraNightGain = 1.15f     // 밤에는 조금 더 진하게
    const val auroraNearM = 300f          // 이 거리부터 가장 진하고 높다
    const val auroraFarM = 2000f          // 이 거리에서 가장 옅고 낮다
    // 음성이 듣는 동안(말로 검색 등)의 오로라(11-14, 사용자 결정 "말할 때 파랑·보라·분홍, 들을 때는 더 낮고 차분하게"): 긴급차량 빛보다 낮고 옅고 느리게
    // 11-15 사용자 "아래 깔린 오로라랑 따로놀아"(듣는 구슬은 앱 아이콘 색) → 듣는 오로라도 앱 색: 민트 초록 · 주황(링크 빛 띠) · 민트
    val auroraListenBlobs = listOf(
        AuroraBlob(Color(0x8C2FB98A), .16f, .56f, .90f, 11.0f, 9.0f),   // 민트 초록 .55
        AuroraBlob(Color(0x80F5A623), .50f, .50f, 1.0f, 12.5f, 10.0f),  // 주황 .50
        AuroraBlob(Color(0x738FD3A8), .84f, .46f, .85f, 10.5f, 9.6f),   // 민트 .45
    )
    val auroraListenH = 210.dp            // 긴급차량(380)보다 낮게
    val auroraListenRay = Color(0x14FFFFFF)   // 빛줄기 .08
    // 주행 중 왼쪽 주변 차 판(11-14, 순정 주행 화면 문법): 폭(앱 영역 1706 가운데 약 1/3, 좁은 창에서는 폭의 38 % 까지) · 지도와의 경계선
    val aroundW = 580.dp
    const val aroundMaxFrac = 0.38f
    val aroundEdge = Color(0x1F000000)

    // ---- 치수(px = A4 CSS 값)
    val margin get() = px(28)
    val isleW get() = px(452)
    val isleRadius get() = px(26)
    val pillH get() = px(54)
    val icon get() = px(58)
    val goH get() = px(62)
    val sosH get() = px(60)
    val chipH get() = px(54)
    val gapBelowIsle get() = px(14)

    // ---- 글자(A4 CSS 크기·굵기)
    private val base = TextStyle(fontFamily = PleosFont, lineBreak = LineBreak.Heading)
    val big get() = base.copy(fontSize = sp(50), lineHeight = sp(52), fontWeight = FontWeight.ExtraBold, letterSpacing = sp(-1.5))
    val bigUnit get() = base.copy(fontSize = sp(26), lineHeight = sp(30), fontWeight = FontWeight.SemiBold)
    val what get() = base.copy(fontSize = sp(23), lineHeight = sp(30), fontWeight = FontWeight.SemiBold)
    val pill get() = base.copy(fontSize = sp(21), lineHeight = sp(28), fontWeight = FontWeight.SemiBold)
    val go get() = base.copy(fontSize = sp(23), lineHeight = sp(30), fontWeight = FontWeight.Bold)
    val sos get() = base.copy(fontSize = sp(21), lineHeight = sp(28), fontWeight = FontWeight.SemiBold)
    val row get() = base.copy(fontSize = sp(21), lineHeight = sp(28))
    val chipText get() = base.copy(fontSize = sp(20), lineHeight = sp(26), fontWeight = FontWeight.SemiBold)
    val foot get() = base.copy(fontSize = sp(17), lineHeight = sp(24))
    val hero get() = base.copy(fontSize = sp(30), lineHeight = sp(40), fontWeight = FontWeight.Bold, letterSpacing = sp(-0.5))
    val head get() = base.copy(fontSize = sp(26), lineHeight = sp(34), fontWeight = FontWeight.Bold)
}
