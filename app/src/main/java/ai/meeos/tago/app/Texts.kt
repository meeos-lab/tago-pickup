package ai.meeos.tago.app

import ai.meeos.tago.R
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources

/**
 * 지역 언어의 문자열. 글은 res/values/strings.xml(한국어)·values-en/strings.xml(영어)에만 둔다 — 코드에 글을 쓰지 않는다.
 * 지역은 시스템 언어와 무관하게 운전자가 고르므로, 그 지역 로케일의 Resources 를 따로 만들어 읽는다.
 */
class Texts(private val app: Context, region: Region) {
    private var res: Resources = localized(region)
    var lang: String = region.lang
        private set

    fun setRegion(r: Region) {
        res = localized(r)
        lang = r.lang
    }

    operator fun get(id: Int, vararg args: Any): String = res.getString(id, *args)

    private fun localized(r: Region): Resources =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(r.locale) }).resources

    // 한국어 조사: 영어 지역에서는 단어를 그대로 돌려준다. 끝의 괄호·따옴표·공백은 건너뛰고 마지막 한글의 받침을 본다("기흥휴게소(하행)" → 소)
    private fun jong(word: String): Int? {
        val trimmed = word.trimEnd { it in ")]}>\"'’ ” " }
        val c = trimmed.lastOrNull() ?: return null
        if (c !in '가'..'힣') return null
        return (c - '가') % 28
    }

    /** 받침에 따라 '로/으로' (ㄹ 받침은 '로'). 한글이 아니면 '로' */
    fun ro(word: String): String {
        if (lang != "ko") return word
        val j = jong(word) ?: return word + "로"
        return word + if (j == 0 || j == 8) "로" else "으로"
    }

    /** 이름 + 이/가 */
    fun subj(word: String): String {
        if (lang != "ko") return word
        val j = jong(word) ?: return word + "가"
        return word + if (j == 0) "가" else "이"
    }

    /** 이름 + 을/를 */
    fun obj(word: String): String {
        if (lang != "ko") return word
        val j = jong(word) ?: return word + "를"
        return word + if (j == 0) "를" else "을"
    }

    fun seatLabel(seat: SeatValue): String = when (seat) {
        SeatValue.ROW_2_LEFT -> get(R.string.seat_row2_left)
        SeatValue.ROW_2_CENTER -> get(R.string.seat_row2_center)
        SeatValue.ROW_2_RIGHT -> get(R.string.seat_row2_right)
        else -> seat.name
    }

    fun memberType(type: String): String = when (type) {
        "child" -> get(R.string.member_type_child)
        "senior" -> get(R.string.member_type_senior)
        else -> get(R.string.member_type_adult)
    }

    /** 초 → "5분" / "1분 안" */
    fun mins(sec: Int): String = if (sec < 45) get(R.string.under_a_minute) else get(R.string.n_minutes, (sec + 30) / 60)

    /** 거리: 1km 미만은 100m 단위, 그 밖은 소수 한 자리 km */
    fun km(m: Int): String {
        val a = kotlin.math.abs(m)
        return if (a < 950) "${(a + 50) / 100 * 100}m" else "%.1fkm".format(a / 1000.0)
    }
}
