package ai.meeos.tago.road

import android.content.Context

/**
 * 우회 권고 준수 점수(FR-G-36 확장) — **차 안에만** 둔다. 허브로 보내지 않고 연구 기록(StudyLog)에도 넣지 않는다(docs/dev/83 개인정보).
 * c = (따른 권고 수 + 1) / (받은 권고 수 + 2) — 처음(0, 0)은 1/2. 권고를 받으면 c ≥ 1 − p 일 때 수락(허브가 정한 우회 비율 p 가 클수록 잘 받아들인다).
 * '따랐다' = 권고 뒤 허브 보기의 offRoute 가 true(내 위경도가 경로에서 1 km 넘게 벗어남). 나들목을 1 km 지나도 offRoute 가 false 면 '안 따름'.
 * offRoute 가 null(위경도를 보내지 않음)이면 판정하지 않는다 — 모르는 것을 '안 따름'으로 세지 않는다.
 */
class DetourComply(received: Int = 0, followed: Int = 0) {
    var received = received; private set
    var followed = followed; private set

    val score: Double get() = (followed + 1.0) / (received + 2.0)

    /** 권고를 받았다: 받은 수를 하나 늘리고 수락 여부를 돌려준다(c 는 늘리기 전 값으로 판단) */
    fun receive(p: Double): Boolean {
        val accept = score >= 1.0 - p
        received++
        return accept
    }

    fun follow() { followed = minOf(followed + 1, received) }

    companion object {
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
