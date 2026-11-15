package ai.meeos.tago.study

import java.io.File

/**
 * 연구 기록 파일의 첫 줄(세션 머리 `"rec":"session"`)이 있는지(V-12, 2026-11-13). 전원이 끊기면 아직 디스크에 안 내려간 첫 줄이 사라져
 * 파일이 0 바이트가 되거나 앞부분이 잘린다(에뮬레이터 강제 종료로 확인) — 그 파일에 이어 쓰면 참가자 정보 없는 기록이 된다. 순수 파일 검사라 단위 시험(StudyFileCheckTest).
 */
object StudyFileCheck {
    /** 파일이 없거나 비었거나, 첫 줄이 온전한 session 머리가 아니면 참 */
    fun headMissing(f: File?): Boolean {
        if (f == null || !f.isFile || f.length() == 0L) return true
        val first = runCatching { f.bufferedReader(Charsets.UTF_8).use { it.readLine() } }.getOrNull() ?: return true
        return runCatching { org.json.JSONObject(first).optString("rec") != "session" }.getOrDefault(true)
    }
}
