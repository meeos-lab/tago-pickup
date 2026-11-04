package ai.meeos.tago.pickup

import ai.meeos.tago.R
import ai.meeos.tago.app.Question
import ai.meeos.tago.app.Services
import org.json.JSONObject
import kotlin.math.abs

/**
 * 근처 장소 제안과 주문(목업): 주차장(정차 한도) · 화장실 · 드라이브스루 · 가는 길 포장.
 * 제안은 한 문장으로 묻고, 주문은 말로 받아 다시 읽어 확인한 뒤 주문 카드를 띄운다.
 * 배민·쿠팡이츠는 외부 주문 API를 공개하지 않아(2026-10 조사) 제휴 계약 뒤 OrderPartner 를 바꿔 끼운다. 시연은 매장에서 결제한다.
 */
class NearbyOffers(private val s: Services, private val pickup: PickupController) {
    /** 주문 단계: "" → listen(주문 듣기) → confirm(다시 읽고 확인) → placed(주문 카드) → done */
    var orderStage = ""
        private set
    var orderItems: List<Pair<Int, String>> = emptyList()
        private set
    var orderPlace: JSONObject? = null
        private set
    var orderNo = ""
        private set
    /** 지금 제안한 장소와 종류(parking / toilet / drivethru / takeout) */
    var offer: JSONObject? = null
        private set
    var offerKind = "parking"
        private set

    private var dtSuggested = false               // 이번 픽업에서 포장·드라이브스루를 이미 제안했는지
    private var storeArrived = false              // 경유지(매장) 앞에 섰는지 — 앱이 판단(엔진 viaX 근처 정차)
    private var lead = ""                         // 자동 제안 때 앞에 붙이는 말("지수 도착이 차량보다 6분 늦습니다. ")

    private val t get() = s.texts

    fun reset() {
        orderStage = ""; orderItems = emptyList(); orderPlace = null; orderNo = ""; offer = null; offerKind = "parking"
        dtSuggested = false; storeArrived = false; lead = ""
    }

    /** 자동 제안이 가능한지: 아직 제안한 적 없고, 질문 중이 아니며, 응급이 아닐 때 */
    fun canSuggest(): Boolean = !dtSuggested && !s.dialog.awaiting && !emergencyActive()
    var emergencyActive: () -> Boolean = { false }

    /** 아이가 늦을 때: 그 시간 안에 들를 수 있는 가는 길 포장(기본) 또는 드라이브스루를 한 번만 제안 */
    fun suggestTakeout(leadText: String, kind: String = "takeout") {
        dtSuggested = true
        lead = leadText
        ask(kind, auto = true)
    }

    /** 근처 장소를 받아 한 문장으로 묻는다. parking: 만남 지점 근처 / toilet: 차 위치 근처. auto: 차가 먼저 묻는 경우 */
    fun ask(kind: String, auto: Boolean = false) {
        val id = pickup.sessionId
        if (id == null) {
            s.say(if (kind == "toilet") t[R.string.say_toilet_needs_pickup] else t[R.string.say_parking_needs_pickup])
            return
        }
        s.api.get("/api/session/$id/nearby?kind=$kind", onError = { e -> s.log.d("근처 장소 조회 실패: ${e.message}") }) { r ->
            val first = r.optJSONArray("items")?.optJSONObject(0)
            // 만남 지점과 같은 상호면(예: GS25) 헷갈리지 않게 '다른 지점'을 붙인다
            val targetName = pickup.targetName()
            val p = first?.let { if (kind == "takeout" && it.optString("name") == targetName) JSONObject(it.toString()).put("name", t[R.string.another_branch, it.optString("name")]) else it }
            if (p == null) {
                if (!auto) s.say(when (kind) {
                    "toilet" -> t[R.string.say_no_toilet]
                    "drivethru" -> t[R.string.say_no_drivethru]
                    "takeout" -> t[R.string.say_no_takeout]
                    else -> t[R.string.say_no_parking]
                })
                lead = ""
                return@get
            }
            offer = p
            offerKind = kind
            val question = if (kind == "drivethru" || kind == "takeout")
                Question(Question.Kind.DRIVETHRU, t[R.string.btn_yes_stop], t[R.string.btn_no_thanks], t[R.string.listen_yes_no], onAnswer = ::answerDriveThru)
            else if (kind == "toilet")
                Question(Question.Kind.PARKING, t[R.string.btn_yes_guide], t[R.string.btn_no_thanks], t[R.string.listen_yes_no], onAnswer = ::answerParking)
            else
                Question(Question.Kind.PARKING, t[R.string.btn_yes_guide], t[R.string.btn_wait_here], t[R.string.listen_yes_no], onAnswer = ::answerParking)
            s.dialog.ask(question, offerSpeech(kind, p, auto))
            lead = ""
            s.render()
        }
    }

