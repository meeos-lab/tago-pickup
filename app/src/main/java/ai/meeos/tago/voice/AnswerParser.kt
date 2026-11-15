package ai.meeos.tago.voice

/**
 * 운전자 말에서 뜻을 가린다(한국어·영어). 공백을 지운 글과 소문자 글을 함께 본다.
 * 순서(응급 > 주문 > 휴게소 > 함께 가기 > 가족 > 근처 > 예/아니오)는 AppController.handleUtterance 가 정한다.
 */
object AnswerParser {
    class Utterance(text: String) {
        val ko: String = text.replace(" ", "")
        val en: String = ko.lowercase()
    }

    // 정규식은 한 번만 만든다(말마다 22개를 새로 컴파일하지 않게). 패턴은 아래 함수 순서대로
    private val EMERGENCY_KO = Regex("구급|응급|119|어지러|쓰러|숨을|아파")
    private val EMERGENCY_EN = Regex("ambulance|emergency|911|dizzy|faint|breathe")
    private val REST_KO = Regex("휴게소|쉬었다|쉬어가")
    private val REST_EN = Regex("reststop|restarea|takeabreak")
    // 11-14 두 층: 주변 차 연결(기본) / 아는 차끼리의 일행 — 말도 나눈다. "함께 가자·같이 가자"는 일행 만들기(연결이 꺼져 있으면 먼저 연결)
    private val START_ROAD_KO = Regex("도로링크|연결해|연결시작|출발")
    private val START_ROAD_EN = Regex("roadlink|linknow|letsgo")
    private val TOGETHER_KO = Regex("함께가|같이가")
    private val TOGETHER_EN = Regex("drivetogether|gotogether")
    private val GROUP_KO = Regex("가족|일행")
    private val GROUP_EN = Regex("family|group")
    private val TOILET_KO = Regex("화장실")
    private val TOILET_EN = Regex("restroom|bathroom|toilet")
    private val DRIVETHRU_KO = Regex("드라이브스루")
    private val DRIVETHRU_EN = Regex("drive-?thr?u|drive-?through")   // drive-through(하이픈+through)도
    private val CHARGER_REPORT_KO = Regex("충전기.*고장|고장.*충전기")
    private val CHARGER_REPORT_EN = Regex("charger.*(broken|outoforder|notworking)|(broken|outoforder).*charger")
    private val BROKEN_KO = Regex("고장")
    private val BROKEN_EN = Regex("broken|outoforder|notworking")
    private val TAKEOUT_KO = Regex("포장|간식|커피사")
    private val TAKEOUT_EN = Regex("takeout|take-out|pickuporder|grabcoffee|grabasnack")
    private val NO_KO = Regex("아니|다른|싫어|대안|말고|안해|안함|됐어")
    private val NO_EN = Regex("^(no|nope|nah)|other|somewhereelse|nottoday")
    private val YES_EN = Regex("^((yes|yeah|yep|ok|okay|sure|go|let'sgo|soundsgood|please)[.,!]*)+$")
    private val YES_KO = Regex("^((응+|어|그래|좋아|네+|예|오케이|가자|맞아|거기로|거기|그리로|가|보내|보내줘)[.,!~요]*)+$")

    fun isEmergency(u: Utterance) = EMERGENCY_KO.containsMatchIn(u.ko) || EMERGENCY_EN.containsMatchIn(u.en)
    fun isRestStop(u: Utterance) = REST_KO.containsMatchIn(u.ko) || REST_EN.containsMatchIn(u.en)
    fun isStartRoad(u: Utterance) = START_ROAD_KO.containsMatchIn(u.ko) || START_ROAD_EN.containsMatchIn(u.en)
    fun isTogether(u: Utterance) = TOGETHER_KO.containsMatchIn(u.ko) || TOGETHER_EN.containsMatchIn(u.en)
    fun isGroup(u: Utterance) = GROUP_KO.containsMatchIn(u.ko) || GROUP_EN.containsMatchIn(u.en)
    fun isToilet(u: Utterance) = TOILET_KO.containsMatchIn(u.ko) || TOILET_EN.containsMatchIn(u.en)
    fun isDriveThru(u: Utterance) = DRIVETHRU_KO.containsMatchIn(u.ko) || DRIVETHRU_EN.containsMatchIn(u.en)
    /** "충전기 고장" 신고(주행 중에는 이 말로만 신고한다) */
    fun isChargerReport(u: Utterance) = CHARGER_REPORT_KO.containsMatchIn(u.ko) || CHARGER_REPORT_EN.containsMatchIn(u.en)
    /** "충전기 고장인가요?"에 '고장'으로 답함(예와 같다). '고장 아니에요'는 yesNo 가 아니오로 먼저 잡는다 */
    fun isBroken(u: Utterance) = BROKEN_KO.containsMatchIn(u.ko) || BROKEN_EN.containsMatchIn(u.en)
    fun isTakeout(u: Utterance) = TAKEOUT_KO.containsMatchIn(u.ko) || TAKEOUT_EN.containsMatchIn(u.en)

    /** 예 → true, 아니오 → false, 답이 아니면 null */
    fun yesNo(u: Utterance): Boolean? = when {
        NO_KO.containsMatchIn(u.ko) || NO_EN.containsMatchIn(u.en) -> false
        YES_EN.matches(u.en) -> true
        YES_KO.matches(u.ko) -> true
        else -> null
    }
}
