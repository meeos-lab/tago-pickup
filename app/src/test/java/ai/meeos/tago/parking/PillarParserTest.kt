package ai.meeos.tago.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 기둥 사진 글자 해석(새 기능 H): 표기 40종 이상. 기대값 = (층, 기둥·구역). 층은 지하 음수 */
class PillarParserTest {
    private fun p(vararg lines: String) = PillarParser.parse(lines.toList())

    /** (입력 줄들, 층, 기둥) 표 */
    private val table: List<Triple<List<String>, Int?, String?>> = listOf(
        // 층만
        Triple(listOf("B3"), -3, null),
        Triple(listOf("b3"), -3, null),
        Triple(listOf("B 3"), -3, null),
        Triple(listOf("B3F"), -3, null),
        Triple(listOf("B3층"), -3, null),
        Triple(listOf("B-3"), -3, null),
        Triple(listOf("B1"), -1, null),
        Triple(listOf("Ｂ３"), -3, null),                       // 전각 문자(NFKC)
        Triple(listOf("지하 2층"), -2, null),
        Triple(listOf("지하2층"), -2, null),
        Triple(listOf("지하3"), -3, null),
        Triple(listOf("지2"), -2, null),
        Triple(listOf("지하 주차장 2층"), -2, null),
        Triple(listOf("BASEMENT 2"), -2, null),
        Triple(listOf("3층"), 3, null),
        Triple(listOf("3F"), 3, null),
        Triple(listOf("2 F"), 2, null),
        Triple(listOf("지하10층"), -10, null),
        // 기둥·구역만
        Triple(listOf("C-12"), null, "C-12"),
        Triple(listOf("C12"), null, "C-12"),
        Triple(listOf("C 12"), null, "C-12"),
        Triple(listOf("C-12번"), null, "C-12"),
        Triple(listOf("가-7"), null, "가-7"),
        Triple(listOf("나12"), null, "나-12"),
        Triple(listOf("다 3"), null, "다-3"),
        Triple(listOf("D-05"), null, "D-5"),                    // 맨 앞 0 은 뗀다
        Triple(listOf("A구역"), null, "A구역"),
        Triple(listOf("C 구역"), null, "C구역"),
        Triple(listOf("B-12"), null, "B-12"),                   // B + 두 자리 = B 구역 12번 기둥
        Triple(listOf("B13"), null, "B-13"),
        // 층 + 기둥
        Triple(listOf("B3 C-12"), -3, "C-12"),
        Triple(listOf("B3", "C-12"), -3, "C-12"),               // 줄이 나뉘어 읽힘
        Triple(listOf("지하2층 C-12"), -2, "C-12"),
        Triple(listOf("지하 2층 가-7"), -2, "가-7"),
        Triple(listOf("B2 가7"), -2, "가-7"),
        Triple(listOf("B-3 C-12"), -3, "C-12"),                 // B-3 은 층, C-12 는 기둥
        Triple(listOf("지하1층 B-3"), -1, "B-3"),               // 강한 층이 있으면 B-3 은 기둥
        Triple(listOf("P3 B2"), -2, "P-3"),
        Triple(listOf("5층 D-14"), 5, "D-14"),
        Triple(listOf("B3 · C-12 · 엘리베이터"), -3, "C-12"),
        Triple(listOf("주차 B4 · D-05"), -4, "D-5"),
        Triple(listOf("지하1층", "A-1"), -1, "A-1"),
        Triple(listOf("B2", "D-14 지하 2층"), -2, "D-14"),     // 같은 층을 두 번 적은 표기: B2 는 층의 반복이지 기둥이 아니다
        Triple(listOf("지하 2층", "B2"), -2, null),
        Triple(listOf("지하 3층 B2 구역"), -3, "B-2"),         // 다른 층 숫자의 B2 는 기둥(B 구역 2번)
    )

    @Test fun tableOfNotations() {
        assertEquals("표기 40종 이상", true, table.size >= 40)
        for ((lines, floor, pillar) in table) {
            val r = PillarParser.parse(lines)
            assertEquals("층: $lines", floor, r?.floor)
            assertEquals("기둥: $lines", pillar, r?.pillar)
        }
    }

    @Test fun nothingUsefulIsNull() {
        for (s in listOf("", "   ", "EXIT", "비상구", "02-3456-7890", "2024-11-13", "A12B", "주차가 12", "EXIT 3", "VOL 12", "00", "C-0", "B0")) assertNull("쓸 것 없음: '$s'", p(s))
    }

    @Test fun overlongFloorIsDropped() { assertNull(p("지하 99층")); assertNull(p("30층")) }

    @Test fun firstPillarWins() { assertEquals("C-12", p("C-12", "D-3")?.pillar) }

    @Test fun normalizeKeepsKoreanAndFoldsDashes() {
        assertEquals("C-12 가-7", PillarParser.normalize("c–12  가—7"))
        assertEquals("B3", PillarParser.normalize(" ｂ３ "))
    }
}
