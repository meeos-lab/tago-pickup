package ai.meeos.tago.parking

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * 기둥 사진 → 글 줄(ML Kit 한국어 텍스트 인식, 번들 모델 — 기기 안에서만 돈다). 사진 원본은 이 함수 안에서만 살고 끝나면 비트맵을 비운다(저장·전송 없음).
 * Edition.roadLink 판 전용(다른 판은 이 경로를 부르지 않는다). 해석은 PillarParser.
 */
class PillarOcr {
    private var rec: TextRecognizer? = null

    /** base64 JPEG → 줄 목록(메인 스레드 콜백). 해독·인식 실패는 null */
    fun recognize(b64: String, done: (List<String>?) -> Unit) {
        val bmp = runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        if (bmp == null) { done(null); return }
        // 인식기 만들기도 실패할 수 있다(릴리스 R8 이 ML Kit 구성 요소를 지웠을 때 NPE — 11-13 실측). 어떤 실패도 앱을 죽이지 않고 null 로 끝낸다
        val r = rec ?: runCatching { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }.getOrNull()?.also { rec = it }
        if (r == null) { bmp.recycle(); done(null); return }
        runCatching {
            r.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text -> bmp.recycle(); done(text.textBlocks.flatMap { b -> b.lines.map { it.text } }) }
                .addOnFailureListener { bmp.recycle(); done(null) }
        }.onFailure { bmp.recycle(); done(null) }
    }

    fun close() { runCatching { rec?.close() }; rec = null }
}
