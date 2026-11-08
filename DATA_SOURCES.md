# 데이터 출처와 이용 조건

시연에 쓴 지도·공공데이터와 출처 표시 의무를 정리한다. 앱 지도 화면 오른쪽 아래에도 같은 출처를 표시한다.

| 데이터 | 쓰인 곳 | 출처 | 이용 조건 |
|---|---|---|---|
| 도로, 버스정류장, 횡단보도, 신호 교차로, 학교, 편의점·카페·약국·주차장·지하철 출구 | `server/daechi_map.json` | © OpenStreetMap contributors (Overpass API로 수집, `server/fetch_daechi_osm.py`) | ODbL 1.0 — 출처 표시, 파생 데이터베이스 공유 시 같은 조건 |
| 건물 주소(도로명+번지) — 단속 CCTV 주소를 좌표로 바꾸는 데만 사용 | `server/daechi_cctv.json` 좌표 | © OpenStreetMap contributors (`server/fetch_daechi_addr.py`), 일부 OSM Nominatim 검색 | ODbL 1.0 |
| 소화전 위치 179개 | `server/daechi_map.json` | 서울특별시 소방재난본부 「소방용수시설」(2025-12-31), 서울 열린데이터광장 OA-21306 | 공공누리 제1유형(출처 표시) |
| 불법주정차 단속 고정형 CCTV 46대(심야·토공휴일 운영 구분) | `server/daechi_cctv.json` | 서울특별시 강남구청 「강남구_불법주정차 단속 고정형 CCTV 현황(2026.8.3)」(구청 주차 안내 페이지 공개 자료) | 공개 행정자료 — 출처 표시. 좌표는 주소를 변환한 근사값 |
| 공영주차장 6곳(운영 시간·요금) | `server/daechi_map.json` publicParking | 서울특별시 강남구 「공영주차장 정보」(2025-09-05), 공공데이터포털 15115393 | 공공누리 제1유형 |
| 역 화장실(대치역) | `server/daechi_map.json` toilets | 서울교통공사 「역사공중화장실정보」(2026-02-12), 공공데이터포털 15044453 | 이용허락범위 제한 없음. 좌표 오류가 있어 위치는 OSM 출입구 사용 |
| 방범 시설 26곳(보안등·안심벨·CCTV·112 안내) | `server/daechi_map.json` safety | 서울특별시 「안심귀갓길 안전시설물 통합데이터」(2023), 서울 열린데이터광장 OA-21696 | 공공누리 제1유형 |
| 해외 동네 지도(어바인·마컴·구루그람): 도로, 횡단보도, 신호, 정지표지, 버스정류장, 소화전, 학교, 상점, 주차장, 화장실, 드라이브스루, 가로등, 감시카메라 | `server/global/*_map.json` | © OpenStreetMap contributors (`server/global/fetch_city_osm.py`) | ODbL 1.0 |
| 고속도로 휴게소 전기차 충전소 9곳 좌표(충전소 단위)·충전기 목록 — 충전기 고장 신고의 충전소 반경 · 충전기 상태(stat, `server/link/fetch_ev_status.py` 가 허브에 올림) | `server/link/gyeongbu_route.json` evStations (`server/link/fetch_ev_stations.py`, 2026-11-06) | 한국환경공단 「전기자동차 충전소 정보」, 공공데이터포털 15076352(개발계정) | 공공누리 제1유형(출처표시) |
| 지도 아이콘 ORBIT 24종 | `server/icons/orbit` → 웹 SVG·안드로이드 벡터 | 팀 제공 원본 심볼 세트 | 팀 자산 |
| 지도 타일 | 웹 화면(`server/rider.html`) | © OpenStreetMap contributors, tile.openstreetmap.org | OSM 타일 이용 정책 — 시연 트래픽 한정. 서비스 단계에서는 상용 타일로 교체 |
| 지도 라이브러리 | 웹 화면 | Leaflet 1.9.4 (unpkg CDN) | BSD-2-Clause |
| 3D 차 모델 Kenney Car Kit 3.1 (glb 11개 + 공용 텍스처) | `server/link/models/` → 3D 관제 화면(`server/link/world.html`) | Kenney (https://kenney.nl). 2026-10-26 OpenGameArt 미러(https://opengameart.org/content/car-kit)에서 받음. 라이선스 원문 `server/link/models/LICENSE-kenney.txt` | CC0 1.0 — 출처 표시 선택(의무 없음) |
| 3D 차 모델 "Hyundai Ioniq 5 – Lowpoly" (GLB, 12.4k tri, 1k 텍스처) | `unreal/_import/ioniq5/` → Unreal 시연 장면(`unreal/Content/Vehicles/ioniq5`) 내 차·익명 차 | andikapratamaw, Sketchfab (https://sketchfab.com/3d-models/hyundai-ioniq-5-lowpoly-675e78311e8440d88714bd212cb7a8fb). 2026-10-26 받음. 비공식 팬 제작 모델(실차 데이터 아님) | CC BY 4.0 — 제작자 표기 필수(영상 크레딧·본 문서) |
| Quixel Megascans 2K 세트 5종 — Asphalt Road ×2(서피스, tigkfb3o·tigjbfxew), Yellow Road Line(데칼), Road Debris Pile(데칼), Asphalt Debris Pack(3D) | `unreal/_import/megascans/` → `unreal/Content/Megascans/` (도로 바닥·중앙선·낙하물 구간) | Quixel Megascans, Fab (https://www.fab.com/sellers/Quixel%20Megascans). 팀원 Epic 계정 라이브러리에 2024년 무료 획득분, 2026-10-26 Fab 에서 2K 로 내려받음 | Fab Standard License (상업 사용 가능, 재배포 불가 → 원본 zip 은 git 에 넣지 않음) |
| 2022 Hyundai Ioniq 5 3D 모델 (hkv-studios) — HIGH 1,850만 삼각형·4K 텍스처, 27파트·67재질 | `unreal/_import/ioniq5_hq/` → `unreal/Content/Vehicles/ioniq5_hq/` (내 차, 키비주얼·영상) | CGTrader https://www.cgtrader.com/3d-models/car/car/2022-hyundai-ioniq-5 — 2026-10-26 구매(주문번호: 기입 예정) | CGTrader Royalty Free License (no AI) — 상업 사용 가능, 재배포 불가 → 원본·임포트 결과 git 제외 |
| 3D 라이브러리 | 3D 관제 화면(`server/link/world.html`) | three.js r128 (cdnjs CDN) + GLTFLoader (three@0.128.0 examples, jsDelivr CDN) | MIT |
| Automotive Bridge Scene / Automotive Salt Flats / Automotive Winter Scene(언리얼 환경, 영상용) | `unreal/Content/Automotive*`(git 제외)와 이를 쓰는 다리 맵 | Epic Games, Fab 무료 | Fab Standard License — 프로젝트 내 사용·렌더 영상 배포 가능, 에셋 재배포 불가 |

규칙 근거(도로교통법 제32조, 과태료 금액, 버스전용차로 운영 시간, 강남구 단속 기준)는 `README.md`의 엔진 설명과 조사 노트에 출처를 달았다. 과태료와 버스전용차로 시간은 법령·고시 원문과 대조가 남아 있다.
