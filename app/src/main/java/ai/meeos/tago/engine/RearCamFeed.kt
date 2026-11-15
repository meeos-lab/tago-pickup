package ai.meeos.tago.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * 언리얼 → 앱 역방향 연동(2026-10-30): 실시간 장면의 아이오닉 뒤 범퍼 카메라(SceneCapture2D)가 기어 R 일 때 JPEG 를 15Hz 로
 * `POST /api/rearcam`(adb forward 8791→8790)으로 보내고, 뒤 범퍼 초음파 센서처럼 광선 5개로 잰 거리를 `POST /api/rearsensor` 로 보낸다.
 * 앱은 Pleos 기어 신호가 R 이고 프레임이 신선할 때(1.5초 안) 후방 카메라 화면을 띄운다. 제품에서는 실차 후방 카메라·PDC 신호가 이 자리를 맡는다.
 */
object RearCamFeed {
    @Volatile var frame: ByteArray? = null; private set
    /** 서버 스레드에서 미리 디코드한 비트맵 — 화면 그릴 때 메인 스레드에서 JPEG 를 풀지 않는다(지연·끊김 원인) */
    @Volatile var bitmap: Bitmap? = null; private set
    @Volatile var frameAt = 0L; private set
    @Volatile var frames = 0; private set
    /** 뒤 센서(m): left·center·right, 없으면 null(범위 밖) */
    @Volatile var left: Float? = null; private set
    @Volatile var center: Float? = null; private set
    @Volatile var right: Float? = null; private set
    @Volatile var sensorAt = 0L; private set
    /** 새 프레임·센서가 오면(서버 스레드) 부른다 — AppController 가 메인 스레드로 넘겨 화면을 다시 그린다 */
    @Volatile var onUpdate: (() -> Unit)? = null

    fun putFrame(jpeg: ByteArray) {
        bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull()
        frame = jpeg; frameAt = System.currentTimeMillis(); frames++
        onUpdate?.invoke()
    }

    fun putSensor(o: JSONObject) {
        fun f(k: String): Float? = if (o.isNull(k) || !o.has(k)) null else o.optDouble(k).toFloat()
        left = f("left"); center = f("center"); right = f("right"); sensorAt = System.currentTimeMillis()
        onUpdate?.invoke()
    }

    fun fresh(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 1500) = frame != null && now - frameAt < maxAgeMs
    fun sensorFresh(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 1500) = now - sensorAt < maxAgeMs
}
