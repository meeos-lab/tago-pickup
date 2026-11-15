package ai.meeos.tago.emergency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/**
 * 응급 카드용 차 안 GPS 고정(V-06): 시스템이 마지막으로 아는 위치(getLastKnownLocation)만 읽는다 — 새 위치 요청·상시 구독은 하지 않는다.
 * 위치 권한이 없거나 고정이 없거나 낡았으면(EmergencyPlace.usable) null. 좌표는 카드에 보이기만 하고 서버로 보내지 않는다.
 * 시연 훅 place_sim 이 값을 덮을 수 있다(에뮬레이터 GPS 가 없을 때의 '위치 있음' 경우 시험용).
 */
class EmergencyGps(private val context: Context) {
    /** 시연 훅 place_sim "37.40,127.10": 이 값이 있으면 실제 GPS 대신 쓴다. "off" 면 위치 없음, null 이면 실제 GPS */
    @Volatile var sim: Pair<Double, Double>? = null
    @Volatile var simNone = false

    /** (위도, 경도) 글자 쌍 — 쓸 만한 고정이 있을 때만 */
    fun coords(): Pair<String, String>? {
        if (simNone) return null
        sim?.let { return EmergencyPlace.coords(it.first, it.second) }
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
            if (!EmergencyPlace.usable(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else null, age)) continue
            if (best == null || l.elapsedRealtimeNanos > best.elapsedRealtimeNanos) best = l
        }
        return best?.let { EmergencyPlace.coords(it.latitude, it.longitude) }
    }
}
