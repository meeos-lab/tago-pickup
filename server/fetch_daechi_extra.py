"""대치동 추가 변수용 OSM 데이터 (단속·감시 카메라, 주정차 차로 태그, 버스전용차로, 택시 승차대, 소방차 전용구역).

사용: python fetch_daechi_extra.py <out.json>  (© OpenStreetMap contributors, ODbL)
"""

import json
import sys
import urllib.parse
import urllib.request

from fetch_daechi_osm import BBOX, ENDPOINTS

QUERY = f"""
[out:json][timeout:90];
(
  node["man_made"="surveillance"]({BBOX});
  node["highway"="speed_camera"]({BBOX});
  node["enforcement"]({BBOX});
  relation["enforcement"]({BBOX});
  way["highway"]["parking:both"]({BBOX});
  way["highway"]["parking:left"]({BBOX});
  way["highway"]["parking:right"]({BBOX});
  way["highway"]["parking:lane:both"]({BBOX});
  way["highway"]["parking:condition:both"]({BBOX});
  way["highway"]["busway"]({BBOX});
  way["highway"]["lanes:bus"]({BBOX});
  way["highway"]["bus:lanes"]({BBOX});
  nwr["amenity"="taxi"]({BBOX});
  nwr["amenity"="fire_station"]({BBOX});
);
out center geom tags;
"""


def main(out_path):
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    for url in ENDPOINTS:
        try:
            req = urllib.request.Request(url, data=body, headers={"User-Agent": "pleos-meeos-prototype/0.1 (contest demo)"})
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.loads(r.read().decode("utf-8"))
            json.dump(data, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
            print(f"ok {url} elements={len(data.get('elements', []))}")
            return
        except Exception as e:  # noqa: BLE001
            print(f"fail {url}: {e}")
    raise SystemExit("all endpoints failed")


if __name__ == "__main__":
    main(sys.argv[1])
