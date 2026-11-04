"""대치동 학원가 일대 OpenStreetMap 데이터 수집 (키 불필요, ODbL: © OpenStreetMap contributors).

사용: python fetch_daechi_osm.py <out.json>
정차 금지 판단(도로교통법 제32조)에 쓸 요소: 버스정류장, 횡단보도, 신호 교차로, 소화전, 학교·유치원(어린이보호구역 추정)
만남 후보: 편의점·약국·카페·주차장·지하철 출구·학원
"""

import json
import sys
import urllib.parse
import urllib.request

BBOX = "37.4930,127.0540,37.5030,127.0700"   # 남,서,북,동 — 대치동 학원가(은마아파트·대치역·도곡로 일대)

QUERY = f"""
[out:json][timeout:90];
(
  way["highway"~"^(primary|secondary|tertiary|residential|unclassified|living_street)$"]({BBOX});
  node["highway"="bus_stop"]({BBOX});
  node["public_transport"="platform"]["bus"="yes"]({BBOX});
  node["highway"="crossing"]({BBOX});
  node["highway"="traffic_signals"]({BBOX});
  node["emergency"="fire_hydrant"]({BBOX});
  node["railway"="subway_entrance"]({BBOX});
  nwr["amenity"~"^(school|kindergarten|pharmacy|parking|cafe|prep_school)$"]({BBOX});
  nwr["shop"="convenience"]({BBOX});
  nwr["office"="educational_institution"]({BBOX});
);
out center geom tags;
"""

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]


def main(out_path: str) -> None:
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    last = None
    for url in ENDPOINTS:
        try:
            req = urllib.request.Request(url, data=body, headers={
                "User-Agent": "pleos-meeos-prototype/0.1 (contest demo; contact via project)",
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded",
            })
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.loads(r.read().decode("utf-8"))
            with open(out_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
            print(f"ok {url} elements={len(data.get('elements', []))}")
            return
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"fail {url}: {e}")
    raise SystemExit(f"all endpoints failed: {last}")


if __name__ == "__main__":
    main(sys.argv[1])
