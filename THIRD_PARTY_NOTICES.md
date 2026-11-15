# 제3자 소프트웨어 고지 (Third-party notices)

이 저장소의 앱(APK)과 서버·웹 화면이 쓰는 외부 구성요소 목록이다. 마지막 확인: 2026-11-11.
**확인 구분**: *POM* = 빌드 때 내려받은 라이브러리의 POM 파일에 적힌 라이선스를 직접 읽음 · *저장소* = 저장소 안 라이선스 파일·문서로 확인 · *미확인* = 근거를 찾지 못함(추정하지 않음).
라이선스 원문 전체는 각 프로젝트 배포본에 있으며 이 저장소에는 복사하지 않았다. 지도·공공데이터의 출처는 [DATA_SOURCES.md](DATA_SOURCES.md), 저장소 자체의 이용 조건은 [LICENSE](LICENSE).

## 1. 앱에 들어가는 라이브러리 (Android, `app/build.gradle.kts` 의 `dependencies`)
| 구성요소 | 버전 | 라이선스 | 확인 |
|---|---|---|---|
| androidx.compose BOM (ui · ui-graphics · foundation · material3) | 2026.09.00 | Apache-2.0 | POM |
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | POM |
| androidx.core:core-ktx (전이) | 1.18.0 | Apache-2.0 | POM |
| Kotlin 표준 라이브러리 | 2.4.20 | Apache-2.0 | POM |
| kotlin-reflect (전이) | 1.9.25 | Apache-2.0 | POM |
| kotlinx-serialization (core · json, 전이) | 1.7.3 | Apache-2.0 | POM |
| kotlinx-coroutines (core · android, 전이) | 1.9.0 | Apache-2.0 | 캐시에 `.module` 만 있어 POM 으로는 못 읽음 — 프로젝트 공지 기준(미확인) |
| com.google.code.gson:gson (전이) | 2.10.1 | Apache-2.0 | POM |
| Dagger · Hilt Android (전이) | 2.51.1 | Apache-2.0 | POM |
| javax.inject (전이) | 1 | Apache-2.0 | POM |
| error_prone_annotations (전이) | 2.15.0 | Apache-2.0 | POM |
| com.google.flatbuffers:flatbuffers-java (VSM SDK 가 요구) | 24.3.25 | Apache-2.0 | POM |
| com.google.ai.edge.litert:litert (LiteRT, TensorFlow Lite 실행기 — 같은 빌드 설정이라 라이브러리는 링크되지만 이 판 APK 에는 모델 파일이 없다) | 1.4.2 | Apache-2.0 | POM |
| 시험 전용(배포물에 없음) junit | 4.13.2 | EPL-1.0 | POM |
| 시험 전용(배포물에 없음) org.json:json | 20240303 | Public Domain | POM |

## 2. 차량·지도 SDK
| 구성요소 | 버전 | 라이선스 | 확인 |
|---|---|---|---|
| Pleos Playground SDK: Vehicle · TextToSpeech · SpeechToText · NaviHelper | 2.3.2 · 3.3.1 · 3.3.1 · 3.0.5 | **미확인** — POM 에 `<licenses>` 없음. 공모전 개발 환경으로 제공된 SDK 를 Maven 저장소(`settings.gradle.kts`)에서 받아 호출만 한다(저장소에 바이너리 없음) | 미확인 |
| Pleos 전이 모듈: navi-data 3.0.4 · navi-aidl 3.0.1 · vehicle-aidl 1.1.1 · vehicle-data 1.1.11 · security:universal-access 0.6.0 | | **미확인** — POM 에 `<licenses>` 없음 | 미확인 |
| TMAP Android SDK (`app/libs/tmap-sdk-3.7.aar`, 차 화면 지도) | 3.7 | **미확인** — 아래 2.1 | 미확인 |
| VSM SDK (`app/libs/vsm-tmap-sdk-v2-eaa-2.0.14.aar`, TMAP SDK 가 요구) | 2.0.14 | **미확인** — 아래 2.1 | 미확인 |

