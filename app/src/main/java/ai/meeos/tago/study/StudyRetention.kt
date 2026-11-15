package ai.meeos.tago.study

import java.io.File

/**
 * 연구 기록 보존 기한(B-16, FR-C-06). 앱 내부 저장소 files/study/ 의 세션 파일(<세션>.jsonl)은
 * 마지막으로 쓴 지 KEEP_DAYS 일이 지나면 지운다. 지금 쓰고 있는 세션 파일은 오래돼 보여도 지우지 않는다.
 * 내보내기가 앱 외부 폴더에 만든 사본(StudyExport)도 같은 기한으로 지운다(ResponseLog.purgeOld 가 두 폴더에 부른다).
 * 앱이 시작될 때(restore)와 새 세션이 시작될 때(start) 부른다. 내보내기를 하려면 그 안에 해야 한다.
 *
 * 시계 가드(2026-11-13 차량 앱 검증): 헤드유닛은 전원이 끊기면 시계가 1970·2000 년으로 돌아갔다가 위성·망으로 맞춰질 수 있다.
 * 시계가 틀린 동안 쓴 파일은 수정 시각이 옛날이라, 시계가 맞춰진 뒤 첫 실행에서 "아주 오래됐다"며 통째로 지워진다(에뮬레이터에서 재현).
 * 그래서 ① 지금 시각이 MIN_VALID_CLOCK_MS 보다 이르면(시계가 아직 틀림) 아무것도 지우지 않고, ② 수정 시각이 그보다 이른 파일(시계가 틀린 때 쓴 것)은 나이를 알 수 없으니 둔다.
 */
object StudyRetention {
    const val KEEP_DAYS = 30

    /** 이 앱이 나온 시기(2026-11-01 UTC). 이보다 이른 시계 값은 틀린 시계로 본다 */
    const val MIN_VALID_CLOCK_MS = 1_793_491_200_000L

    /** dir 안에서 keep 이 아니고 마지막 수정이 nowMs 보다 keepDays 일 넘게 오래된 .jsonl 파일을 지운다. 지운 개수를 돌려준다 */
    fun purge(dir: File, nowMs: Long, keepDays: Int = KEEP_DAYS, keep: File? = null): Int {
        if (nowMs < MIN_VALID_CLOCK_MS) return 0   // 지금 시계를 믿을 수 없다 — 나이를 셀 수 없으니 지우지 않는다
        val limit = nowMs - keepDays * 86_400_000L
        var n = 0
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile || !f.name.endsWith(".jsonl") || f == keep) continue
            val mod = f.lastModified()
            if (mod < MIN_VALID_CLOCK_MS) continue   // 시계가 틀린 때 쓴 파일: 나이를 알 수 없다 — 둔다
            if (mod < limit && f.delete()) n++
        }
        return n
    }
}
