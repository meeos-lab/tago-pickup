package ai.meeos.tago.study

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 연구 기록 내보내기: 세션 JSONL 을 공유 시트로 넘긴다(FileProvider, 읽기 권한만 잠깐). 차량에는 공유 받을 앱이 없을 때가 많아
 * 늘 앱 외부 폴더(Android/data/<패키지>/files/study/)에도 복사해 둔다 — PC 에서 `adb pull` 로 가져간다.
 * 결과 문장(토스트용)을 돌려준다: true = 공유 시트를 띄움, false = 폴더에만 복사
 */
object StudyExport {
    fun share(activity: Activity, file: File): Pair<Boolean, File?> {
        if (!file.exists()) return false to null
        val outDir = File(activity.getExternalFilesDir(null), "study").also { it.mkdirs() }
        val copy = runCatching { File(outDir, file.name).also { file.copyTo(it, overwrite = true) } }.getOrNull()
        val uri = runCatching { FileProvider.getUriForFile(activity, activity.packageName + ".study", file) }.getOrNull() ?: return false to copy
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (send.resolveActivity(activity.packageManager) == null) return false to copy
        return runCatching { activity.startActivity(Intent.createChooser(send, file.name)); true }.getOrDefault(false) to copy
    }
}
