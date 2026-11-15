import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

// 병합된 매니페스트에서 그 판이 쓰지 않는 <uses-permission> 을 뺀다(판별용 — 매니페스트 원본은 하나)
abstract class StripPermissionsTask : DefaultTask() {
    @get:InputFile abstract val inputManifest: RegularFileProperty
    @get:OutputFile abstract val outputManifest: RegularFileProperty
    @get:Input abstract val names: ListProperty<String>
    @TaskAction fun run() {
        var text = inputManifest.get().asFile.readText(Charsets.UTF_8)
        for (n in names.get()) {
            val re = Regex("""[ \t]*<uses-permission\b[^>]*\bandroid:name="${Regex.escape(n)}"[^>]*/>[ \t]*\r?\n?""")
            require(re.containsMatchIn(text)) { "매니페스트에 없는 권한: $n" }
            text = re.replace(text, "")
        }
        outputManifest.get().asFile.writeText(text, Charsets.UTF_8)
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 기본은 앱 내장 서버(APK 하나로 시연). PC 시연 서버(server/meeos_server.py)에 붙이려면 -Pmeeos.pcServer=true
val pcServer = (project.findProperty("meeos.pcServer") as String?) == "true"
// 두 차 라이브 시연: 뒷차 APK 는 내장 서버 없이 앞차 에뮬레이터의 허브를 본다 — -Pmeeos.serverUrl=http://10.0.2.2:8791
// (PC 에서 `adb -s emulator-5554 forward tcp:8791 tcp:8790` 으로 앞차의 내장 서버를 열어 둔다). README "시연 순서: 두 차 라이브"
val serverUrlOverride = (project.findProperty("meeos.serverUrl") as String?)?.trim()?.takeIf { it.isNotEmpty() }
// 출품작별 APK(두 작품을 따로 낸다): 기본은 두 기능 모두.
//  -Pmeeos.edition=pickup  → 타고(픽업) 출품작: 함께 가기·도로 링크·응급 브리핑을 화면과 음성에서 뺀다. ai.meeos.tago
//  -Pmeeos.edition=gilbeot → 길벗(차대차 링크) 출품작: 데리러 가기(픽업)·요일 루틴·근처 찾기를 뺀다. ai.meeos.gilbeot(따로 설치되는 앱)
val edition = (project.findProperty("meeos.edition") as String?)?.trim().orEmpty()
val pickupOnly = edition == "pickup"
val gilbeotOnly = edition == "gilbeot"
require(edition in setOf("", "pickup", "gilbeot")) { "meeos.edition 은 pickup 또는 gilbeot (지금: $edition)" }
val appLabel = if (gilbeotOnly) "길벗" else "Tago"
// 앱 내장 서버 포트(에뮬레이터 안 127.0.0.1): 판마다 다르게 — 두 APK 를 한 에뮬레이터에 같이 깔아도 먼저 뜬 앱이 포트를 잡아 다른 앱이 멈추지 않게(11-09).
// 길벗(ai.meeos.gilbeot) 8790 은 그대로(영상·언리얼·두 차 라이브 도구가 쓴다), 타고 픽업·기본 판(ai.meeos.tago) 은 8793. PC 쪽은 둘 다 adb forward tcp:8791 tcp:<이 값>
val localPort = if (gilbeotOnly) 8790 else 8793
// 시연·시험 인텐트 훅(utterance·belt_sim·convoy_sim…): debug 는 항상, release 는 -Pmeeos.demo=true 일 때만 (외부 앱이 인텐트로 명령을 넣는 통로라 제품 빌드에서는 닫는다)
val demoHooks = (project.findProperty("meeos.demo") as String?) == "true"
// 긴급차량 비컨 토큰(저장소 코드에 값을 두지 않는다 — docs/dev/83 S4): -Pmeeos.evToken → 환경변수 TAGO_EV_TOKEN → 루트의 ev_token.local(git 제외). 없으면 앱이 실행마다 무작위 값을 쓴다
val evToken = ((project.findProperty("meeos.evToken") as String?)?.trim().orEmpty())
    .ifEmpty { System.getenv("TAGO_EV_TOKEN")?.trim().orEmpty() }
    .ifEmpty { rootProject.file("ev_token.local").takeIf { it.isFile }?.readText()?.trim().orEmpty() }
    .filter { it.isLetterOrDigit() || it in "-_" }   // 따옴표·역슬래시가 BuildConfig 문자열을 깨지 않게
// 연구 기록(경고 반응 로그 — 동의 화면·참가자 정보·JSONL): debug·qa 는 항상, release 는 -Pmeeos.study=true 일 때만(출품 APK 화면은 그대로).
// 서버 업로드 주소는 -Pmeeos.studyUploadUrl=https://… (비어 있으면 업로드 스위치를 켜도 앱 안에만 남는다)
val studyLog = (project.findProperty("meeos.study") as String?) == "true"
val studyUploadUrl = (project.findProperty("meeos.studyUploadUrl") as String?)?.trim().orEmpty()

// 릴리스 서명: keystore.properties(커밋 금지)가 있으면 그 키, 없으면 디버그 키로 서명해 빌드는 항상 된다
val signProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// TMAP Open API 앱키(openapi.sk.com, 커밋 금지): local.properties 의 tmap.appKey. 비어 있으면 지도 페이지는 OpenFreeMap 바탕으로 돈다
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val tmapAppKey = localProps.getProperty("tmap.appKey", "").trim()
// 공공데이터포털 '국토교통부_건축HUB_건축물대장정보 서비스' 일반 인증키(커밋 금지): local.properties 의 BLDG_API_KEY. 주차 위치 기억의 방법 C(건물 층수).
// TMAP 키와 같이 APK 에 들어간다(공모전 전용 키, 심사 뒤 폐기·재발급). 비어 있으면 방법 C 를 건너뛴다. pickupOnly 판에는 넣지 않는다
val bldgApiKey = localProps.getProperty("BLDG_API_KEY", "").trim().replace("\\", "").replace("\"", "")

// 버전: 심사 때마다 versionCode 가 올라야 한다(같으면 반려) — 커밋 수로 자동. git 이 없으면 1
val gitVersionCode = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)
// 네이티브 라이브러리 CPU 종류: 실차(Pleos Connect)는 arm64-v8a, 에뮬레이터는 x86_64. debug 는 에뮬레이터용 x86_64 만(설치가 빨라진다), 모두 넣으려면 -Pmeeos.allAbis=true
val allAbis = (project.findProperty("meeos.allAbis") as String?) == "true"

// 지도 데이터와 기다리는 사람용 웹 페이지는 server/ 의 파일을 그대로 앱 assets 로 복사한다(원본 하나). HTML 의 __TMAP_KEY__ 자리에만 앱키를 끼운다
val copyMeeosAssets = tasks.register<Sync>("copyMeeosAssets") {   // Sync: 출품작을 바꿔 빌드할 때 지난 자산이 남지 않게
    from("../server") {
        include("daechi_map.json", "daechi_cctv.json", "regions/*.json", "global/*_map.json")
        if (!gilbeotOnly) include("rider.html")   // 기다리는 사람용 휴대폰 페이지 — 그 기능이 없는 판의 APK 에는 넣지 않는다(MainActivity 가 빈 페이지를 넘긴다)
        if (!pickupOnly) include("link/gyeongbu_route.json", "link/link.html", "link/world.html", "link/ambulance.html", "link/around.html", "link/models/**")   // 픽업 APK에는 함께 가기 자산을 넣지 않는다
        filesMatching("**/*.html") { filter { line -> line.replace("__TMAP_KEY__", tmapAppKey) } }
    }
    // 사이렌 감지 모델(YAMNet, 4 MB, app/siren/README.md): 도로 링크가 있는 판에만
    if (!pickupOnly) from("siren") { include("yamnet.tflite"); into("siren") }
    // Gleo 앱 액션 정의: 판마다 패키지 이름·함수가 달라 app/gleo/ 의 판별 파일을 schema.json 으로 넣는다
    from("gleo") {
        include(if (gilbeotOnly) "schema-gilbeot.json" else "schema-tago.json")
        rename { "schema.json" }
    }
    into(layout.buildDirectory.dir("generated/meeosAssets"))
    inputs.property("tmapAppKey", tmapAppKey)   // 키가 바뀌면 다시 복사
    inputs.property("edition", edition)
}

// 도로 링크 요청 스키마(server/link/schema): 앱 엔진 LinkHub 이 PC 서버와 같은 파일로 검사한다 — Context 없이 읽게 Java 리소스 gilbeot-road/ 로 넣는다
val copyMeeosRes = tasks.register<Sync>("copyMeeosRes") {
    from("../server/link/schema") { include("*.request.schema.json", "*.query.schema.json") }
    into(layout.buildDirectory.dir("generated/meeosRes/gilbeot-road"))
}

android {
    namespace = "ai.meeos.tago"
    // 최신 Compose(BOM 2026.09)가 compileSdk 37 이상을 요구한다. targetSdk 는 Pleos 안내(35)대로 둔다 — 차량 이미지가 API 34 라 실제 동작은 같다
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    // 차량 속도(PERF_VEHICLE_SPEED)는 Pleos SDK에 없어 표준 API 사용 (FAQ 안내 방식)
    useLibrary("android.car")

    defaultConfig {
        applicationId = if (gilbeotOnly) "ai.meeos.gilbeot" else "ai.meeos.tago"
        manifestPlaceholders["appLabel"] = appLabel
        manifestPlaceholders["appIcon"] = if (gilbeotOnly) "@mipmap/ic_launcher_gilbeot" else "@mipmap/ic_launcher_tago"   // 독·앱 목록 아이콘(판마다)
        minSdk = 28
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = "0.1.0"
        // 내장 서버: 차량 WebView는 127.0.0.1:<LOCAL_PORT>(길벗 8790·타고 8793), PC 브라우저는 adb forward tcp:8791 tcp:<LOCAL_PORT> 후 127.0.0.1:8791
        // PC 서버: 에뮬레이터 호스트 10.0.2.2:8790. 제품에서는 HTTPS 도메인으로 교체
        buildConfigField("boolean", "EMBEDDED_SERVER", (!pcServer && serverUrlOverride == null).toString())
        buildConfigField("boolean", "ROAD_LINK", (!pickupOnly).toString())
        buildConfigField("boolean", "EMERGENCY", (!pickupOnly).toString())   // 응급 브리핑은 픽업 출품작 범위 밖(의료 책임)이라 픽업 APK에서 뺀다. 길벗은 사고 뒤 응급 카드에 쓴다
        buildConfigField("boolean", "PICKUP", (!gilbeotOnly).toString())     // 데리러 가기·요일 루틴·근처 찾기 — 길벗 APK에서 뺀다
        buildConfigField("String", "APP_LABEL", "\"$appLabel\"")
        buildConfigField("int", "LOCAL_PORT", localPort.toString())
        buildConfigField("String", "SERVER_URL", "\"" + (serverUrlOverride ?: if (pcServer) "http://10.0.2.2:8790" else "http://127.0.0.1:$localPort") + "\"")
        buildConfigField("String", "PUBLIC_URL", if (pcServer) "\"http://127.0.0.1:8790\"" else "\"http://127.0.0.1:8791\"")
        // TMAP 앱키(local.properties 의 tmap.appKey, 커밋 금지). 있으면 차 화면 지도를 네이티브 TMAP SDK 로, 없으면 웹 지도(/car/)로
        buildConfigField("String", "TMAP_KEY", "\"$tmapAppKey\"")
        buildConfigField("String", "BLDG_API_KEY", "\"" + (if (pickupOnly) "" else bldgApiKey) + "\"")   // 값은 로그·문서에 남기지 않는다(앱 로그는 '키 있음/없음'만)
        buildConfigField("String", "STUDY_UPLOAD_URL", "\"$studyUploadUrl\"")
        buildConfigField("String", "EV_TOKEN", "\"$evToken\"")   // 비어 있으면 LinkHub 가 실행마다 무작위 토큰(구급차 휴대폰 주소는 시연 빌드 로그에)
    }

    signingConfigs {
        create("release") {
            if (signProps.isNotEmpty()) {
                storeFile = file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    // 앱 문자열은 한국어·영어뿐 — 라이브러리의 80여 개 언어 문자열은 빼서 resources.arsc 를 줄인다
    androidResources {
        localeFilters += listOf("ko", "en")
        noCompress += "tflite"   // 사이렌 모델을 압축 없이 넣어 메모리 매핑으로 읽는다(SirenYamnet.loadModel)
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEMO_HOOKS", "true")
            manifestPlaceholders["demoHooksEnabled"] = "true"
            buildConfigField("boolean", "STUDY_LOG", "true")
            manifestPlaceholders["studyLocationPermission"] = "android.permission.ACCESS_FINE_LOCATION"
            ndk { abiFilters += if (allAbis) listOf("arm64-v8a", "x86_64") else listOf("x86_64") }
        }
        getByName("release") {
            // R8: 쓰지 않는 코드를 빼고 최적화한다(싱글 스레드가 약한 실차에서 시작·프레임 비용을 줄인다). SDK 가 리플렉션으로 쓰는 부분은 proguard-rules.pro 에서 지킨다
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "DEMO_HOOKS", demoHooks.toString())
            manifestPlaceholders["demoHooksEnabled"] = demoHooks.toString()   // 훅 수신기(adb 전용)는 훅이 켜진 빌드에서만 켠다
            buildConfigField("boolean", "STUDY_LOG", studyLog.toString())
            // 위치: 연구 빌드(차속 없는 차의 GPS 속도) 또는 길벗 판(휴게소 판정 offRoute — 차 안에서 계산, 불리언만 전송, 11-08). 픽업 판 제품 빌드는 넣지 않는다
            manifestPlaceholders["studyLocationPermission"] = if (studyLog || !pickupOnly) "android.permission.ACCESS_FINE_LOCATION" else "ai.meeos.permission.STUDY_GPS_UNUSED"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        // qa: release 와 같은 최적화·축소, 디버그 키 서명 — 에뮬레이터의 debug 위에 그대로 덮어 설치해 실제 성능을 잰다(-g 설치 권한 그대로)
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "STUDY_LOG", "true")
            manifestPlaceholders["studyLocationPermission"] = "android.permission.ACCESS_FINE_LOCATION"
            ndk { abiFilters.clear(); abiFilters += listOf("x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// AGP 9 는 sourceSets 에 Provider 를 못 넣는다 → Variant API 로 복사본 폴더를 assets 에 더한다(복사 작업은 preBuild 가 먼저 돌린다)
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/meeosAssets").get().asFile.path)
        variant.sources.resources?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/meeosRes").get().asFile.path)   // Java 리소스(위 copyMeeosRes)
        // 판별 문구(작품 이름이 든 문장): src/edition-<판>/res(판 = meeos.edition, 없으면 both) — main/res 에는 이 키들이 없다(res/values*/strings_edition.xml)
        variant.sources.res?.addStaticSourceDirectory(file("src/edition-${edition.ifEmpty { "both" }}/res").path)
        // 픽업 판은 쓰지 않는 권한(마이크 사이렌·에어백·공조·EV 에너지·조향각)을 병합 매니페스트에서 뺀다
        // (이 신호는 그 기능이 있는 판에서만 읽고(readRoadSignals), 마이크 권한은 사이렌만 쓴다. 음성 인식 녹음은 SDK 서비스가 한다)
        if (pickupOnly) {
            val strip = tasks.register<StripPermissionsTask>("strip${variant.name.replaceFirstChar { it.uppercase() }}Permissions") {
                names.set(listOf("android.permission.RECORD_AUDIO", "pleos.car.permission.CAR_SAFETY", "pleos.car.permission.CAR_CLIMATE",
                    "pleos.car.permission.CAR_ENERGY", "pleos.car.permission.READ_CAR_STEERING"))
            }
            variant.artifacts.use(strip).wiredWithFiles(StripPermissionsTask::inputManifest, StripPermissionsTask::outputManifest).toTransform(SingleArtifact.MERGED_MANIFEST)
        }
    }
}

tasks.named("preBuild") { dependsOn(copyMeeosAssets, copyMeeosRes) }

// 에뮬레이터 없는 두 엔진 대조용 JVM 허브(app/src/test/.../LinkHubServe.kt): -Pmeeos.linkServe=<포트> 일 때만 그 시험이 허브를 연다
tasks.withType<Test>().configureEach {
    systemProperty("meeos.linkServe", (project.findProperty("meeos.linkServe") as String?).orEmpty())
    systemProperty("meeos.linkRoute", (project.findProperty("meeos.linkRoute") as String?).orEmpty())   // 대조용 허브의 경로 파일(없으면 시연 경로) — 실제 경로로 만든 파일의 두 허브 대조(B-107)
    if ((project.findProperty("meeos.linkServe") as String?).isNullOrEmpty().not()) outputs.upToDateWhen { false }
}

dependencies {
    // 화면: Jetpack Compose. 컴포넌트는 app/.../ui/components
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    // 차 화면 지도: TMAP Android SDK 3.7(tmapapi.tmapmobility.com SDK 다운로드, 내장 VSM SDK 2.0.14). 순정 커서(현재 위치 아이콘)·티맵 노선(TMapTrafficLine)을 SDK 그대로 쓴다
    implementation(files("libs/tmap-sdk-3.7.aar"))
    implementation(files("libs/vsm-tmap-sdk-v2-eaa-2.0.14.aar"))
    implementation("com.google.flatbuffers:flatbuffers-java:24.3.25")   // VSM SDK 가 요구(공식 샘플과 같은 판)
    // 사이렌 감지: YAMNet TFLite 를 돌리는 LiteRT(구 TensorFlow Lite) — org.tensorflow.lite.Interpreter
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    // 주차 기둥 사진 글자 인식(새 기능 H): ML Kit 한국어 텍스트 인식(번들 모델, 기기 안에서만 돈다 — 사진은 서버로 가지 않는다).
    // pickupOnly 판에는 이 기능이 없어 라이브러리를 넣지 않는다(컴파일에만 필요하고 APK 에는 들어가지 않는다 — Edition.roadLink 가 꺼진 판은 이 경로를 부르지 않는다)
    if (pickupOnly) compileOnly("com.google.mlkit:text-recognition-korean:16.0.1") else implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // 단위 시험(app/src/test, 로컬 JVM): ./gradlew testDebugUnitTest -Pmeeos.edition=gilbeot — 순수 로직(말 해석·운전 위험·충전 설문 …)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // android.jar 의 org.json 은 로컬 JVM 에서 비어 있는 껍데기라 실제 구현을 넣는다
}
