package ai.meeos.tago.parking

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 방법 C(건물 층수, 새 기능 H): 주차장 입구 좌표 → 지번(TMAP 역지오코딩) → 국토교통부 건축HUB 건축물대장 표제부(getBrTitleInfo)의
 * 지하층수(ugrndFlrCnt)·지상층수(grndFlrCnt) → 층 추정의 범위 제한(FloorRange). 이 파일은 주소 만들기와 응답 해석만(순수, 단위 시험) —
 * 네트워크·캐시·빈도 제한은 BuildingFloorLookup(ParkingController 가 쓴다).
 *
 * 키: 공공데이터포털 일반 인증키는 local.properties 의 BLDG_API_KEY 를 빌드가 BuildConfig 로 넣는다(TMAP 키와 같은 방식, 저장소에는 없음).
 * 비어 있으면 방법 C 는 건너뛴다. 키 값은 로그·문서·커밋에 남기지 않는다.
 * 같은 지번에 건물이 여럿이면(표제부 여러 건) 지하·지상 모두 가장 큰 값을 쓴다 — 범위를 넓게 잡아 맞는 층을 잘라 내지 않게.
 */
data class Parcel(val sigunguCd: String, val bjdongCd: String, val platGbCd: String, val bun: String, val ji: String) {
    /** 캐시 열쇠(건물 단위) */
    val key: String get() = "$sigunguCd$bjdongCd-$platGbCd-$bun-$ji"
}

object BuildingFloors {
    const val TMAP_REVERSE = "https://apis.openapi.sk.com/tmap/geo/reversegeocoding"
    const val TITLE_URL = "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo"

    fun reverseUrl(lat: Double, lon: Double): String =
        "$TMAP_REVERSE?version=1&format=json&coordType=WGS84GEO&addressType=A10&lat=%.6f&lon=%.6f".format(java.util.Locale.US, lat, lon)

    /** 공공데이터포털 키는 '인코딩 키'(% 가 들어 있음)와 '디코딩 키' 둘 다 쓰인다 — % 가 있으면 그대로, 없으면 URL 인코딩 */
    fun titleUrl(serviceKey: String, p: Parcel): String {
        val k = if (serviceKey.contains('%')) serviceKey else URLEncoder.encode(serviceKey, "UTF-8")
        return "$TITLE_URL?serviceKey=$k&sigunguCd=${p.sigunguCd}&bjdongCd=${p.bjdongCd}&platGbCd=${p.platGbCd}&bun=${p.bun}&ji=${p.ji}&numOfRows=100&pageNo=1&_type=json"
    }

    /**
     * TMAP 역지오코딩 응답 → 지번. legalDongCode(10자리 = 시군구 5 + 법정동 5)와 bunji("159", "159-1", "산 12-3").
     * 모자라면 null(방법 C 건너뜀)
     */
    fun parcelFromTmap(json: String): Parcel? = runCatching {
        val a = JSONObject(json).getJSONObject("addressInfo")
        val code = a.optString("legalDongCode").filter { it.isDigit() }
        if (code.length != 10) return null
        var bunji = a.optString("bunji").trim()
        var plat = "0"
        if (bunji.startsWith("산")) { plat = "1"; bunji = bunji.removePrefix("산").trim() }
        val m = Regex("^(\\d{1,4})(?:-(\\d{1,4}))?$").find(bunji) ?: return null
        val bun = m.groupValues[1].padStart(4, '0')
        val ji = (m.groupValues[2].ifEmpty { "0" }).padStart(4, '0')
        Parcel(code.substring(0, 5), code.substring(5, 10), plat, bun, ji)
    }.getOrNull()

    /**
     * 건축HUB 표제부 응답 → 층 범위. response.body.items.item 이 객체 하나이거나 배열. 결과 코드가 정상(00)이 아니거나 항목이 없으면 null.
     * 층수가 숫자가 아니면 그 항목은 건너뛴다.
     */
    fun rangeFromTitle(json: String): FloorRange? = runCatching {
        val resp = JSONObject(json).getJSONObject("response")
        val code = resp.optJSONObject("header")?.optString("resultCode").orEmpty()
        if (code.isNotEmpty() && code != "00" && code != "000") return null
        val items = resp.optJSONObject("body")?.opt("items") ?: return null
        val item = (items as? JSONObject)?.opt("item") ?: return null
        val list: List<JSONObject> = when (item) {
            is JSONArray -> (0 until item.length()).mapNotNull { item.optJSONObject(it) }
            is JSONObject -> listOf(item)
            else -> emptyList()
        }
        var under: Int? = null; var above: Int? = null
        for (o in list) {
            o.opt("ugrndFlrCnt")?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 0..ParkingConst.MAX_FLOORS * 2 }?.let { under = maxOf(under ?: 0, it) }
            o.opt("grndFlrCnt")?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 0..200 }?.let { above = maxOf(above ?: 0, it) }
        }
        if (under == null && above == null) null else FloorRange(under, above)
    }.getOrNull()
}
