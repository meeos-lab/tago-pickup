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

    fun isEmergency(u: Utterance) = Regex("구급|응급|119|어지러|쓰러|숨을|아파").containsMatchIn(u.ko) || Regex("ambulance|emergency|911|dizzy|faint|breathe").containsMatchIn(u.en)
    fun isRestStop(u: Utterance) = Regex("휴게소|쉬었다|쉬어가").containsMatchIn(u.ko) || Regex("reststop|restarea|takeabreak").containsMatchIn(u.en)
    fun isStartRoad(u: Utterance) = Regex("도로링크|함께가|같이가|출발").containsMatchIn(u.ko) || Regex("roadlink|drivetogether|letsgo").containsMatchIn(u.en)
    fun isGroup(u: Utterance) = Regex("가족|일행").containsMatchIn(u.ko) || Regex("family|group").containsMatchIn(u.en)
    fun isToilet(u: Utterance) = Regex("화장실").containsMatchIn(u.ko) || Regex("restroom|bathroom|toilet").containsMatchIn(u.en)
    fun isDriveThru(u: Utterance) = Regex("드라이브스루").containsMatchIn(u.ko) || Regex("drive-?thr?u|drivethrough").containsMatchIn(u.en)
    fun isTakeout(u: Utterance) = Regex("포장|간식|커피사").containsMatchIn(u.ko) || Regex("takeout|take-out|pickuporder|grabcoffee|grabasnack").containsMatchIn(u.en)

    /** 예 → true, 아니오 → false, 답이 아니면 null */
    fun yesNo(u: Utterance): Boolean? = when {
        Regex("아니|다른|싫어|대안|말고|안해|안함|됐어").containsMatchIn(u.ko) || Regex("^(no|nope|nah)|other|somewhereelse|nottoday").containsMatchIn(u.en) -> false
        Regex("^((yes|yeah|yep|ok|okay|sure|go|let'sgo|soundsgood|please)[.,!]*)+$").matches(u.en) -> true
        Regex("^((응+|어|그래|좋아|네+|예|오케이|가자|맞아|거기로|거기|그리로|가|보내|보내줘)[.,!~요]*)+$").matches(u.ko) -> true
        else -> null
    }
}
