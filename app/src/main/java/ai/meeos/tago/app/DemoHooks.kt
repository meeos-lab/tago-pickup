package ai.meeos.tago.app

import ai.meeos.tago.road.DriveRisk
import android.app.Activity
import android.content.Intent
import java.io.File

/**
 * 시연·시험용 훅. BuildConfig.DEMO_HOOKS 가 켜진 빌드(debug, 또는 -Pmeeos.demo=true)에서만 읽는다 —
 * 음성 명령·지역·센서 값을 넣는 통로라 제품 빌드에서는 닫는다.
 * **adb 셸 전용(2026-11-13)**: MainActivity 인텐트가 아니라 [DemoHookReceiver](DUMP 권한으로 보호, 셸만 보낼 수 있음)로만 들어온다.
 * 아래 예의 `--es …` 는 모두 이 방송의 extras 다:
 *
 *   adb shell am broadcast -n ai.meeos.tago/ai.meeos.tago.app.DemoHookReceiver -a ai.meeos.tago.DEMO_HOOK --es utterance "응"
 *   (인식된 말 넣기 — 에뮬레이터에 마이크가 없어서). 앱이 안 떠 있으면 보관했다가 뜰 때 적용한다.
 *   --es region irvine · --ez belt_sim true · --ez convoy_sim true · --ez navi_all true · --ez routine_test true
 *   --es siren_wav test.wav   앱 외부 저장소(files/, 없으면 내부 files/)의 16kHz 모노 wav 를 마이크 대신 흘려 넣는다(이름만, 경로·URL 불가)
 *   --es say_test "문장" · --es tone_test nav|media   음성 경로 진단
 *   차량 앱 검증(2026-11-13, docs/dev/95): --es speed_sample_sim "25,0,unavail"(속도 표본 상태 시험 — 충돌 감지로 가는 같은 검증 경로) ·
 *   --ez navi_off true(내비가 없는 것처럼) · --es navi_req detour|rest(내비 요청 한 번) · --es place_sim "37.40,127.10"|off|real(응급 카드 위치) ·
 *   주차 위치 기억(2026-11-13, 새 기능 H): --es parking_sim "spiral:3"|"straight"|"flat"|"off" · --es ocr_sim "B3|C-12"(기둥 사진 글 줄)
 *   차 화면 지도 노선 색 두 안(2026-11-13): --es map_route stock|ink(앱을 다시 열면 적용, 기본 stock)
 *   --ei init_fail 12(앱을 새로 시작할 때만: 앱을 끈 채 방송 → `am start` 순서. 처음 12초 동안 차량·내비·음성 서비스 연결이 실패한 것처럼 — MainActivity.onCreate 가 먼저 읽는다)
 */
