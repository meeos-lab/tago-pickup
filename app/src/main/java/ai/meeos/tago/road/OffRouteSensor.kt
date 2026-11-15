package ai.meeos.tago.road

import ai.meeos.tago.app.AppLog
import ai.meeos.tago.engine.LinkHub
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/**
 * 경로 이탈(휴게소 진입 등) 판정 — **차 안에서만**. 자기 GPS 와 경로 선(앱에 들어 있는 경로 파일, 허브와 같은 투영)으로 횡거리를 구해
 * 1 s 상태에 `offRoute: true|false`(횡거리 ≥ `LinkHub.REST_LATERAL_M`) 하나만 싣는다. 위경도·횡거리 값은 보내지도 남기지도 않는다(docs/dev/83).
 * GPS 가 없거나(권한 없음 포함) 고정이 오래됐거나 정확도가 REST_LATERAL_M 보다 나쁘면 null — 그 상태에는 offRoute 를 넣지 않는다(허브 기본값).
 * 허브는 이 값을 휴게소 제외에만 쓴다(link_server._in_rest = LinkHub.inRest).
 * 권한은 묻지 않는다: ACCESS_FINE_LOCATION 이 이미 허용된 빌드(debug·qa·연구 빌드)에서만 동작한다.
 */
class OffRouteSensor(private val context: Context, private val log: AppLog, private val lateralOf: (Double, Double) -> Double) {
    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)
    @Volatile private var last: Location? = null
    private var on = false
    private var lastSent: Boolean? = null

    private val listener = LocationListener { l -> last = l }

    fun start() {
        val m = lm ?: return
        if (on) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        on = runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()); true }.getOrDefault(false)
        if (on) log.d("경로 이탈 판정: GPS 켬(값은 차 안에서만 쓴다)")
    }

    fun stop() {
        if (!on) return
        runCatching { lm?.removeUpdates(listener) }
        on = false; last = null; lastSent = null
    }

    /** 지금 1 s 상태에 실을 값(null = 넣지 않음) */
    fun offRoute(): Boolean? {
        val l = last ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else null
        val lateralM = if (acc != null && acc <= LinkHub.REST_LATERAL_M && ageMs <= FRESH_MS) lateralOf(l.latitude, l.longitude) else null
        val v = decide(acc, ageMs, lateralM)
        if (v != lastSent) { lastSent = v; log.demo("경로 이탈 판정: $v(정확도 ${acc?.let { "%.0f".format(it) } ?: "모름"} m)") }   // 시연 빌드 logcat 에만, 위경도·횡거리는 남기지 않는다
        return v
    }

    /** 우회 따름 판정용 횡거리(m): 정확도·신선도가 충분할 때만, 아니면 null. 값은 차 안에서만 쓰고 보내지 않는다(DetourComply.offRoute1km) */
    /** 마지막 GPS 고정(10초 안의 것만) — 시연 주행 판정(실제 위치 ↔ 경로 위 내 자리)에 쓴다. 밖으로 보내지 않는다 */
    fun freshFix(): Location? = last?.takeIf { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 <= FRESH_MS }

    fun lateralM(): Double? {
        val l = last ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        if (!l.hasAccuracy() || l.accuracy.toDouble() > LinkHub.REST_LATERAL_M || ageMs > FRESH_MS) return null
        return lateralOf(l.latitude, l.longitude).takeIf { !it.isNaN() }
    }

    companion object {
        const val FRESH_MS = 10_000L   // 이보다 오래된 GPS 고정은 쓰지 않는다

        /** 판정식(시험용 순수 함수): 정확도를 모르거나 REST_LATERAL_M 보다 나쁘거나 고정이 FRESH_MS 보다 오래되거나 횡거리를 못 구하면(NaN) null,
         *  아니면 횡거리 ≥ REST_LATERAL_M */
        fun decide(accuracyM: Double?, ageMs: Long, lateralM: Double?): Boolean? {
            if (accuracyM == null || accuracyM > LinkHub.REST_LATERAL_M || ageMs > FRESH_MS || lateralM == null || lateralM.isNaN()) return null
            return lateralM >= LinkHub.REST_LATERAL_M
        }
    }
}
