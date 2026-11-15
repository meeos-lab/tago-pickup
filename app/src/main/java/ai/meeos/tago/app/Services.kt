package ai.meeos.tago.app

import ai.meeos.tago.R
import ai.meeos.tago.car.CarSignals
import ai.meeos.tago.engine.WarpClock
import ai.meeos.tago.navi.NaviBridge
import ai.meeos.tago.net.ApiClient
import ai.meeos.tago.voice.VoiceAssistant
import android.os.Handler
import java.util.concurrent.ExecutorService

/** 컨트롤러들이 함께 쓰는 것. 지역은 대기 화면에서만 바뀐다(AppController.setRegion) */
class Services(
    val main: Handler,
    val io: ExecutorService,
    val log: AppLog,
    val texts: Texts,
    val api: ApiClient,
    val car: CarSignals,
    val voice: VoiceAssistant,
    val navi: NaviBridge,
    val dialog: Dialog,
) {
    var region: Region = Region.DAECHI
    val lang: String get() = region.lang
    /** 지역 프로필(assets regions/<id>.json)의 거리 단위 "km"·"mi"(어바인만 mi). MainActivity 가 자산 읽기를 잇는다; 없으면 km */
    var regionUnits: (String) -> String = { "km" }
    val units: String get() = regionUnits(region.id)

    /** 화면을 다시 그리라는 신호(AppController 가 잇는다) */
    var render: () -> Unit = {}

    /** 화면 하단 토스트(3초) */
    var toast: (String) -> Unit = {}

    /** 내장 서버를 못 띄웠을 때의 안내 문장(AppController.serverFailed). 있으면 함께 가기·픽업을 시작하지 않고 이 문장을 보인다 */
    var serverError: String? = null

    /** 순정 내비에 요청을 보내지 못했을 때(내비 없음·준비 안 됨·예외) 운전자에게 한 번 알린다(V-05) */
    fun naviFailed() { say(texts[R.string.say_navi_failed]); toast(texts[R.string.toast_navi_failed]) }

    fun say(text: String, key: String? = null, fresh: (() -> String?)? = null, onDropped: (() -> Unit)? = null) = voice.say(text, key, fresh, onDropped)
    /** 허브에 보내는 시각. 시연 훅 ff(빠르게 감기)가 배율을 올리면 그만큼 빨리 간다 — 평소(배율 1)에는 실제 시각 */
    val clock = WarpClock { System.currentTimeMillis() / 1000.0 }
    fun epoch() = clock.now()
}
