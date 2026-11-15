package ai.meeos.tago.ui

/** 오픈소스 고지 화면(B-62)의 한 줄: 이름과 라이선스. 이름·라이선스 표기는 번역하지 않는다 */
data class LicenseItemUi(val name: String, val license: String)

/** 오픈소스 고지 화면: 안내 글, 목록, 덧붙임 글들, 닫기 글. 글은 모두 지역 언어의 완성 문자열 */
/** privacy: 개인정보 고지(제목 + 줄들, 차대차 판만 — 비면 그리지 않는다), licTitle: 오픈소스 목록 제목 */
data class LicensesUi(val intro: String, val items: List<LicenseItemUi>, val notes: List<String>, val closeLabel: String,
                      val privacyTitle: String? = null, val privacy: List<String> = emptyList(), val licTitle: String? = null)

/** 앱에 들어가는 라이브러리 목록. 근거와 전체 목록은 저장소 THIRD_PARTY_NOTICES.md(라이선스는 빌드 캐시 POM 에서 읽음) */
object LicenseList {
    private val common = listOf(
        LicenseItemUi("Jetpack Compose · AndroidX", "Apache-2.0"),
        LicenseItemUi("Kotlin · kotlinx", "Apache-2.0"),
        LicenseItemUi("Gson · Dagger/Hilt · javax.inject", "Apache-2.0"),
        LicenseItemUi("FlatBuffers", "Apache-2.0"),
        LicenseItemUi("LiteRT", "Apache-2.0"),
    )
    /** 사이렌 감지가 있는 판에만 들어가는 것: 사이렌 분류 모델, 3D 화면의 차 모델, 주차 기둥 사진 글자 인식(ML Kit, 11-13) */
    private val withRoadLink = listOf(
        LicenseItemUi("YAMNet", "Apache-2.0"),
        LicenseItemUi("ML Kit Text Recognition (Korean)", "ML Kit Terms of Service"),
        LicenseItemUi("Kenney Car Kit 3.1", "CC0 1.0"),
    )

    fun items(roadLink: Boolean): List<LicenseItemUi> = if (roadLink) common + withRoadLink else common
}
