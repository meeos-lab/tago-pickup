package ai.meeos.tago.ui

/**
 * 화면이 그릴 것 전부. 컨트롤러가 상태가 바뀔 때마다 새로 만들고(AppController.render), 화면은 이것만 본다.
 * 글은 이미 지역 언어로 완성된 문자열이다(컴포저블 안에서 stringResource 를 쓰지 않는다 — 지역 언어가 시스템 언어와 다르다).
 */
enum class VoiceUi { IDLE, LISTENING, SPEAKING }

/** 예/아니오 칩. 글은 운전자가 말할 답 그대로 */
data class QuestionUi(val yes: String, val no: String)

enum class CardTone { NEUTRAL, INFO, SAFETY, WARNING, DONE }

data class CardLine(val icon: Int?, val text: String)

data class CardUi(val tone: CardTone, val title: String?, val titleIcon: Int?, val lines: List<CardLine>)

enum class BinColor { DANGER, PURPLE, RAIN, UNKNOWN, SLOW, MEDIUM, FREE }

/** 앞길 스트립의 표식: 내 차 · 위험(지금 안내 중인 것은 HAZARD_TOP) · 뒤에서 오는 긴급차량 · 일행 · 다음 휴게소 */
enum class MarkerKind { ME, HAZARD, HAZARD_TOP, SIREN, MEMBER, REST }

data class StripMarker(val distM: Int, val kind: MarkerKind, val icon: Int, val distLabel: String, val label: String, val meta: String?)

/** 앞길 세로 스트립: 앞 aheadM(10km)·뒤 behindM(2km)의 거리 축, 500m 칸 색(앞차 평균 속도·비·위험), 표식들 */
data class RoadStripUi(val aheadM: Int, val behindM: Int, val bins: List<BinColor>, val markers: List<StripMarker>, val aheadLabel: String)

data class MemberUi(val name: String, val relation: String, val icon: Int, val type: String)

data class RegionUi(val id: String, val label: String)

sealed interface Panel {
    /** 대기: 함께 가기(있으면) + 가족 타일 */
    data class Idle(val roadLinkLabel: String?, val members: List<MemberUi>) : Panel

    /** 픽업 진행: 단계(0 = 아직, 1 링크, 2 만날 곳, 3 도착, 4 탑승), 아이 메시지, 내비 버튼, 상태 카드(재약속·주문) */
    data class Pickup(val step: Int, val stepLabels: List<String>, val message: String?, val naviLabel: String?, val card: CardUi?, val members: List<MemberUi>) : Panel

    /** 함께 가기: 앞길 스트립(내 차·위험·일행·휴게소) + [가족 차 묶기 / 다같이 휴게소] [끝내기] */
    data class RoadLink(val strip: RoadStripUi?, val connecting: String?, val primaryLabel: String, val primaryIcon: Int, val endLabel: String, val showButtons: Boolean) : Panel

    /** 응급: Safety 카드 + [119 전화 화면] [응급 끝내기] */
    data class Emergency(val card: CardUi, val dialLabel: String, val endLabel: String) : Panel
}

data class TagoUiState(
    val headline: String,
    val subline: String,
    val voice: VoiceUi,
    val voiceText: String?,
    val question: QuestionUi?,
    val panel: Panel,
    val emergencyLabel: String?,          // 응급 버튼(응급 빌드에서, 응급 중이 아닐 때)
    val regions: List<RegionUi>?,         // 시연 지역 세그먼트(대기 화면에서만)
    val regionTitle: String?,             // 세그먼트 위 소제목("시연 지역")
    val regionId: String,
    val mapUrl: String?,                  // null 이면 빈 상태
    val mapEmptyText: String,
    val toast: String?,
    val devLog: List<String>?,            // 개발 패널(브랜드 글자를 길게 누르면)
    val devStatus: String?,
)
