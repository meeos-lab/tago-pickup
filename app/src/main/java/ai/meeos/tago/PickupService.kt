package ai.meeos.tago

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 픽업 중 앱을 살려 두는 포그라운드 서비스.
 * 순정 내비가 앞에 오면 Tago가 백그라운드(캐시) 프로세스가 되어 Android가 얼린다 → 아이 폰 연결(내장 서버)·도착/탑승 판단이 멈춘다.
 * 서비스는 프로세스 우선순위만 유지하고, 실제 일은 MainActivity·LocalServer가 그대로 한다.
 */
class PickupService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        val text = getString(if (BuildConfig.PICKUP) R.string.notif_pickup else R.string.notif_road)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, if (BuildConfig.PICKUP) getString(R.string.notif_channel) else text, NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(BuildConfig.APP_LABEL)
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "pickup"
        private const val ID = 7
        fun start(c: Context) {
            val i = Intent(c, PickupService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, PickupService::class.java)) }
    }
}
