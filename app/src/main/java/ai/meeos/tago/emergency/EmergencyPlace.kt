package ai.meeos.tago.emergency

import java.util.Locale

/**
 * 응급 카드의 위치 줄(V-06, 2026-11-13). 119 에 읽어 줄 값이 있어야 한다: 허브·픽업이 아는 장소 이름이 있으면 그것, 없으면 차 안 GPS 의 위도·경도,
 * 둘 다 없으면 null(호출하는 쪽이 '위치를 알 수 없습니다'). GPS 고정이 maxAgeMs 보다 오래됐거나 정확도가 나쁘면 쓰지 않는다 — 낡은 위치를 지금 위치처럼 읽어 주지 않는다.
 * 좌표는 카드에 보이기만 하고 서버로 보내지 않는다(docs/dev/83).
 */
object EmergencyPlace {
    const val MAX_AGE_MS = 120_000L
    const val MAX_ACCURACY_M = 200.0

    /** GPS 고정이 쓸 만한가(나이·정확도·유한한 값·위경도 범위) */
    fun usable(lat: Double, lon: Double, accuracyM: Double?, ageMs: Long): Boolean {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        if (ageMs < 0 || ageMs > MAX_AGE_MS) return false
        return accuracyM == null || accuracyM <= MAX_ACCURACY_M
    }

    /** 카드에 쓸 위도·경도 글자(소수 5자리 ≈ 1 m). 앞뒤 문구는 문자열 자원(emg_place_gps)이 지역 언어로 붙인다 */
    fun coords(lat: Double, lon: Double): Pair<String, String> = String.format(Locale.US, "%.5f", lat) to String.format(Locale.US, "%.5f", lon)
}
