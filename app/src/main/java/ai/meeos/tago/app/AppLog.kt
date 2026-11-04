package ai.meeos.tago.app

import android.os.Handler
import android.util.Log

/**
 * 앱 로그: logcat(태그 Meeos) + 개발 패널에 보일 최근 줄. 개인정보(링크·세션 id)는 demo()로만 남긴다 — 시연 빌드에서만 찍힌다.
 */
class AppLog(private val main: Handler, private val demoHooks: Boolean) {
    val lines = ArrayDeque<String>()
    var onChange: () -> Unit = {}

    fun d(raw: String) {
        val msg = if (demoHooks) raw else raw.replace(SESSION_ID, "/session/<sid>")   // 제품 빌드에서는 오류 문구에 섞인 세션 id 도 남기지 않는다
        Log.d(TAG, msg)
        main.post {
            lines.addFirst(msg)
            while (lines.size > KEEP) lines.removeLast()
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
    }
}
