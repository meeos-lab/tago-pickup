package ai.meeos.tago.app

import ai.meeos.tago.car.CarSignals
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

    fun say(text: String) = voice.say(text)
    fun epoch() = System.currentTimeMillis() / 1000.0
}