    private fun offerSpeech(kind: String, p: JSONObject, auto: Boolean): String {
        val name = p.getString("name")
        return when (kind) {
            "takeout" -> {
                val slack = p.optInt("slackSec")
                val late = p.optInt("lateSec").let { if (it < 45) 0 else it }   // 45초 미만은 '늦지 않음'으로
                val why = if (lead.isNotEmpty()) "" else t[R.string.takeout_slack, pickup.member?.name ?: "", t.mins(slack)]
                val text = if (late == 0) t[R.string.say_takeout_offer, lead + why, name] else t[R.string.say_takeout_offer_late, lead, name, t.mins(late)]
                if (s.lang == "en") text.replaceFirstChar { it.uppercase() } else text
            }
            "drivethru" -> {
                val where = if (p.optBoolean("ahead")) t[R.string.where_ahead, p.getInt("dist")] else t[R.string.where_away, p.getInt("dist")]
                t[R.string.say_drivethru_offer, lead, where, name]
            }
            "toilet" -> t[R.string.say_toilet_offer, name, p.getInt("dist"), if (p.optBoolean("inside")) t[R.string.toilet_inside_gate] else ""]
            else -> {
                val extra = listOfNotNull(
                    p.optString("fee").takeIf { it.isNotEmpty() },
                    p.optString("hours").takeIf { it.isNotEmpty() }?.let { t[R.string.open_until, it.substringAfter("–")] },
                ).joinToString(", ").let { if (it.isNotEmpty()) "$it. " else "" }
                t[R.string.say_parking_offer, if (auto) t[R.string.parking_limit_warning] else "", name, p.getInt("dist"), extra]
            }
        }
    }

    private fun answerParking(yes: Boolean) {
        val p = offer ?: return
        if (offerKind == "toilet") {
            if (yes) {
                val c = p.getJSONArray("curb")
                if (s.navi.routeTo(c.getDouble(0), c.getDouble(1), "${p.getString("name")} (Tago)", "nearby")) pickup.naviRouted = true
                s.say(t[R.string.say_guiding_to, t.ro(p.getString("name"))])
            } else s.say(t[R.string.say_ok])
            s.render(); return
        }
        if (!yes) { s.say(t[R.string.say_wait_here]); s.render(); return }
        pickup.parkedWaiting = true
        val curb = p.getJSONArray("curb")
        if (s.navi.routeTo(curb.getDouble(0), curb.getDouble(1), "${p.getString("name")} (Tago)", "nearby")) pickup.naviRouted = true   // 재약속 때 내비를 다시 잡도록
        s.say(t[R.string.say_parking_guide, t.ro(p.getString("name")), t.subj(pickup.member?.name ?: "")])
        s.render()
    }

    // ------------------------------------------------------------ 주문(목업)

    /** 주문을 실제로 넘기는 곳. 제휴 후: BaeminPickupPartner, CoupangEatsPickupPartner(포장 주문 API), 매장 직접 주문 등 */
    interface OrderPartner {
        val label: String
        fun place(store: String, items: List<Pair<Int, String>>): String   // 주문 번호
    }

    private object DemoOrderPartner : OrderPartner {
        override val label = "demo"
        override fun place(store: String, items: List<Pair<Int, String>>) = "D" + (System.currentTimeMillis() / 1000 % 10000)
    }

    private val orderPartner: OrderPartner = DemoOrderPartner