object DemoHooks {
    private val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
    private const val MAX_TEXT = 200
    /** 시연 빌드에서만 쓰는 지도 모습 저장소(노선 색 두 안 비교). 제품 빌드는 이 값이 없어 기본(stock) */
    const val MAP_LOOK_PREFS = "map_look"

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
        // 순정 내비 돌발 정보 흉내 "ACCIDENT,2000"(에뮬레이터 내비는 성남 고정이라 경부선 돌발이 오지 않는다)
        intent.getStringExtra("official_sim")?.let { app.road.simOfficial(it) }
        // 운전 묶음 흉내 "SPEEDING,BRAKES,FATIGUE" | "off"(사고 위험성 시연 — 과속 20초·급제동 두 번·2시간 운전을 기다리지 않게)
        intent.getStringExtra("risk_sim")?.let { v -> app.road.driveRisk.sim = if (v.trim() == "off") emptySet() else v.split(",").map { it.trim().uppercase() }.filter { it in DriveRisk.TAGS }.toSet(); s.log.d("운전 위험 시연 ${app.road.driveRisk.sim}") }
        // 외기 온도 흉내 "-2" | "off"(교량 결빙 주의 시연 — Virtual Console 에 외기 온도 조절이 없다)
        intent.getStringExtra("temp_sim")?.let { v -> s.car.simTemp = if (v.trim() == "off") null else v.trim().toFloatOrNull(); s.log.d("외기 온도 시연 ${s.car.simTemp}") }
        intent.getStringExtra("speed_sample_sim")?.let { spec ->
            val p = spec.split(",").map { it.trim() }
            val pre = p.getOrNull(0)?.toFloatOrNull(); val post = p.getOrNull(1)?.toFloatOrNull()
            val status = when (p.getOrNull(2)) { "unavail" -> 1; "error" -> 2; else -> 0 }
            if (pre != null && post != null) s.car.simSpeedSample(pre, post, status) else s.log.d("speed_sample_sim 형식: pre,post[,ok|unavail|error] (m/s)")
        }
        // 주차 위치 기억(새 기능 H) 시연: parking_sim "spiral:3"(입구 → 나선 3층 → 기어 P) | "straight" | "flat" | "off", ocr_sim "B3|C-12"(기둥 사진 글 줄 — '|' 로 줄 구분)
        intent.getStringExtra("parking_sim")?.let { app.parking.simulate(it.take(MAX_TEXT)) }
        // 차 화면 지도 노선 색 두 안 비교(11-13): "stock"(순정 노선색, 기본) | "ink"(중립 짙은 색). 앱을 다시 열 때 지도 주소에 붙는다
        intent.getStringExtra("map_route")?.let { v -> if (v == "stock" || v == "ink") { activity.getSharedPreferences(MAP_LOOK_PREFS, android.content.Context.MODE_PRIVATE).edit().putString("route", v).apply(); s.log.d("지도 노선 색 $v(앱을 다시 열면 적용)") } }
        intent.getStringExtra("ocr_sim")?.let { app.parking.handleLines(it.take(MAX_TEXT).split("|")) }
        if (intent.hasExtra("navi_off")) { s.navi.simOff = intent.getBooleanExtra("navi_off", false); s.log.d("내비 없음 시연 ${s.navi.simOff}") }
        intent.getStringExtra("navi_req")?.let { k -> when (k) {
            "detour" -> s.navi.detour(37.2, 127.1, "시험 나들목")
            "rest" -> s.navi.routeDomestic(37.0, 127.0, "시험 휴게소")
            "cancel" -> s.navi.cancelAny()   // 시연 녹화 전: 에뮬레이터 순정 내비에 남은 안내를 정식 API(cancelRoute)로 끈다
            else -> s.log.d("navi_req: detour|rest|cancel")
        } }
        intent.getStringExtra("place_sim")?.let { v ->
            val g = app.emergencyGps
            when (v.trim()) {
                "off" -> { g.simNone = true; g.sim = null }
                "real" -> { g.simNone = false; g.sim = null }
                else -> { val c = v.split(",").mapNotNull { it.trim().toDoubleOrNull() }; if (c.size == 2) { g.simNone = false; g.sim = c[0] to c[1] } else s.log.d("place_sim 형식: 위도,경도|off|real") }
            }
            s.log.d("응급 위치 시연 ${if (g.simNone) "없음" else g.sim?.toString() ?: "실제 GPS"}")
        }
        if (intent.hasExtra("airbag_sim")) s.car.airbagSim = intent.getBooleanExtra("airbag_sim", false)
        if (intent.hasExtra("comm_sim")) { app.road.commSim = intent.getBooleanExtra("comm_sim", false); s.log.d("통신 끊김 시연 ${app.road.commSim}") }
        // 충전 시연: --es ev_sim "port=1,charge=0,soc=40" (에뮬레이터 Virtual Console 은 충전 상태 조작이 없다), "off" 면 실제 신호로
        intent.getStringExtra("ev_sim")?.let { spec ->
            val kv = parseKv(spec)
            val car = s.car
            if (spec.trim() == "off") { car.simChargePort = null; car.simCharging = null; car.simSoc = null }
            else { kv["port"]?.let { car.simChargePort = it == "1" }; kv["charge"]?.let { car.simCharging = it == "1" }; kv["soc"]?.toIntOrNull()?.let { car.simSoc = it } }
            kv["move"]?.toDoubleOrNull()?.let { app.road.demoMove(it) }   // 휴게소 안에서 자리 옮김(m)
            s.log.d("충전 시연: 충전구 ${car.chargePort} 충전 ${car.charging} 배터리 ${car.soc}%")
        }
        // 시연 차량 끄기(B-11): --ez demo_traffic false → 익명 시연 차량 14대·가상 가족을 허브에 부르지 않는다(실제 두 차만, 두 차 라이브)
        if (intent.hasExtra("sim_vc")) { app.road.simToVc = intent.getBooleanExtra("sim_vc", false); s.log.d("시연 주행 속도 → 가상 콘솔 ${app.road.simToVc}") }
        if (intent.hasExtra("demo_core")) { app.road.demoCore = intent.getBooleanExtra("demo_core", false); s.log.d("시연 각본 핵심만 ${app.road.demoCore}") }
        if (intent.hasExtra("demo_traffic")) { app.road.demoTraffic = intent.getBooleanExtra("demo_traffic", true); s.log.d("시연 차량 ${app.road.demoTraffic}") }
        if (intent.hasExtra("link_offset")) { app.road.startOffset = intent.getIntExtra("link_offset", 0).toDouble(); s.log.d("도로 링크 출발 간격 ${app.road.startOffset}m") }
        // 연구 기록 검증: --es study_sim "age=30s,years=8,car=sedan"(동의 화면 없이 세션 시작, 머리 줄 consent_source=demo_hook) · --ez study_off true · --ez study_export true
        intent.getStringExtra("study_sim")?.let { spec ->
            val kv = parseKv(spec)
            app.studyDemo(kv["age"] ?: "", kv["years"]?.toIntOrNull() ?: 0, kv["car"] ?: "")
        }
        if (intent.getBooleanExtra("study_off", false)) app.studyStop()
        // 접근 경고음 시험: --es sim_kmh "100"(시연 주행이 권장 속도를 따르지 않고 이 속도로) · "off"
        // 경고음 들어 보기: --es beep_test 1|2|3|pass(바로 한 번 울림)·loop(3단계 고리 4초) · --ez beep_wav true(네 소리를 앱 files/ 에 WAV 로)
        intent.getStringExtra("beep_test")?.let { v -> if (v == "pass") app.road.beepTest(ai.meeos.tago.road.ApproachBeep.PASSED) else if (v == "loop") app.road.beepTest(ai.meeos.tago.road.ApproachBeep.LOOP3) else v.toIntOrNull()?.takeIf { it in 1..3 }?.let { app.road.beepTest(it) } }
        if (intent.getBooleanExtra("beep_wav", false)) app.road.beepWavs(activity.filesDir)
        intent.getStringExtra("sim_kmh")?.let { v -> app.road.simTargetKmh = if (v.trim() == "off") null else v.trim().toFloatOrNull(); s.log.d("시연 주행 고정 속도 ${app.road.simTargetKmh}") }
        if (intent.getBooleanExtra("study_export", false)) (activity as? ai.meeos.tago.MainActivity)?.exportStudy()
        if (intent.getBooleanExtra("road_start", false)) app.startRoad()
        intent.getStringExtra("dest_search")?.let { q -> app.destOpenSearch(); app.destQueryChange(q.take(30)); app.destSearch() }   // 목적지 검색(시연 녹화: 키보드 없이)
        // 말로 검색 시험(11-14): /data/local/tmp 의 16 kHz 모노 16비트 PCM(머리 없음)을 마이크 대신 같은 Pleos STT 에 넣는다 — 에뮬레이터 PC 녹음 장치에 소리가 안 들어온다
        intent.getStringExtra("mic_sample")?.let { app.micSample = it.substringAfterLast('/').takeIf { n -> n != "off" && n.isNotBlank() } }   // 시연 마이크 단추 샘플 이름(/data/local/tmp) · off = 실제 마이크
        intent.getStringExtra("stt_wav")?.let { name ->
            val f = java.io.File("/data/local/tmp", name.substringAfterLast('/'))
            if (!f.isFile || f.length() > 2_000_000) s.log.d("stt_wav 없음·너무 큼: ${f.name}") else app.destVoice(f.readBytes())
        }
        if (intent.hasExtra("dest_pick")) app.destPick(intent.getIntExtra("dest_pick", 0))
        intent.getStringExtra("sim_stop_at")?.let { app.road.setSimStop(it) }   // 시연 주행을 세울 자리: 숫자(경로 위 m) · "rest"(다음 휴게소) · "off"
        if (intent.hasExtra("dir_aware")) app.road.setDirAware(intent.getBooleanExtra("dir_aware", true))   // 방향 구분 켬·끔(끄면 맞은편 위험도 예전처럼 안내)
        if (intent.hasExtra("ff")) app.road.setFf(intent.getFloatExtra("ff", 1f).toDouble())   // 빠르게 감기 배율(--ef ff 6, 1 = 끔) — 시연 주행 중에만, 화면에 "빠르게 감기 ×N"
        if (intent.hasExtra("link_consent")) app.demoConsent(intent.getBooleanExtra("link_consent", true))   // 주변 차 연결 동의를 미리 켬·끔(--ez link_consent true — 시연 녹화는 동의 판 없이)
        if (intent.hasExtra("player")) { app.playerShown = intent.getBooleanExtra("player", true); app.render() }   // 시연 주행 재생 막대 보임·감춤(--ez player false = 녹화할 때 막대 없이)
        intent.getStringExtra("listen_style")?.let { ai.meeos.tago.ui.screens.ListenStyle.bar = it == "bar" }   // 말로 검색 듣는 표시: bar(알약, 기본) · orb(구슬) — 11-15 두 안 비교
        if (intent.hasExtra("home_min")) { app.homeMinimal = intent.getBooleanExtra("home_min", true); app.render() }   // 최소 표시 첫 화면 켬·끔(--ez home_min false = 예전 첫 화면 판)
        if (intent.hasExtra("around")) { app.aroundEnabled = intent.getBooleanExtra("around", false); app.render() }   // 주행 중 주변 차 화면 켬·끔(--ez around true, 기본 꺼짐 — 11-14 사용자 답 대기)
        if (intent.hasExtra("real_route")) app.road.realRouteEnabled = intent.getBooleanExtra("real_route", true)   // 실제 경로 위 연결 켬·끔(B-107, 끄면 늘 시연 경로)
        if (intent.hasExtra("real_loc")) { app.road.realLocEnabled = intent.getBooleanExtra("real_loc", true); app.road.onDestChanged() }   // 실제 위치 모드 켬·끔(11-14 오후, 끄면 예전 시연 경로 기준)
        if (intent.hasExtra("dest_off")) { app.road.destEnabled = !intent.getBooleanExtra("dest_off", false); s.log.d("목적지 기능 ${app.road.destEnabled}") }
        if (intent.getBooleanExtra("road_end", false)) app.road.end()   // 시연 한 바퀴 녹화: [끝내기]와 같은 길(화면 누르기 없이)
        if (intent.getBooleanExtra("group_start", false)) s.main.postDelayed({ app.roadPrimary() }, 1500)   // 앞차: 가족 그룹 만들기(코드는 로그·음성)
        intent.getStringExtra("link_code")?.let { code -> s.main.postDelayed({ app.road.joinGroup(code.take(8)) }, 1500) }
        intent.getStringExtra("siren_wav")?.let { name ->
            if (!SAFE_NAME.matches(name)) { s.log.d("사이렌 시험 파일 이름이 올바르지 않음"); return@let }
            // 외부 저장소 → 없으면 앱 내부 files/(에뮬레이터가 사용자 10 으로 돌면 adb 가 외부 저장소에 못 쓴다: `adb exec-in run-as <패키지> --user 10 sh -c 'cat > files/<파일>'`)
            val f = File(activity.getExternalFilesDir(null), name).takeIf { it.exists() } ?: File(activity.filesDir, name)
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

    /** "a=1,b=2" → {a:1, b:2}. '=' 이 정확히 하나가 아닌 조각은 버린다 */
    private fun parseKv(spec: String): Map<String, String> =
        spec.split(",").mapNotNull { it.trim().split("=").takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }.toMap()
}
