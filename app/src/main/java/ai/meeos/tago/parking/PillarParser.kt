package ai.meeos.tago.parking

import java.text.Normalizer

/**
 * 기둥 사진 글자(OCR)에서 층·기둥 표기를 뽑는 순수 파서(새 기능 H). 글자를 읽는 것(ML Kit)과 해석(이 파일)을 나눠, 해석은 JVM 단위 시험이 고정한다.
 *
 * 입력 = 인식된 글 줄들. 출력 = (층, 기둥·구역). 층은 지하가 음수(B3 = −3), 지상이 양수(3층 = 3). 모르면 null.
 *  - 층: `지하 2층`·`지2`·`B3`·`B3F`·`B-3`·`3층`·`3F`·`BASEMENT 2`. 'B 한 자리 숫자' 는 층으로 본다(다른 층 글이 없을 때) — `B-12`처럼 두 자리면 B 구역 12번 기둥.
 *  - 기둥·구역: `C-12`·`C12`·`C 12`·`C-12번`·`가-7`·`나12`·`A구역`. 숫자는 맨 앞 0 을 떼고 `글자-숫자` 로 돌려준다.
 *  - 지하 안내가 어디든 있고 층이 '3층' 꼴이면 지하로 본다(`지하 주차장 2층`).
 *  - 같은 층을 두 번 적은 표기(`B2`와 `지하 2층`)에서 B2 는 기둥으로 보지 않는다.
 *  - 4자리 이상 숫자·전화번호·날짜는 쓰지 않는다. 글자와 숫자가 붙은 낱말(`A12B`)도 쓰지 않는다.
 * 사진 원본은 이 파서에 오지 않는다(글자만).
 */
data class PillarInfo(val floor: Int?, val pillar: String?)

object PillarParser {
    private const val ZONES = "가나다라마바사아자차카타파하"
    private val DASH = Regex("[‐‑‒–—−ー]")

    // 강한 층: 층·F 가 붙었거나 '지하'
    private val BASE_KO = Regex("지하\\s*(\\d{1,2})\\s*층?")
    private val BASE_EN = Regex("BASEMENT\\s*(\\d{1,2})")
    private val B_FLOOR = Regex("(?<![A-Z0-9가-힣])B\\s*-?\\s*(\\d{1,2})\\s*(?:F|층)(?![A-Z])")
    private val ABOVE = Regex("(?<![A-Z0-9가-힣-])(\\d{1,2})\\s*(?:F|층)(?![A-Z])")
    private val JI = Regex("(?<![A-Z0-9가-힣])지\\s*(\\d)(?!\\d)")

    // 글자-숫자 후보(기둥·구역, 또는 'B 한 자리' 약한 층)
    private val LETNUM = Regex("(?<![A-Z가-힣0-9])([A-Z${ZONES}])\\s*-?\\s*(\\d{1,3})(?:\\s*(?:번|열|기둥))?(?![0-9A-Z가-힣])")
    private val ZONE_ONLY = Regex("(?<![A-Z가-힣0-9])([A-Z${ZONES}])\\s*(?:구역|ZONE|존)")

    fun normalize(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = DASH.replace(s, "-").uppercase()
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun parse(lines: List<String>): PillarInfo? {
        var text = lines.joinToString(" | ") { normalize(it) }
        if (text.isBlank()) return null
        val hasJiha = text.contains("지하")
        var floor: Int? = null

        fun take(re: Regex, sign: Int): Boolean {
            val m = re.find(text) ?: return false
            val n = m.groupValues[1].toInt()
            if (n < 1 || n > ParkingConst.MAX_FLOORS) { text = text.replaceRange(m.range, " · "); return false }
            floor = sign * n
            text = text.replaceRange(m.range, " · ")
            return true
        }
        // 강한 층(앞에 있는 것이 먼저): 지하·BASEMENT·B…F/층 → 지상 ○층 → 지○
        if (!take(BASE_KO, -1)) if (!take(BASE_EN, -1)) if (!take(B_FLOOR, -1)) {
            val up = ABOVE.find(text)
            if (up != null && take(ABOVE, 1) && hasJiha) floor = -floor!!    // '지하 주차장 2층'
            else if (floor == null) take(JI, -1)
        }

        // 글자-숫자 후보
        val cands = mutableListOf<Triple<String, Int, IntRange>>()
        for (m in LETNUM.findAll(text)) {
            val n = m.groupValues[2].toInt()
            if (n == 0) continue
            cands += Triple(m.groupValues[1], n, m.range)
        }
        var pillar: String? = null
        if (floor == null) {
            // 약한 층: B + 한 자리(1~9) 하나. 다른 후보가 있어도 이 B 는 층이다
            val b = cands.firstOrNull { it.first == "B" && it.second in 1..9 }
            if (b != null) { floor = -b.second; cands.remove(b) }
        }
        // 같은 층을 두 번 적은 표기('B2' 와 '지하 2층')의 B2 는 기둥이 아니다
        val fl = floor
        if (fl != null && fl < 0) cands.removeAll { it.first == "B" && it.second == -fl }
        cands.firstOrNull()?.let { pillar = "${it.first}-${it.second}" }
        if (pillar == null) ZONE_ONLY.find(text)?.let { pillar = "${it.groupValues[1]}구역" }
        if (floor == null && pillar == null) return null
        return PillarInfo(floor, pillar)
    }
}
