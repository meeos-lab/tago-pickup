package ai.meeos.tago.app

import ai.meeos.tago.road.DriveRisk
import android.app.Activity
import android.content.Intent
import java.io.File

/**
 * 시연·시험용 인텐트 훅. BuildConfig.DEMO_HOOKS 가 켜진 빌드(debug, 또는 -Pmeeos.demo=true)에서만 읽는다 —
 * 외부 앱이 인텐트로 음성 명령·지역·센서 값을 넣을 수 있는 통로라 제품 빌드에서는 닫는다.
 *
 *   adb shell am start -n ai.meeos.tago/.MainActivity --es utterance "응"      인식된 말 넣기(에뮬레이터에 마이크가 없어서)
 *   --es region irvine · --ez belt_sim true · --ez convoy_sim true · --ez navi_all true · --ez routine_test true
 *   --es siren_wav test.wav   앱 외부 저장소(files/)의 16kHz 모노 wav 를 마이크 대신 흘려 넣는다(이름만, 경로·URL 불가)
 *   --es say_test "문장" · --es tone_test nav|media   음성 경로 진단
 */
object DemoHooks {
    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
    private const val MAX_TEXT = 200

    fun apply(intent: Intent, app: AppController, activity: Activity) {
        val s = app.s
        if (intent.hasExtra("navi_all")) s.navi.allowOverseas = intent.getBooleanExtra("navi_all", false)
        intent.getStringExtra("region")?.let { app.setRegion(it) }                       // Region.of 가 모르는 id 는 버린다
        intent.getStringExtra("tone_test")?.let { if (it == "nav" || it == "media") toneTest(it, s) }
        intent.getStringExtra("say_test")?.let { s.say(it.take(MAX_TEXT)) }
        if (intent.hasExtra("belt_sim")) { app.pickup.beltSim = intent.getBooleanExtra("belt_sim", false); s.log.d("시연 벨트 ${app.pickup.beltSim}") }
        if (intent.hasExtra("convoy_sim")) { app.road.simDrive = intent.getBooleanExtra("convoy_sim", false); s.log.d("시연 주행 ${app.road.simDrive}") }
        // 두 차 라이브 시연(앞차·뒷차 에뮬레이터): 출발 간격 → 링크 시작 → (뒷차) 앞차가 만든 가족 코드로 합류
        intent.getStringExtra("link_name")?.let { app.road.nameOverride = it.take(12) }
        if (intent.getBooleanExtra("probe_signals", false)) s.car.probeSignals()   // 설계 실측: 속도 콜백 주기·에어백 읽기
        // 사고 판단 시연: impact_sim "25,0"(충돌 표본), airbag_sim true(에어백 전개 — 에뮬레이터 VHAL 미지원), comm_sim true(보고 중단 = 통신 끊김)
        intent.getStringExtra("impact_sim")?.let { app.road.simImpact(it) }
        intent.getStringExtra("official_sim")?.let { app.road.simOfficial(it) }
        // 운전 묶음 흉내 "SPEEDING,BRAKES,FATIGUE" | "off"(사고 위험성 시연 — 과속 20초·급제동 두 번·2시간 운전을 기다리지 않게)
        intent.getStringExtra("risk_sim")?.let { v -> app.road.driveRisk.sim = if (v.trim() == "off") emptySet() else v.split(",").map { it.trim().uppercase() }.filter { it in DriveRisk.TAGS }.toSet(); s.log.d("운전 위험 시연 ${app.road.driveRisk.sim}") }
        // 외기 온도 흉내 "-2" | "off"(교량 결빙 주의 시연 — Virtual Console 에 외기 온도 조절이 없다)
        intent.getStringExtra("temp_sim")?.let { v -> s.car.simTemp = if (v.trim() == "off") null else v.trim().toFloatOrNull(); s.log.d("외기 온도 시연 ${s.car.simTemp}") }   // 순정 내비 돌발 정보 흉내 "ACCIDENT,2000"(에뮬레이터 내비는 성남 고정이라 경부선 돌발이 오지 않는다)
        if (intent.hasExtra("airbag_sim")) s.car.airbagSim = intent.getBooleanExtra("airbag_sim", false)
        if (intent.hasExtra("comm_sim")) { app.road.commSim = intent.getBooleanExtra("comm_sim", false); s.log.d("통신 끊김 시연 ${app.road.commSim}") }
        // 충전 시연: --es ev_sim "port=1,charge=0,soc=40" (에뮬레이터 Virtual Console 은 충전 상태 조작이 없다), "off" 면 실제 신호로
        intent.getStringExtra("ev_sim")?.let { spec ->
            val kv = spec.split(",").mapNotNull { it.trim().split("=").takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }.toMap()
            val car = s.car
            if (spec.trim() == "off") { car.simChargePort = null; car.simCharging = null; car.simSoc = null }
            else { kv["port"]?.let { car.simChargePort = it == "1" }; kv["charge"]?.let { car.simCharging = it == "1" }; kv["soc"]?.toIntOrNull()?.let { car.simSoc = it } }
            s.log.d("충전 시연: 충전구 ${car.chargePort} 충전 ${car.charging} 배터리 ${car.soc}%")
        }
        if (intent.hasExtra("link_offset")) { app.road.startOffset = intent.getIntExtra("link_offset", 0).toDouble(); s.log.d("도로 링크 출발 간격 ${app.road.startOffset}m") }
        if (intent.getBooleanExtra("road_start", false)) app.startRoad()
        if (intent.getBooleanExtra("group_start", false)) s.main.postDelayed({ app.roadPrimary() }, 1500)   // 앞차: 가족 그룹 만들기(코드는 로그·음성)
        intent.getStringExtra("link_code")?.let { code -> s.main.postDelayed({ app.road.joinGroup(code.take(8)) }, 1500) }
        intent.getStringExtra("siren_wav")?.let { name ->
            if (!SAFE_NAME.matches(name)) { s.log.d("사이렌 시험 파일 이름이 올바르지 않음"); return@let }
            val f = File(activity.getExternalFilesDir(null), name)
            if (f.exists()) app.road.sirenWav = f else s.log.d("사이렌 시험 파일 없음: ${f.path}")
        }
        if (intent.getBooleanExtra("routine_test", false)) s.main.postDelayed({ app.routine.prompt(app.routine.routines.first()) }, 2500)
        intent.getStringExtra("utterance")?.let { app.demoUtterance(it.take(MAX_TEXT)) }
    }

    /** 진단용: 앱이 직접 6초 사인파 재생(media/nav) — 끊김이 TTS 서비스 문제인지 오디오 경로 문제인지 가른다 */
    private fun toneTest(kind: String, s: Services) {
        Thread {
            runCatching {
                val sr = 22050; val n = sr * 6
                val pcm = ShortArray(n) { i -> (Math.sin(2 * Math.PI * 440 * i / sr) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * i / sr)) * 8000).toInt().toShort() }
                val usage = if (kind == "nav") android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else android.media.AudioAttributes.USAGE_MEDIA
                val attrs = android.media.AudioAttributes.Builder().setUsage(usage).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
                val fmt = android.media.AudioFormat.Builder().setSampleRate(sr).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
                val track = android.media.AudioTrack(attrs, fmt, n * 2, android.media.AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                track.write(pcm, 0, n); track.play()
                s.log.d("톤 테스트 시작 $kind"); Thread.sleep(6500); track.stop(); track.release(); s.log.d("톤 테스트 끝")
            }.onFailure { s.log.d("톤 테스트 실패: ${it.message}") }
        }.start()
    }
}
