plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 는 Kotlin 을 내장한다(kotlin-android 플러그인 불필요). Compose 컴파일러 플러그인 버전이 쓸 Kotlin 버전을 정한다
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
