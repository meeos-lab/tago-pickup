package ai.meeos.tago.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 방법 C(건물 층수): TMAP 역지오코딩 → 지번, 건축HUB 표제부 → 층 범위. 응답 모양은 공개 문서 기준 + 에뮬레이터 실호출 1회로 확인(95 문서) */
class BuildingFloorsTest {
    private fun tmap(code: String, bunji: String) = """{"addressInfo":{"fullAddress":"x","addressType":"A10","legalDong":"삼성동","legalDongCode":"$code","bunji":"$bunji"}}"""

    @Test fun parcelFromReverseGeocode() {
        assertEquals(Parcel("11680", "10500", "0", "0159", "0000"), BuildingFloors.parcelFromTmap(tmap("1168010500", "159")))
        assertEquals(Parcel("11680", "10500", "0", "0159", "0001"), BuildingFloors.parcelFromTmap(tmap("1168010500", "159-1")))
        assertEquals(Parcel("41135", "10900", "1", "0012", "0003"), BuildingFloors.parcelFromTmap(tmap("4113510900", "산 12-3")))
    }

    @Test fun badAddressIsSkipped() {
        assertNull(BuildingFloors.parcelFromTmap(tmap("11680", "159")))         // 법정동 코드가 10자리가 아님
        assertNull(BuildingFloors.parcelFromTmap(tmap("1168010500", "")))       // 번지 없음
        assertNull(BuildingFloors.parcelFromTmap(tmap("1168010500", "159번지 일대")))
        assertNull(BuildingFloors.parcelFromTmap("{}")); assertNull(BuildingFloors.parcelFromTmap("not json"))
    }

    private fun title(items: String, code: String = "00") = """{"response":{"header":{"resultCode":"$code","resultMsg":"NORMAL SERVICE."},"body":{"items":$items,"numOfRows":100,"pageNo":1,"totalCount":2}}}"""

    @Test fun rangeTakesTheLargestOfAllBuildingsOnTheParcel() {
        val j = title("""{"item":[{"bldNm":"A","ugrndFlrCnt":4,"grndFlrCnt":"55"},{"bldNm":"B","ugrndFlrCnt":"6","grndFlrCnt":3}]}""")
        assertEquals(FloorRange(6, 55), BuildingFloors.rangeFromTitle(j))
    }

    @Test fun singleItemObjectAndNoBasement() {
        assertEquals(FloorRange(0, 5), BuildingFloors.rangeFromTitle(title("""{"item":{"ugrndFlrCnt":0,"grndFlrCnt":5}}""")))
    }

    @Test fun errorsAndEmptyAreNull() {
        assertNull(BuildingFloors.rangeFromTitle(title("""{"item":[]}""")))
        assertNull(BuildingFloors.rangeFromTitle(title("\"\"")))                              // 결과 없음일 때 items 가 빈 문자열
        assertNull(BuildingFloors.rangeFromTitle(title("""{"item":{"ugrndFlrCnt":3}}""", code = "30")))   // 키 오류 등
        assertNull(BuildingFloors.rangeFromTitle("<OpenAPI_ServiceResponse>…</OpenAPI_ServiceResponse>"))   // XML 오류 응답
        assertNull(BuildingFloors.rangeFromTitle(title("""{"item":{"ugrndFlrCnt":"x","grndFlrCnt":""}}""")))
    }

    @Test fun urlsDoNotDoubleEncodeAnEncodedKey() {
        val p = Parcel("11680", "10500", "0", "0159", "0000")
        assertTrue(BuildingFloors.titleUrl("ab%2Bcd%3D%3D", p).contains("serviceKey=ab%2Bcd%3D%3D&"))   // 인코딩 키는 그대로
        assertTrue(BuildingFloors.titleUrl("ab+cd==", p).contains("serviceKey=ab%2Bcd%3D%3D&"))         // 디코딩 키는 인코딩
        assertTrue(BuildingFloors.titleUrl("k", p).endsWith("sigunguCd=11680&bjdongCd=10500&platGbCd=0&bun=0159&ji=0000&numOfRows=100&pageNo=1&_type=json"))
        assertEquals("${BuildingFloors.TMAP_REVERSE}?version=1&format=json&coordType=WGS84GEO&addressType=A10&lat=37.511600&lon=127.059400", BuildingFloors.reverseUrl(37.5116, 127.0594))
    }
}
