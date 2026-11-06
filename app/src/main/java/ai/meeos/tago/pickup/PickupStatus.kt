package ai.meeos.tago.pickup

/**
 * 픽업 진행 상태(서버 세션 status 와 같은 낱말). 글자 그대로 비교하면 오타가 컴파일에 걸리지 않아 이름으로 쓴다.
 * 흐름: IDLE → WAITING_RIDER(링크 보냄) → PROPOSED(만날 곳 제안) → CONFIRMED(확정) → ARRIVED(도착) → BOARDED(탔음) → DROPPED(내림)
 * 값은 엔진(MeeosEngine·meeos_server.py)이 주고받는 문자열 그대로라 바꾸면 안 된다.
 */
object PickupStatus {
    const val IDLE = "idle"
    const val WAITING_RIDER = "waiting_rider"
    const val PROPOSED = "proposed"
    const val CONFIRMED = "confirmed"
    const val ARRIVED = "arrived"
    const val BOARDED = "boarded"
    const val DROPPED = "dropped"
}

/** 가는 길 포장 주문 단계(NearbyOffers.orderStage). NONE = 주문 없음 */
object OrderStage {
    const val NONE = ""
    const val LISTEN = "listen"     // 품목을 듣는 중
    const val CONFIRM = "confirm"   // 받아쓴 주문을 확인하는 중
    const val PLACED = "placed"     // 주문함 — 매장을 경유지로 가는 중
    const val DONE = "done"         // 받아 옴
}
