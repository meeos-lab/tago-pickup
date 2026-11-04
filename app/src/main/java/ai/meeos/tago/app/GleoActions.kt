package ai.meeos.tago.app

import ai.meeos.tago.BuildConfig
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Gleo(음성 비서) 앱 액션 — assets/schema.json 의 START_PICKUP("지수 데리러 가자"), FIND_NEARBY("근처 주차장").
 * 인자는 ACTION_PARAM(JSON 문자열). 처리 결과는 APP_ACTION_RESPONSE 브로드캐스트로 돌려준다.
 */
object GleoActions {
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    private val NEARBY_KINDS = setOf("parking", "toilet", "drivethru", "takeout")

    fun handle(intent: Intent, app: AppController, context: Context) {
        when (intent.action) {
            "START_PICKUP" -> if (BuildConfig.PICKUP) startPickup(intent, app, context) else respond(context, "START_PICKUP", "fail", "Pickup is not part of this app.")
            "FIND_NEARBY" -> if (BuildConfig.PICKUP) findNearby(intent, app, context) else respond(context, "FIND_NEARBY", "fail", "Nearby search is not part of this app.")
            "START_ROAD_LINK" -> startRoadLink(app, context)
        }
    }

    /** 길벗: 같은 길의 차들과 연결(도로 링크 시작). 이미 켜져 있거나 다른 일이 진행 중이면 알려 준다 */
    private fun startRoadLink(app: AppController, context: Context) {
        if (!BuildConfig.ROAD_LINK) return respond(context, "START_ROAD_LINK", "fail", "Road link is not part of this app.")
        if (app.road.active) return respond(context, "START_ROAD_LINK", "success", "Already linked with cars on this road.")
        if (app.pickup.active || app.emergency.active) return respond(context, "START_ROAD_LINK", "fail", "Finish the current pickup or emergency first.")
        app.startRoad()
        respond(context, "START_ROAD_LINK", "success", "Linking with cars on this road; hazards ahead will be announced.")
    }

    private fun param(intent: Intent): JSONObject {
        val raw = intent.getStringExtra("ACTION_PARAM").orEmpty().trim().removeSurrounding("\"").replace("\\\"", "\"")
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun startPickup(intent: Intent, app: AppController, context: Context) {
        val name = param(intent).optString("memberName").trim()
        val members = app.s.region.members
        // 이름이 없으면 첫 가족(시연: "데리러 가자"), 모르는 이름이면 아무에게도 링크를 보내지 않는다
        val m = if (name.isEmpty()) members.first() else members.firstOrNull { it.name == name }
            ?: return respond(context, "START_PICKUP", "fail", "No family member named '$name'. Known: ${members.joinToString { it.name }}.")
        // 진행 중인 픽업을 말 한마디로 덮어쓰지 않는다(같은 사람이면 그대로 두고 성공으로 답한다)
        val p = app.pickup
        if (p.active && p.status !in setOf("idle", "dropped")) {
            return if (p.member?.name == m.name) respond(context, "START_PICKUP", "success", "A pickup for ${m.name} is already in progress.")
            else respond(context, "START_PICKUP", "fail", "A pickup for ${p.member?.name} is in progress; finish it first.")
        }
        if (app.startPickup(m)) respond(context, "START_PICKUP", "success", "Starting a pickup for ${m.name}; a link will be sent.")
        else respond(context, "START_PICKUP", "fail", "An emergency card is open; end it before starting a pickup.")
    }

    private fun findNearby(intent: Intent, app: AppController, context: Context) {
        val kind = param(intent).optString("kind").ifEmpty { "parking" }
        val ok = kind in NEARBY_KINDS
        if (ok) app.pickup.nearby.ask(kind)
        respond(context, "FIND_NEARBY", if (ok) "success" else "fail", "Looking for nearby $kind.")
    }

    private fun respond(context: Context, function: String, status: String, message: String) {
        context.sendBroadcast(Intent(RESPONSE_ACTION)
            .putExtra("packageName", context.packageName)
            .putExtra("response", JSONObject().put("functionName", function).put("status", status).put("message", message).put("mode", "silent").toString()))
    }
}
