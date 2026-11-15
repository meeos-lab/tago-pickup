package ai.meeos.tago.road

import android.content.Context

/**
 * 우회 권고 준수 점수(FR-G-36 확장) — **차 안에만** 둔다. 허브로 보내지 않고 연구 기록(StudyLog)에도 넣지 않는다(docs/dev/83 개인정보).
 * c = (따른 권고 수 + 1) / (받은 권고 수 + 2) — 처음(0, 0)은 1/2. 권고를 받으면 c ≥ 1 − p 일 때 수락(허브가 정한 우회 비율 p 가 클수록 잘 받아들인다).
 * '따랐다' = 권고 뒤 허브 보기의 offRoute 가 true(내 위경도가 경로에서 1 km 넘게 벗어남). 나들목을 1 km 지나도 offRoute 가 false 면 '안 따름'.
 * offRoute 가 null(위경도를 보내지 않음)이면 판정하지 않는다 — 모르는 것을 '안 따름'으로 세지 않는다.
 * '받은 수'는 따름·안 따름 판정이 난 권고만 센다(accepts 는 세지 않고, 판정 때 judge 가 센다). 판정 전에 막힘이 풀리거나 다른 권고로 바뀌면 세지 않으므로
 * 판정 없이 받은 수만 늘어 c 가 계속 내려가는 일이 없다(FR-G-37).
 */
class DetourComply(received: Int = 0, followed: Int = 0) {
    var received = received; private set
    var followed = followed; private set

    val score: Double get() = (followed + 1.0) / (received + 2.0)

    /** 수락 여부(c ≥ 1 − p). 세지 않는다 — 받은 수는 판정이 날 때 judge 가 센다 */
    fun accepts(p: Double): Boolean = score >= 1.0 - p

    /** 판정이 난 권고 하나를 센다: 따랐으면 따른 수도 하나 늘린다 */
    fun judge(followedIt: Boolean) {
        received++
        if (followedIt) followed++
    }

    companion object {
        /** 경로에서 1 km 넘게 벗어났나(우회를 따랐나)의 근거: 허브 보기 offRoute(앱이 위경도를 보낸 때만 채워짐)가 있으면 그것, 없으면 차 안 GPS 횡거리(보내지 않음).
         *  둘 다 모르면 null — 판정을 미룬다(모르는 것을 '안 따름'으로 세지 않는다). B-86 */
        fun offRoute1km(viewOffRoute: Boolean?, localLateralM: Double?, thresholdM: Double): Boolean? =
            viewOffRoute ?: localLateralM?.let { it > thresholdM }

        private const val PREFS = "detour_comply"   // 앱 전용 저장소(기기 밖으로 나가지 않는다 — 백업 제외는 매니페스트 규칙을 따른다)

        fun load(context: Context): DetourComply {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DetourComply(p.getInt("received", 0), p.getInt("followed", 0))
        }

        fun save(context: Context, c: DetourComply) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("received", c.received).putInt("followed", c.followed).apply()
        }
    }
}
