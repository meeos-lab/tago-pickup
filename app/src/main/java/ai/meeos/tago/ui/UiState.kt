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

/** 주차 위치 한 줄("주차 B3 · C-12") + [고치기]. edit 가 있으면 고치는 중(한 층 위·아래·지우기·완료). 정차 중에만 보인다 */
data class ParkingUi(val line: String, val fixLabel: String, val edit: ParkingEditUi?, val card: ParkCardUi? = null,
                     val mates: String? = null)   // 함께 가는 일행의 주차 한 줄("일행 2대 주차함 · 가까이", 11-14) — 허브가 실어 준 일행의 주차 글을 센 값 그대로

/**
 * 주변 차 연결 한 줄(첫 화면 판, 11-14 "일반 주행 / 일행 나누기"): 주행하면 주변의 모르는 차들과 자동으로 연결된다는 상태 + 스위치.
 * text = "주행하면 주변 차와 자동으로 연결됩니다" 또는 "자동 연결 꺼짐 · 눌러서 켜기", on = 스위치(동의했고 자동 연결이 켜짐)
 */
data class LinkLineUi(val text: String, val on: Boolean,
                      val short: String = "")   // 최소 표시 첫 화면의 펼친 판에 쓰는 짧은 상태 글("연결 대기"·"꺼짐"·"동의 필요")

/** 첫 실행 한 번의 동의 한 장(주변 차 연결): 제목 · 보내는 것·안 보내는 것 줄 · [동의하고 켜기] [나중에] */
data class ConsentUi(val title: String, val lines: List<String>, val agreeLabel: String, val laterLabel: String, val moreLabel: String? = null)   // moreLabel: [자세히] → 고지 화면(11-15)

/** 지도 우선 화면(A4) 주차 판: 큰 글(층·기둥 또는 범위) · 근거 한 줄(실제 값만) · 사진으로 받았는지(작은 표시) */
data class ParkCardUi(val big: String, val evidence: String, val photo: Boolean)

/** 목적지(첫 화면, 정차 중): 고른 곳 한 줄 또는 검색 칸 + 결과 목록. 결과는 실제 장소 검색 값만 */
data class DestUi(val title: String, val chosen: String?, val chosenSub: String?, val open: Boolean, val hint: String, val searchLabel: String,
                  val clearLabel: String, val closeLabel: String, val openLabel: String, val busy: Boolean, val note: String?, val query: String,
                  val results: List<DestRow>, val prompt: String = "", val locked: Boolean = false, val lockNote: String = "",
                  // 말로 검색(11-14): 마이크 버튼 이름(읽어 주기용) · 듣는 중인지 · 듣는 중 칸에 보일 글(들린 말 또는 안내) — mic = null 이면 버튼 없음
                  val mic: String? = null, val listening: Boolean = false, val listenText: String? = null)

/**
 * 설정(11-14 사용자 "설정 버튼도 만드렁줘"): 첫 화면 판의 톱니로 연다(정차 P 에서만, 화면 안에서 여닫는다). 실제로 동작하는 것만 —
 * 내 실제 위치 쓰기(켬/끔) · 저장된 주차 위치 지우기(있을 때만) · 연구 기록(그 기능이 있는 빌드만) · 오픈소스 고지 · 앱 버전 한 줄
 */
data class SettingsUi(val title: String, val closeLabel: String, val locked: Boolean, val lockNote: String,
                      val realLocLabel: String, val realLocSub: String, val realLocOn: Boolean,
                      val parkClearLabel: String?, val parkClearBtn: String,
                      val studyLabel: String?, val studyState: String?,
                      val licensesLabel: String, val version: String,
                      // 주행 시 자동 연결(주변 차 연결, 11-14): 켬/끔 — 기본 켬, 첫 실행 동의 뒤에만 실제로 연결한다
                      val autoLinkLabel: String = "", val autoLinkSub: String = "", val autoLinkOn: Boolean = false,
                      val withdrawLabel: String? = null, val withdrawSub: String? = null, val withdrawBtn: String = "")   // 동의 거두기(11-15) — 동의했을 때만

/** 목적지 검색 결과 한 줄: onRoute = false 면 회색 + '이 시연 경로 밖'(고를 수 없다, 11-14) */
data class DestRow(val name: String, val sub: String, val onRoute: Boolean = true)