### 2.1 TMAP·VSM SDK 바이너리(AAR) 재배포 조건
- 두 AAR 안에는 라이선스 파일이 없다. 권리는 티맵모빌리티에 있다.
- TMAP API 공식 개발자 포털의 약관 페이지(https://tmapapi.tmapmobility.com/terms.html, 2026-11-11 열람)에는 서비스 개요, 일별 무료 제공 한도, 준수사항만 있다. 준수사항은 "Open API 로 얻은 데이터는 저장 후 24시간 이상 사용할 수 없다", "같은 서비스에 여러 프로젝트를 만들어 쓰면 불법 사용으로 간주될 수 있다", "무료 한도를 넘으면 유료 서비스에 가입한다"는 내용이다. **SDK 파일을 재배포해도 되는지 허락하거나 금지하는 조항은 찾지 못했다.** 포털 하단에는 'COPYRIGHT TMAP MOBILITY CO. LTD. ALL RIGHTS RESERVED.' 라고만 적혀 있다.
- 따라서 **재배포 허용 여부는 미확인**이다. 이 저장소의 AAR 은 앱을 빌드하는 데에만 쓴다.
- 재배포가 허락되지 않는 것으로 확인되면 대안은 이렇다. 저장소에서 `app/libs/*.aar` 를 빼고, 빌드하는 사람이 TMAP API 포털(https://tmapapi.tmapmobility.com/)의 Vector Map SDK(Android) 내려받기에서 SDK 를 받아 `app/libs/` 에 같은 파일 이름으로 넣는다. `app/build.gradle.kts` 는 `libs/tmap-sdk-3.7.aar`·`libs/vsm-tmap-sdk-v2-eaa-2.0.14.aar` 를 읽는다. 다만 포털에서 이 두 판(3.7·2.0.14)을 지금도 받을 수 있는지는 미확인이다. 심사용 실행은 SDK 가 이미 들어 있는 `apk/` 의 APK 로 할 수 있다.
- 앱 지도 키(`tmap.appKey`)는 저장소에 없다(`local.properties`, git 제외). 키가 없으면 앱은 웹 지도 바탕(OpenFreeMap)으로 돈다.

## 3. 웹 화면이 불러오는 라이브러리 (CDN, 저장소·앱 안에 복사하지 않음)
| 구성요소 | 버전 | 쓰는 화면 | 라이선스 | 확인 |
|---|---|---|---|---|
| Leaflet | 1.9.4 | `server/rider.html`(기다리는 사람 휴대폰 페이지·차 화면 지도) | BSD-2-Clause | 널리 알려진 라이선스(저장소에 원문 없음) |
| MapLibre GL JS · maplibre-gl-leaflet | 4.7.1 · 0.0.22 | 같은 화면 | BSD-3-Clause 계열 · ISC 계열 | 널리 알려진 라이선스(저장소에 원문 없음) |
| TMAP Vector JS · 경로 API | | 웹 지도(키가 있을 때) | **미확인** — 2.1 의 포털 약관을 따른다 | 미확인 |
| OpenFreeMap 스타일 · OpenMapTiles · OpenStreetMap 데이터 | | 웹 지도 바탕 | 지도 데이터는 ODbL, 스타일 이용 조건은 미확인 | 화면 출처 줄 '© OpenFreeMap © OpenMapTiles © OpenStreetMap'(`server/rider.html`) |

## 4. 서버
서버 실행 코드(Python)는 표준 라이브러리만 쓴다. 데이터 빌드 스크립트 하나만 openpyxl 을 쓴다(`server/build_cctv.py`, 앱·서버 실행에는 쓰지 않음. 라이선스는 이 문서에서 미확인).

## 5. 고치는 방법
의존성을 더하거나 바꾸면 이 문서를 같은 변경에서 고친다. POM 라이선스는 `~/.gradle/caches/modules-2/files-2.1/<그룹>/<이름>/<버전>/*/*.pom` 의 `<license>` 에서 읽는다. 의존성의 버전 근거와 코드 위치는 개발 보고서 부록 L(의존성·라이선스)에 있다.
