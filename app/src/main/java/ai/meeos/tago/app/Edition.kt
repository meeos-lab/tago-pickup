package ai.meeos.tago.app

import ai.meeos.tago.BuildConfig

/**
 * 출품작 판(`-Pmeeos.edition=gilbeot|pickup`, 없으면 두 기능판)에 따라 켜고 끄는 기능을 한 곳에서 묻는다.
 * BuildConfig 불리언을 파일마다 흩어 쓰지 않게 — 판 조합(예: 길벗 전용 = 함께 가기 && !데리러 가기)을 여기서만 정한다.
 * - 길벗(ai.meeos.gilbeot): 함께 가기(도로 링크)·응급 카드, 데리러 가기 없음
 * - 타고 픽업(ai.meeos.tago): 데리러 가기·요일 루틴·근처 찾기, 도로 링크·응급 브리핑 없음
 * - 두 기능판(판 지정 없음, debug 기본): 전부
 * 값은 빌드 때 정해지는 상수라 R8 이 꺼진 쪽 분기를 지운다.
 */
object Edition {
    /** 데리러 가기·요일 루틴·근처 찾기 */
    val pickup: Boolean = BuildConfig.PICKUP
    /** 함께 가기(차대차 도로 링크) */
    val roadLink: Boolean = BuildConfig.ROAD_LINK
    /** 응급 브리핑(사고 뒤 응급 카드) */
    val emergency: Boolean = BuildConfig.EMERGENCY
    /** 길벗 출품작: 함께 가기만 있고 데리러 가기는 없다 */
    val gilbeotOnly: Boolean get() = roadLink && !pickup
}
