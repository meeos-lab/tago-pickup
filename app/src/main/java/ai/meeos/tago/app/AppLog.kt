package ai.meeos.tago.app

import android.os.Handler
import android.util.Log

/**
 * 앱 로그: logcat(태그 Meeos) + 개발 패널에 보일 최근 줄. 개인정보(링크·세션 id)는 demo()로만 남긴다 — 시연 빌드에서만 찍힌다.
 */
class AppLog(private val main: Handler, private val demoHooks: Boolean) {
    private val recent = ArrayDeque<String>()
    /** 최근 줄(새 것이 앞). 메인 스레드에서만 읽는다 — 밖에서는 고칠 수 없게 읽기 전용으로 내보낸다 */
    val lines: List<String> get() = recent
    var onChange: () -> Unit = {}

    fun d(raw: String) {
        // 제품 빌드에서는 오류 문구(HTTP 예외는 URL 을 담는다)에 섞인 세션 id·가족 그룹 id·차량 id 도 남기지 않는다
        val msg = if (demoHooks) raw else raw.replace(SESSION_ID, "/session/<sid>").replace(LINK_GROUP, "/api/link/<gid>").replace(CAR_ID, "me=<car>")
        Log.d(TAG, msg)
        main.post {
            recent.addFirst(msg)
            while (recent.size > KEEP) recent.removeLast()
            onChange()
        }
    }

    /** 시연용 정보(참가 링크·세션 id 같은 식별자). 제품 빌드에서는 남기지 않는다 */
    fun demo(msg: String) {
        if (demoHooks) d(msg)
    }

    companion object {
        const val TAG = "Meeos"
        private const val KEEP = 6
        private val SESSION_ID = Regex("/session/[0-9a-f]{16}")
        private val LINK_GROUP = Regex("/api/link/[^/?#\\s]+")
        private val CAR_ID = Regex("me=[^&#\\s]+")
    }
}