/**
 * 주행 중 왼쪽 판의 "주변 차 화면"(11-14 사용자: 순정 주행 화면 캡처 + "주행할때 이런 화면으로 나오게 만들어줘"): 맨 위 속도, 뒤 위에서 본 도로, 내 차,
 * 그리고 앱이 실제로 아는 차만 — 함께 가는 차(member) · 차 사고·급정지·길 막힘(hazard = 비상등 켠 차) · 그 밖의 위험(sign = 삼각 표지) · 뒤에서 오는 긴급차량(ev).
 * 차로는 허브가 모른다(모두 내 차로 앞·뒤), 센서로 본 일반 주변 차는 없다. 그리기 = server/link/around.html(ui/components/AroundView.kt 가 값을 넘긴다)
 */
data class AroundUi(val kmh: Int, val unit: String, val cars: List<AroundCar>)

/** d = 실제 거리(m, 앞 + · 뒤 −), name = 이름표(없으면 ""), dist = 거리 글("420 m" 등), tone = "danger"|"caution" */
data class AroundCar(val id: String, val kind: String, val d: Int, val name: String, val dist: String, val tone: String = "danger")

data class ParkingEditUi(val upLabel: String, val downLabel: String, val clearLabel: String, val doneLabel: String)

enum class BinColor { DANGER, PURPLE, RAIN, UNKNOWN, SLOW, MEDIUM, FREE }

/** 앞길 스트립의 표식: 내 차 · 위험(지금 안내 중인 것은 HAZARD_TOP) · 뒤에서 오는 긴급차량 · 일행 · 다음 휴게소 */
enum class MarkerKind { ME, HAZARD, HAZARD_TOP, SIREN, MEMBER, REST }

/** sign: 위험·휴게소의 주의 표지 그림(색 그대로, 지도 표지판과 같은 그림). 없으면 icon 을 원 안에 칠한다.
 *  tone: 정차 중 목록의 점 색(위험 빨강·주의 주황, null = 일행 초록) — 지도 우선 화면만 쓴다 */
data class StripMarker(val distM: Int, val kind: MarkerKind, val icon: Int, val distLabel: String, val label: String, val meta: String?, val sign: Int? = null,
                       val tone: AlertTone? = null)

/** 앞길 세로 스트립: 앞 aheadM(10km)·뒤 behindM(2km)의 거리 축, 500m 칸 색(앞차 평균 속도·비·위험), 표식들 */
data class RoadStripUi(val aheadM: Int, val behindM: Int, val bins: List<BinColor>, val markers: List<StripMarker>, val aheadLabel: String)

/** 경고 화면 머리의 색 뜻: 위험(빨강 숫자) · 주의(정체 꼬리) · 긴급차량(검은 배너가 따로 뜬다) */
enum class AlertTone { DANGER, CAUTION, SIREN }

/** 경고 화면 머리(11-13 디자인 손질): 가장 큰 거리 숫자(lead + num + unit, 예 "후방 1.1 km") / 제목 한 줄 / 본문 한 줄. 헤드라인 문구와 뜻이 같다 */
data class AlertUi(val lead: String?, val num: String, val unit: String, val title: String, val body: String, val tone: AlertTone,
                   // 지도 우선 화면(11-13 시안 A)만 쓰는 값 — 판 머리 한 줄(eyebrow), 위험 표지 그림(sign), 숫자 뒤 꼬리("뒤"), 아래 한 줄(note), 내 차 → 위험 막대(bar)
                   val eyebrow: String? = null, val sign: Int? = null, val trail: String? = null, val note: String? = null, val bar: BarUi? = null,
                   val what: String? = null,
                   val distM: Int? = null)   // 긴급차량까지 거리(m, 양수) — 화면 아래 오로라 빛의 세기   // what = 숫자 아래 굵은 한 줄("낙하물 의심"), 뒤에 note 를 보조색으로 잇는다

/** 단속 알림 한 줄(11-15, 판 안): 제한속도 원 · 큰 거리 글 · 종류 글 */
data class CamUi(val limit: Int, val dist: String, val label: String)

/** 내 차(왼쪽 끝) → 위험 지점 가로 막대: frac = 위험 위치(0~1), 양 끝 글(내 차 · 축 끝 거리) */
data class BarUi(val frac: Float, val leftLabel: String, val rightLabel: String)

/** 주행 중 왼쪽 위 한 줄 알약(지도 우선 화면): 글 + 점 색(null = 초록 정상, CAUTION 주황, DANGER 빨강) */
data class StatusUi(val text: String, val tone: AlertTone?)

data class MemberUi(val name: String, val relation: String, val icon: Int, val type: String)

