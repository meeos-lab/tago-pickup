package ai.meeos.tago.app

import ai.meeos.tago.BuildConfig
import ai.meeos.tago.MainActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 시연 훅의 **유일한 입구**(2026-11-13): `adb shell am broadcast` 로만 들어온다.
 *
 * 예전에는 MainActivity 인텐트의 extras 로 받았는데, MainActivity 는 런처·Gleo 때문에 `exported=true` 라
 * 시연 빌드(-Pmeeos.demo=true)를 설치한 기기에서는 다른 앱도 같은 extras 를 보낼 수 있었다.
 * 이 수신기는 매니페스트에서 `android:permission="android.permission.DUMP"` 로 보호한다 —
 * DUMP 는 서명·특권 권한이라 일반 앱은 가질 수 없고 adb 셸(shell uid)만 가진다. 그래서 다른 앱이 보내면 시스템이 거부한다.
 * 제품 빌드(훅 꺼짐)는 수신기 자체가 `enabled=false`.
 *
 *   adb shell am broadcast -n ai.meeos.tago/ai.meeos.tago.app.DemoHookReceiver -a ai.meeos.tago.DEMO_HOOK --es utterance "응" --ez belt_sim true
 *
 * 앱이 떠 있으면 바로 적용하고, 아직 안 떴으면(콜드 스타트 전) 보관했다가 MainActivity 가 만들어질 때 적용한다.
 * `init_fail` 처럼 서비스가 시작되기 전에 읽어야 하는 값은 앱을 끈 채 방송 → 이어서 `am start` 순서로 보낸다.
 */
class DemoHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEMO_HOOKS || intent.action != ACTION) return   // 이중 방어: 훅이 꺼진 빌드는 받아도 버린다
        DemoHookBus.deliver(Intent(intent))
    }

    companion object { const val ACTION = "ai.meeos.tago.DEMO_HOOK" }
}

/** 수신기와 MainActivity 사이 다리. 한 프로세스 안에서만 쓴다 */
object DemoHookBus {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var target: MainActivity? = null
    private val pending = ArrayList<Intent>()

    fun deliver(i: Intent) = main.post {
        val t = target
        if (t != null) t.runDemoHooks(i) else synchronized(pending) { pending.add(i); while (pending.size > 32) pending.removeAt(0) }
    }

    /** 서비스를 시작하기 전에 읽는 값(init_fail): 보관된 방송에서 마지막 값을 꺼내되 지우지는 않는다 */
    fun pendingInt(key: String): Int = synchronized(pending) { pending.lastOrNull { it.hasExtra(key) }?.getIntExtra(key, 0) ?: 0 }

    /** MainActivity 가 다 만들어진 뒤: 보관분을 적용하고, 이후 방송은 바로 받는다 */
    fun attach(a: MainActivity) {
        target = a
        val list = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        for (i in list) a.runDemoHooks(i)
    }

    fun detach(a: MainActivity) { if (target === a) target = null }
}
