package ai.meeos.tago.voice

import ai.meeos.tago.voice.AnswerParser.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 운전자 말 해석(한국어·영어). 공백을 지운 글·소문자 글로 보는 규칙이 바뀌면 여기서 먼저 깨진다 */
class AnswerParserTest {
    private fun u(s: String) = Utterance(s)

    @Test fun emergencyKoreanAndEnglish() {
        assertTrue(AnswerParser.isEmergency(u("구급차 불러 줘")))
        assertTrue(AnswerParser.isEmergency(u("머리가 어 지 러 워")))   // 공백을 지우고 본다
        assertTrue(AnswerParser.isEmergency(u("Call an AMBULANCE")))   // 영어는 소문자로 본다
        assertFalse(AnswerParser.isEmergency(u("휴게소 들르자")))
    }

    @Test fun intents() {
        assertTrue(AnswerParser.isRestStop(u("다음 휴게소에서 쉬어가자")))
        assertTrue(AnswerParser.isRestStop(u("take a break")))
        assertTrue(AnswerParser.isStartRoad(u("길벗 출발")))
        assertTrue(AnswerParser.isGroup(u("가족한테 알려 줘")))
        assertTrue(AnswerParser.isToilet(u("restroom please")))
        assertTrue(AnswerParser.isDriveThru(u("drive thru")))
        assertTrue(AnswerParser.isDriveThru(u("drive through")))
        assertTrue(AnswerParser.isDriveThru(u("Drive-Through window")))   // 11-06 전에는 놓쳤다
        assertTrue(AnswerParser.isTakeout(u("커피 사 갈래")))
    }

    @Test fun chargerReportNeedsBothWords() {
        assertTrue(AnswerParser.isChargerReport(u("충전기가 고장 났어")))
        assertTrue(AnswerParser.isChargerReport(u("this charger is out of order")))
        assertFalse(AnswerParser.isChargerReport(u("충전기 어디 있어")))
        assertTrue(AnswerParser.isBroken(u("고장이요")))
    }

    @Test fun yesNo() {
        assertEquals(true, AnswerParser.yesNo(u("응")))
        assertEquals(true, AnswerParser.yesNo(u("네네")))
        assertEquals(true, AnswerParser.yesNo(u("좋아요!")))
        assertEquals(true, AnswerParser.yesNo(u("Yes, let's go")))
        assertEquals(false, AnswerParser.yesNo(u("아니 다른 데")))
        assertEquals(false, AnswerParser.yesNo(u("nope")))
        assertEquals(false, AnswerParser.yesNo(u("고장 아니에요")))   // '아니' 가 먼저 — 충전기 질문의 '고장' 답과 겹치지 않게
        assertNull(AnswerParser.yesNo(u("글쎄")))
        assertNull(AnswerParser.yesNo(u("yes but later maybe")))       // 예는 문장 전체가 긍정일 때만
    }
}