data class RegionUi(val id: String, val label: String)

sealed interface Panel {
    /** 대기: 함께 가기(있으면) + 가족 타일 */
    data class Idle(val roadLinkLabel: String?, val members: List<MemberUi>, val studyLabel: String? = null, val licensesLabel: String? = null,
                    val demoLabel: String? = null,        // 시연 빌드 전용 [시연 한 바퀴]
                    val summary: CardUi? = null,          // 직전 주행 요약(정차 중에만)
                    val parking: ParkingUi? = null,       // 주차 위치 기억(정차 중에만)
                    val dest: DestUi? = null,             // 목적지 검색·지정(차대차 판, 정차 중에만)
                    val hero: String? = null,             // 첫 화면 한 문장(차대차 판만) — 있으면 문장 + 그림 + 검은 버튼 하나, 연구 기록·고지·시연은 맨 아래 작은 글자 줄
                    val settings: SettingsUi? = null,     // 설정(지도 우선 화면만, 톱니) — 있으면 연구 기록·오픈소스 고지는 설정 안으로
                    // 주변 차 연결 / 일행 나누기(11-14): 연결 상태 한 줄 + 스위치 · 첫 실행 동의 한 장(있으면 판이 동의 판) · [일행 만들기](btn_convoy) 알약 글
                    val link: LinkLineUi? = null, val consent: ConsentUi? = null, val convoyLabel: String? = null,
                    val menuLabel: String? = null) : Panel   // 최소 표시 첫 화면의 상태 점 단추 이름(읽어 주기용 "더 보기")

    /** 픽업 진행: 단계(0 = 아직, 1 링크, 2 만날 곳, 3 도착, 4 탑승), 아이 메시지, 내비 버튼, 상태 카드(재약속·주문) */
    data class Pickup(val step: Int, val stepLabels: List<String>, val message: String?, val naviLabel: String?, val card: CardUi?, val members: List<MemberUi>) : Panel

    /** 함께 가기: 앞길 스트립(내 차·위험·일행·휴게소) + [가족 차 묶기 / 다같이 휴게소] [끝내기] */
    data class RoadLink(val strip: RoadStripUi?, val connecting: String?, val primaryLabel: String, val primaryIcon: Int, val endLabel: String, val showButtons: Boolean,
                        val chargeLabel: String? = null,   // chargeLabel: 휴게소·충전 정차 중 [충전기 고장 신고]
                        val detourLabel: String? = null,   // detourLabel: 최소 우회 안내 중 [우회 안내](FR-G-36)
                        val wrongLabel: String? = null,    // 정차 중에만 [경고 틀림](기록만, 판정 불변)
                        val summary: CardUi? = null,       // 도착해 정차했을 때 이번 주행 요약
                        val parking: ParkingUi? = null) : Panel   // 주차 위치 기억(정차 중에만)

    /** 충전기 고장 신고 1분 설문: 한 화면에 한 문항(그림 타일) + [이전] [모름·건너뛰기] [닫기]. 정차 중에만 */
    data class ChargeReport(val ui: ChargeReportUi) : Panel

    /** 연구 기록(경고 반응 로그): 동의 안내 + 참가자 정보(연령대·운전 경력·차종) 또는 기록 상태(내보내기·업로드·끄기). 정차 중에만 */
    data class Study(val ui: StudyUi) : Panel

    /** 오픈소스·데이터 고지(B-62): 쓰는 라이브러리와 라이선스 목록 + 안내 글 + [닫기]. 정차 중에만 */
    data class Licenses(val ui: LicensesUi) : Panel

    /** 응급: Safety 카드 + [119 전화 화면] [응급 끝내기] */
    data class Emergency(val card: CardUi, val dialLabel: String, val endLabel: String) : Panel
}

/**
 * 연구 기록 화면. active = 기록 중(상태·내보내기·업로드·끄기), 아니면 동의 안내와 참가자 정보 입력.
 * 연령대는 두 줄 세그먼트(4 + 3), 운전 경력은 −/+ , 차종은 한 줄 세그먼트. 글은 모두 지역 언어의 완성 문자열
 */
data class StudyUi(
    val active: Boolean,
    val consentTitle: String, val consentLines: List<String>,
    val ageTitle: String, val ageRows: List<List<RegionUi>>, val ageSel: String,
    val yearsTitle: String, val yearsLabel: String,
    val carTitle: String, val carItems: List<RegionUi>, val carSel: String,
    val agreeLabel: String, val agreeEnabled: Boolean, val declineLabel: String,
    val status: String?, val exportLabel: String, val uploadTitle: String, val uploadItems: List<RegionUi>, val uploadSel: String, val uploadNote: String,
    val stopLabel: String, val closeLabel: String,
)