    // 메뉴 키워드(목업 데이터): 매장 메뉴 API가 없어서 말에서 품목만 뽑는다. 실제는 매장 주문 시스템과 연동해야 한다
    private val menu = listOf(
        "burger|버거|햄버거" to ("Famous Star burger" to "버거"), "fries|감자|감튀" to ("Fries" to "감자튀김"),
        "shake|쉐이크|셰이크" to ("Shake" to "셰이크"), "tender|nugget|너겟|치킨" to ("Chicken tenders" to "치킨 텐더"),
        "taco|타코" to ("Taco" to "타코"), "burrito|부리토" to ("Burrito" to "부리토"),
        "coffee|latte|americano|커피|아메리카노|라떼" to ("Coffee" to "커피"), "juice|주스|쥬스" to ("Juice" to "주스"),
        "sandwich|샌드위치" to ("Sandwich" to "샌드위치"), "cookie|쿠키" to ("Cookie" to "쿠키"), "cake|케이크|케익" to ("Cake" to "케이크"),
        "milk|우유" to ("Milk" to "우유"), "soda|coke|drink|lemonade|콜라|음료|사이다" to ("Soda" to "콜라"),
    )
    private val numberWords = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "넷" to 4, "네" to 4)

    /** "two burgers and a soda" / "버거 두 개랑 콜라 하나" → [(2, 버거), (1, 콜라)] */
    fun parseOrder(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (seg in text.lowercase().split(Regex(",|\\band\\b|그리고|랑|하고|\\."))) {
            val item = menu.firstOrNull { Regex(it.first).containsMatchIn(seg) } ?: continue
            val qty = Regex("\\d+").find(seg)?.value?.toInt()
                ?: seg.split(Regex("\\s+")).firstNotNullOfOrNull { numberWords[it] }
                ?: numberWords.entries.firstOrNull { seg.contains(it.key) && it.key.length > 1 && it.key.all { c -> c in '가'..'힣' } }?.value ?: 1
            val name = if (s.lang == "ko") item.second.second else item.second.first
            val i = out.indexOfFirst { it.second == name }
            if (i >= 0) out[i] = (out[i].first + qty) to name else out += qty to name
        }
        return out
    }

    fun orderSummary() = orderItems.joinToString(", ") { (q, n) -> t[R.string.order_item, n, q] }

    /** 읽어 줄 때는 기호 없이: "Famous Star burger 2개" / "2 Famous Star burgers" */
    private fun orderSpoken() = orderItems.joinToString(", ") { (q, n) -> if (s.lang == "ko") "$n ${q}개" else "$q ${if (q > 1 && !n.endsWith("s")) n + "s" else n}" }

    private fun listenForOrder() {
        orderStage = "listen"
        s.dialog.ask(Question(Question.Kind.ORDER_TEXT, listenHint = t[R.string.listen_order], onText = ::takeOrder))
        s.render()
    }

    private fun takeOrder(text: String) {
        val items = parseOrder(text)
        if (items.isEmpty()) {
            s.dialog.ask(Question(Question.Kind.ORDER_TEXT, listenHint = t[R.string.listen_order], onText = ::takeOrder), t[R.string.say_order_not_understood])
            return
        }
        orderItems = items
        orderStage = "confirm"
        s.dialog.ask(Question(Question.Kind.ORDER_CONFIRM, t[R.string.btn_yes_order], t[R.string.btn_say_again], t[R.string.listen_yes_no], onAnswer = ::answerOrderConfirm),
            t[R.string.say_order_confirm, orderSpoken()])
        s.render()
    }

    private fun answerDriveThru(yes: Boolean) {
        if (!yes) { s.say(t[R.string.say_ok]); s.render(); return }
        val p = offer ?: return
        orderPlace = p
        s.say(if (offerKind == "takeout") t[R.string.say_order_prompt_takeout] else t[R.string.say_order_prompt_drivethru])
        listenForOrder()
    }

    private fun answerOrderConfirm(yes: Boolean) {
        val p = offer ?: return
        if (!yes) { s.say(t[R.string.say_say_again]); listenForOrder(); return }
        orderStage = "placed"; storeArrived = false
        orderNo = orderPartner.place(p.getString("name"), orderItems)
        // 매장을 경유지로 서버에 알린다 → 지도에 차 → 매장 → 만남 지점 실제 경로가 그려진다
        pickup.sessionId?.let { sid -> p.optJSONArray("curb")?.let { c -> pickup.post("/api/session/$sid/via", JSONObject().put("lat", c.getDouble(0)).put("lon", c.getDouble(1)).put("name", p.getString("name"))) } }
        s.log.d("주문(${orderPartner.label}) $orderNo: ${p.getString("name")} ${orderSummary()}")
        pickup.routeWithStop(p)                       // 순정 내비: 만날 곳까지, 매장 경유
        s.say(if (offerKind == "takeout") t[R.string.say_order_placed_takeout, p.getString("name")] else t[R.string.say_order_placed_drivethru])
        s.render()
    }

    /** 매장 앞에 서면 안내, 다시 출발하면 경유지를 내비·지도에서 지운다 */
    fun checkStoreStop() {
        val vx = pickup.lastState?.optDouble("viaX")?.takeIf { !it.isNaN() } ?: return
        if (orderStage != "placed") return
        val near = abs(pickup.progress - vx) < 60
        if (!storeArrived && near && s.car.speedMs < 1f) {
            storeArrived = true
            val sn = orderPlace?.optString("name") ?: ""
            val tx = pickup.targetX() ?: Double.NaN
            // 매장이 만날 곳 바로 옆이면(30m 안) 기다리는 동안 받아 오면 된다
            s.say(if (!tx.isNaN() && abs(vx - tx) < 30) t[R.string.say_store_next_door, sn] else t[R.string.say_store_arrived, sn])
        } else if (storeArrived && s.car.speedMs > 2f) {
            storeArrived = false; orderStage = "done"
            s.navi.clearWaypoints()
            pickup.sessionId?.let { pickup.post("/api/session/$it/via", JSONObject()) }
            if (pickup.status != "arrived") s.say(t[R.string.say_back_to_meeting_point])
            s.render()
        }
    }
}
