package ai.meeos.tago.car

/**
 * 충돌 감지(docs/incident-judgment-design.md 10-1, docs/dev/40-algorithms.md A-02): 10 Hz 차속 표본(ms, m/s)을 받아
 * 두 표본 사이 평균 감속률 ≥ 14 m/s² 이고 Δv ≥ 2.2 m/s(8 km/h), 간격 0.08~0.5 s 이면 후보 → 그 뒤 0.5 s 동안 속도가 사건 전의
 * 50 % 아래로 머물면 충돌(한 표본 튐 제외). 10 s 에 한 번만. 제동 한계(1.1 g = 10.8 m/s²)와 30 % 여유.
 * 차 신호(CarSignals)와 떼어 시각을 인자로 받는다 — 단위 시험(ImpactDetectorTest)이 규칙을 고정한다.
 * 허브 재검증(LinkHub.impactOk·link_server impact_ok)과 PC 시험 도구(server/link/impact_sim.py)가 같은 상수를 쓴다.
 */
class ImpactDetector(private val log: (String) -> Unit = {}) {
    /** 확정 때 (사건 전 속도, 후보 속도, 최근 2.5초 표본) */
    var onImpact: ((pre: Float, now: Float, samples: List<Pair<Long, Float>>) -> Unit)? = null
    private val fast = ArrayDeque<Pair<Long, Float>>()      // (ms, m/s) 최근 2.5초
    private var cand: Triple<Long, Float, Float>? = null    // 후보 (시각 ms, 사건 전 속도, 후보 속도)
    private var impactAt = 0L

    /** 표본과 후보를 비운다(구독을 켜고 끌 때·시연 표본을 넣기 전) */
    fun reset() { fast.clear(); cand = null }

    /** 표본 하나를 넣는다. 시각은 ms(단조 증가) */
    fun feed(ms: Long, v: Float) {
        fast.addLast(ms to v)
        while (fast.size > 1 && ms - fast.first().first > FAST_KEEP_MS) fast.removeFirst()
        val c = cand
        if (c != null) {
            // 후보 뒤 0.5 s: 하나라도 사건 전 속도의 50 % 이상이면 튐으로 버린다. 0.5 s 를 넘기면 충돌 확정
            if (v >= 0.5f * c.second) { cand = null; return }
            if (ms - c.first >= IMPACT_HOLD_MS) {
                cand = null; impactAt = ms
                log("감지: 충돌 — %.1f→%.1f m/s (10Hz 표본, 0.5초 유지)".format(c.second, c.third))
                onImpact?.invoke(c.second, c.third, fast.toList())
            }
            return
        }
        if (ms - impactAt < IMPACT_GAP_MS) return             // 10 s 에 한 번
        var k = fast.size - 2
        while (k >= 0) {
            val (tk, vk) = fast.elementAt(k)
            val dt = (ms - tk) / 1000.0
            if (dt > IMPACT_DT_MAX) break
            if (dt >= IMPACT_DT_MIN && vk - v >= IMPACT_DV && (vk - v) / dt >= IMPACT_A) { cand = Triple(ms, vk, v); return }
            k--
        }
    }

    companion object {
        const val IMPACT_A = 14.0         // m/s² — 제동으로는 못 내는 감속률(ETSI TR 103 460 표 3: 승용차 12 m/s² 초과는 불가능)
        const val IMPACT_DV = 2.2f        // m/s — 8 km/h(EDR 기록 최소 Δv, 49 CFR 563)
        const val IMPACT_DT_MIN = 0.08
        const val IMPACT_DT_MAX = 0.5
        const val IMPACT_HOLD_MS = 500L
        const val FAST_KEEP_MS = 2500L      // 10 Hz 표본 링 버퍼 길이(최근 2.5초)
        const val IMPACT_GAP_MS = 10_000L   // 충돌 감지 쿨다운
    }
}
