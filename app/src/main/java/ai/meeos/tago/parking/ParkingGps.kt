package ai.meeos.tago.parking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock

/**
 * 주차 위치 기억용 GPS 한 점: 시스템이 마지막으로 아는 위치(getLastKnownLocation)만 읽는다 — 새 위치 요청·상시 구독은 하지 않는다(응급 카드 EmergencyGps 와 같은 방식).
 * 차 안에서는 순정 내비가 위치를 계속 갱신하므로 낡은 정도(ageS)가 '끊김'의 신호가 된다(실차 확인 필요 — 에뮬레이터는 갱신이 없다).
 * 좌표는 기기 안에서만 쓰고 서버로 보내지 않는다.
 */
class ParkingGps(private val context: Context) {
    fun fix(): GpsFix? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        var best: android.location.Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.elapsedRealtimeNanos > best.elapsedRealtimeNanos) best = l
        }
        val l = best ?: return null
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9
        return GpsFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else null, age,
            if (l.hasBearing() && l.hasSpeed() && l.speed >= 1.5f) l.bearing.toDouble() else null)   // 방위는 움직일 때만 믿는다
    }
}
