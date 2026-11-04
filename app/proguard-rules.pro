# Tago R8 규칙 — 외부 SDK 는 이름 그대로 둔다(네이티브·IPC·리플렉션이 이름으로 찾는다)

# TMAP SDK 3.7 + 내장 VSM 2.0.14: VSM 네이티브 라이브러리(libvsmsdk_jni.so)가 JNI 로 자바 클래스·필드를 이름으로 부른다.
# 앱도 map/TmapCarMap.kt 에서 TMapView 의 비공개 필드 vsmMapEngine 을 리플렉션으로 읽는다
-keep class com.skt.tmap.** { *; }
-keep class vsm.** { *; }
-keep class a.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn com.skt.tmap.**
-dontwarn vsm.**

# Pleos Connect SDK(Vehicle·NaviHelper·TTS·STT): 시스템 서비스와 AIDL/Binder 로 붙는다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# AAOS android.car(useLibrary): 기기 쪽 라이브러리
-dontwarn android.car.**

# 엔진·서버는 org.json 으로 문자열 키를 쓰므로 이름 보존이 필요 없다. 로그의 스택이 읽히게 줄 번호는 남긴다
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
