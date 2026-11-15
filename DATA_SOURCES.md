# 데이터 출처와 이용 조건

이 저장소에 들어 있는 지도·공공데이터와 출처 표시 의무를 정리한다. 앱 지도 화면 오른쪽 아래에도 같은 출처를 표시한다. 소프트웨어 라이브러리는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 에 있다. 마지막 확인: 2026-11-11.

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
| 지도 아이콘 ORBIT 24종 | `server/icons/orbit` → 웹 SVG·안드로이드 벡터 | 외부에서 받은 에셋이 아닌 원본 심볼 세트(`server/icons/orbit/README.txt`) | 출품작 자체 자산 |
| 지도 바탕(휴대폰 페이지·차 화면 지도) | `server/rider.html`, 앱 지도 | OpenFreeMap 스타일 + OpenMapTiles + © OpenStreetMap contributors(화면 출처 줄), 키가 있으면 TMAP 지도 | OSM 데이터 ODbL. OpenFreeMap 스타일·TMAP 지도의 이용 조건은 미확인(THIRD_PARTY_NOTICES.md 2.1) |

정차 규칙의 근거(도로교통법 제32조, 과태료 금액, 버스전용차로 운영 시간, 강남구 단속 기준) 가운데 과태료와 버스전용차로 시간은 법령·고시 원문과의 대조가 남아 있다.
