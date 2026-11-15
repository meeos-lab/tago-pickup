package ai.meeos.tago.parking

import org.json.JSONObject

/**
 * 마지막 주차 기록 보관 형식(새 기능 H, 11-13 채점 1바퀴 — "돌아올 때 무엇을 보는가"). 앱 전용 저장소(SharedPreferences)에 한 건만.
 * 넣는 것: 층·범위의 다른 끝·출처·기둥·신뢰도·추정 층·근거(경사로 수·건축물대장 지하 층수)·기록 벽시계. **입구 좌표는 넣지 않는다**(83 개인정보 원칙).
 * MAX_AGE_MS 가 지나면 버린다. 깨진 값·모르는 판이면 null.
 */
object ParkStore {
    const val PREFS = "park_last"
    const val KEY = "rec"
    const val VERSION = 1
    const val MAX_AGE_MS = 72L * 3600_000L   // 사흘 — 그보다 오래 세워 둔 차는 기록이 맞는지 알 수 없다

    fun encode(r: ParkRecord, wallMs: Long): String = JSONObject().put("v", VERSION).put("wall", wallMs)
        .put("floor", r.floor ?: JSONObject.NULL).put("alt", r.alt ?: JSONObject.NULL).put("src", r.floorSource)
        .put("pillar", r.pillar ?: JSONObject.NULL).put("conf", r.confidence.name).put("est", r.estFloor ?: JSONObject.NULL)
        .put("ramps", r.rampRuns).put("bldgB", r.bldgBasement ?: JSONObject.NULL).put("bldgX", r.bldgConflict).put("h", r.heightUsed).toString()

    /** (기록, 기록 벽시계) — 없거나 MAX_AGE_MS 넘었거나(미래 시각 포함) 깨졌으면 null */
    fun decode(text: String?, nowWallMs: Long): Pair<ParkRecord, Long>? {
        if (text.isNullOrEmpty()) return null
        return runCatching {
            val o = JSONObject(text)
            if (o.optInt("v") != VERSION) return null
            val wall = o.getLong("wall")
            if (wall > nowWallMs || nowWallMs - wall > MAX_AGE_MS) return null
            fun int(k: String): Int? = if (o.isNull(k)) null else o.getInt(k)
            val src = o.getString("src").takeIf { it in setOf("est", "ocr", "user") } ?: return null
            val pillar = if (o.isNull("pillar")) null else o.getString("pillar").take(16)
            ParkRecord(atMs = 0L, entrance = null, floor = int("floor"), confidence = Confidence.valueOf(o.getString("conf")), pillar = pillar,
                floorSource = src, estFloor = int("est"), alt = int("alt"), rampRuns = o.optInt("ramps", 0), bldgBasement = int("bldgB"),
                bldgConflict = o.optBoolean("bldgX", false), heightUsed = o.optBoolean("h", false)) to wall
        }.getOrNull()
    }

    /** 경과 시간 글감: 10분 미만은 null, 1시간 미만은 (분, false), 그 밖은 (시간, true) */
    fun ageText(ageMs: Long): Pair<Int, Boolean>? = when {
        ageMs < 10 * 60_000L -> null
        ageMs < 3600_000L -> (ageMs / 60_000L).toInt() to false
        else -> (ageMs / 3600_000L).toInt() to true
    }

    // ---------------------------------------------------------------- 차 관성 센서 장착 기울기(11-14). 센서 이름이 다르면(다른 헤드유닛·센서) 버린다. 위치 정보 없음
    const val MOUNT_KEY = "imu_mount"

    fun encodeMount(offsetDeg: Double, seconds: Double, sensor: String): String =
        JSONObject().put("v", VERSION).put("off", offsetDeg).put("s", seconds).put("sensor", sensor.take(120)).toString()

    /** (장착 기울기 °, 배운 시간 s) — 없거나 깨졌거나 센서가 다르면 null */
    fun decodeMount(text: String?, sensor: String): Pair<Double, Double>? {
        if (text.isNullOrEmpty()) return null
        return runCatching {
            val o = JSONObject(text)
            if (o.optInt("v") != VERSION || o.optString("sensor") != sensor.take(120)) return null
            val off = o.getDouble("off"); val sec = o.getDouble("s")
            if (off.isNaN() || sec.isNaN() || kotlin.math.abs(off) > 15.0 || sec <= 0) null else off to sec
        }.getOrNull()
    }
}
