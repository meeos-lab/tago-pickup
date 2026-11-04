package ai.meeos.tago.app

import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import java.util.Locale

/** 데리러 갈 가족 한 사람. type 은 엔진의 대상 규칙(child / adult / senior)과 같다 */
data class Member(val name: String, val type: String)

/**
 * 시연 지역: 대치동(한국어) + 현대차 해외 판매 상위 3개국 동네(영어).
 * 운전자가 대기 화면에서 고른다. 실차에서는 차량 위치·국가 설정으로 자동 선택한다.
 */
enum class Region(val id: String, val label: String, val lang: String, val emergencyNumber: String, val leftHandTraffic: Boolean) {
    DAECHI("daechi", "대치동", "ko", "119", false),
    IRVINE("irvine", "Irvine", "en", "911", false),
    MARKHAM("markham", "Markham", "en", "911", false),
    GURUGRAM("gurugram", "Gurugram", "en", "112", true);   // 인도는 좌측통행 — 인도 쪽이 왼쪽

    /** Pleos STT/TTS 지원 언어: ko-KR, en-US, fr-CA, es-US, es-MX (문서 기준). 해외 지역은 en-US */
    val locale: Locale get() = if (lang == "ko") Locale.KOREA else Locale.US

    val members: List<Member> get() = when (this) {
        DAECHI -> listOf(Member("지수", "child"), Member("엄마", "adult"), Member("할머니", "senior"))
        GURUGRAM -> listOf(Member("Aarav", "child"), Member("Mom", "adult"), Member("Grandma", "senior"))
        else -> listOf(Member("Emma", "child"), Member("Mom", "adult"), Member("Grandma", "senior"))
    }

    /** 태울 사람이 앉을 좌석(기본 2열 인도 쪽). 탑승 판정은 2열 세 좌석을 모두 보고, 문은 2열 양쪽을 본다 */
    val riderSeat: SeatValue get() = if (leftHandTraffic) SeatValue.ROW_2_LEFT else SeatValue.ROW_2_RIGHT
    val curbDoor: DoorValue get() = if (leftHandTraffic) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT
    val roadDoor: DoorValue get() = if (leftHandTraffic) DoorValue.ROW_2_RIGHT else DoorValue.ROW_2_LEFT

    companion object {
        val REAR_SEATS = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        val REAR_DOORS = listOf(DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
        val ALL_SEATS = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        fun of(id: String?): Region? = entries.firstOrNull { it.id == id }
    }
}