/** 1분 설문 타일: 그림(없으면 글자만 크게 — 기기 번호) + 짧은 글 */
data class SurveyTileUi(val id: String, val label: String, val icon: Int?)
/** 1분 설문 한 화면: 지금 문항(progress = "2 / 5"), 타일, 열 수, 고른 답("" = 아직). backLabel = 첫 문항이면 null */
data class ChargeReportUi(val progress: String, val question: String, val tiles: List<SurveyTileUi>, val columns: Int, val sel: String,
                          val backLabel: String?, val skipLabel: String, val closeLabel: String)

/** 후방 카메라(기어 R, 언리얼 장면의 뒤 범퍼 카메라 JPEG) + 뒤 센서 거리(m, null = 범위 밖). stamp 가 바뀔 때만 비트맵을 다시 푼다 */
data class RearCamUi(val jpeg: ByteArray, val stamp: Long, val left: Float?, val center: Float?, val right: Float?, val title: String, val hint: String, val bitmap: android.graphics.Bitmap? = null)

/** 시연 주행 재생 막대(시연 판만, 11-14 사용자 "동영상 재생 처럼"): 재생·멈춤 · 진행 줄(누르거나 끌면 그 자리로, 앞·뒤 모두) · 지나온 거리 · 배속.
 *  frac: 출발~끝 가운데 내 자리(0~1). marks: 진행 줄 위 점(앞의 위험 = 빨강, 휴게소 = 흰색). note: 고른 배속이 잠깐 ×1 로 내려가 있을 때 한 줄 */
data class PlayerUi(val paused: Boolean, val frac: Float, val pos: String, val rate: String, val note: String?, val marks: List<PlayMark>,
                    val playLabel: String, val pauseLabel: String, val rateLabel: String, val seekLabel: String,
                    val stopLabel: String = "")   // 막대 오른쪽 끝 ✕: 시연 주행을 끝낸다(연결 끊기와 같다)
data class PlayMark(val frac: Float, val danger: Boolean)

data class TagoUiState(
    val cam: CamUi? = null,                 // 주행 중 단속 알림(위험 판 아래 한 줄 · 없으면 상태 줄 아래, 11-15)
    val rearCam: RearCamUi? = null,
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
    val notice: String? = null,           // 음성·차량 서비스를 못 쓸 때 한 줄(헤드라인 아래, 경고색)
    val banner: String? = null,           // 긴급차량 안내 중 맨 위 흑백 반전 배너(청각 보조 — 소리로만 알리지 않는다)
    val alert: AlertUi? = null,           // 경고 중이면 헤드라인 대신 큰 거리 숫자 + 제목 + 본문(뜻은 headline·subline 과 같다)
    /** 시작 버튼 아래 한 줄(11-14 오후): 실제 위치가 시연 출발 자리와 다르면 누르기 전에 '시연 구간으로 이동해 보여 드려요' */
    /** 낮은 단계 위험 한 줄(방향 구분 — 일반 도로의 맞은편·방향이 애매한 위험): 붉은 판·음성 없이 알약 아래 작은 줄로만. 없으면 null */
    val sideNote: String? = null,
    val goHint: String? = null,
    val driving: Boolean = false,         // 달리는 중이거나 기어를 모름(정차 전용 화면 금지와 같은 판정) — 지도 우선 화면은 필요한 것만 그린다
    val status: StatusUi? = null,         // 지도 우선 화면: 주행 중 경고가 없을 때의 한 줄 알약
    val around: AroundUi? = null,         // 지도 우선 화면: 함께 가는 중 달릴 때 왼쪽 판의 주변 차 화면(없으면 판 없음)
    /** 최소 표시 첫 화면(11-14 사용자 "최소한으로 표시되게" → 시안 C): 평소에는 지도 · 검색 한 줄 · 상태 점 하나 · 작은 응급 십자만. false 면 예전 첫 화면 판 */
    val homeMin: Boolean = false,
    val player: PlayerUi? = null,         // 시연 주행 재생 막대(시연 판에서 시연 주행 중에만) — 화면 아래
    val convoyPill: String? = null,       // 지도 우선 화면: 일행이 있을 때 주행 알약 옆 둘째 알약("일행 3대" 알약) — 주변 차 연결과 따로 보인다
)
