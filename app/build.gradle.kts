import java.util.Properties

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
// 시연·시험 인텐트 훅(utterance·belt_sim·convoy_sim…): debug 는 항상, release 는 -Pmeeos.demo=true 일 때만 (외부 앱이 인텐트로 명령을 넣는 통로라 제품 빌드에서는 닫는다)
val demoHooks = (project.findProperty("meeos.demo") as String?) == "true"
// 연구 기록(경고 반응 로그 — 동의 화면·참가자 정보·JSONL): debug·qa 는 항상, release 는 -Pmeeos.study=true 일 때만(출품 APK 화면은 그대로).
// 서버 업로드 주소는 -Pmeeos.studyUploadUrl=https://… (비어 있으면 업로드 스위치를 켜도 앱 안에만 남는다)
val studyLog = (project.findProperty("meeos.study") as String?) == "true"
val studyUploadUrl = (project.findProperty("meeos.studyUploadUrl") as String?)?.trim().orEmpty()

// 릴리스 서명: keystore.properties(커밋 금지)가 있으면 그 키, 없으면 디버그 키로 서명해 빌드는 항상 된다
val signProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// TMAP Open API 앱키(openapi.sk.com, 커밋 금지): local.properties 의 tmap.appKey. 비어 있으면 지도 페이지는 OpenFreeMap 바탕으로 돈다
val tmapAppKey = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("tmap.appKey", "").trim()

// 버전: 심사 때마다 versionCode 가 올라야 한다(같으면 반려) — 커밋 수로 자동. git 이 없으면 1
val gitVersionCode = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)
// 네이티브 라이브러리 CPU 종류: 실차(Pleos Connect)는 arm64-v8a, 에뮬레이터는 x86_64. debug 는 에뮬레이터용 x86_64 만(설치가 빨라진다), 모두 넣으려면 -Pmeeos.allAbis=true
val allAbis = (project.findProperty("meeos.allAbis") as String?) == "true"

// 지도 데이터와 기다리는 사람용 웹 페이지는 server/ 의 파일을 그대로 앱 assets 로 복사한다(원본 하나). HTML 의 __TMAP_KEY__ 자리에만 앱키를 끼운다
val copyMeeosAssets = tasks.register<Sync>("copyMeeosAssets") {   // Sync: 출품작을 바꿔 빌드할 때 지난 자산이 남지 않게
    from("../server") {
        include("daechi_map.json", "rider.html", "daechi_cctv.json", "regions/*.json", "global/*_map.json")
        if (!pickupOnly) include("link/gyeongbu_route.json", "link/link.html", "link/world.html", "link/ambulance.html", "link/models/**")   // 픽업 APK에는 함께 가기 자산을 넣지 않는다
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
        // 내장 서버: 차량 WebView는 127.0.0.1:8790, PC 브라우저는 adb forward tcp:8791 tcp:8790 후 127.0.0.1:8791
        // PC 서버: 에뮬레이터 호스트 10.0.2.2:8790. 제품에서는 HTTPS 도메인으로 교체
        buildConfigField("boolean", "EMBEDDED_SERVER", (!pcServer && serverUrlOverride == null).toString())
        buildConfigField("boolean", "ROAD_LINK", (!pickupOnly).toString())
        buildConfigField("boolean", "EMERGENCY", (!pickupOnly).toString())   // 응급 브리핑은 픽업 출품작 범위 밖(의료 책임)이라 픽업 APK에서 뺀다. 길벗은 사고 뒤 응급 카드에 쓴다
        buildConfigField("boolean", "PICKUP", (!gilbeotOnly).toString())     // 데리러 가기·요일 루틴·근처 찾기 — 길벗 APK에서 뺀다
        buildConfigField("String", "APP_LABEL", "\"$appLabel\"")
        buildConfigField("String", "SERVER_URL", "\"" + (serverUrlOverride ?: if (pcServer) "http://10.0.2.2:8790" else "http://127.0.0.1:8790") + "\"")
        buildConfigField("String", "PUBLIC_URL", if (pcServer) "\"http://127.0.0.1:8790\"" else "\"http://127.0.0.1:8791\"")
        // TMAP 앱키(local.properties 의 tmap.appKey, 커밋 금지). 있으면 차 화면 지도를 네이티브 TMAP SDK 로, 없으면 웹 지도(/car/)로
        buildConfigField("String", "TMAP_KEY", "\"$tmapAppKey\"")
        buildConfigField("String", "STUDY_UPLOAD_URL", "\"$studyUploadUrl\"")
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
    }
}

tasks.named("preBuild") { dependsOn(copyMeeosAssets, copyMeeosRes) }

// 에뮬레이터 없는 두 엔진 대조용 JVM 허브(app/src/test/.../LinkHubServe.kt): -Pmeeos.linkServe=<포트> 일 때만 그 시험이 허브를 연다
tasks.withType<Test>().configureEach {
    systemProperty("meeos.linkServe", (project.findProperty("meeos.linkServe") as String?).orEmpty())
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

    // 단위 시험(app/src/test, 로컬 JVM): ./gradlew testDebugUnitTest -Pmeeos.edition=gilbeot — 순수 로직(말 해석·운전 위험·충전 설문 …)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // android.jar 의 org.json 은 로컬 JVM 에서 비어 있는 껍데기라 실제 구현을 넣는다
}
