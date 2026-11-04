"""대치동 일대 OSM 건물 주소(addr:street + addr:housenumber) 수집 — CCTV 도로명 주소를 좌표로 바꾸는 데 쓴다.

사용: python fetch_daechi_addr.py <out.json>  (© OpenStreetMap contributors, ODbL)
"""

import json
import sys
import urllib.parse
import urllib.request

from fetch_daechi_osm import ENDPOINTS

BBOX = "37.4880,127.0480,37.5080,127.0760"   # 지도 범위보다 조금 넓게(경계 근처 CCTV 대비)

QUERY = f"""
[out:json][timeout:120];
nwr["addr:housenumber"]["addr:street"]({BBOX});
out center tags;
"""


def main(out_path):
    body = urllib.parse.urlencode({"data": QUERY}).encode()
    for url in ENDPOINTS:
        try:
            req = urllib.request.Request(url, data=body, headers={"User-Agent": "pleos-meeos-prototype/0.1 (contest demo)"})
            with urllib.request.urlopen(req, timeout=150) as r:
                data = json.loads(r.read().decode("utf-8"))
            json.dump(data, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
            print(f"ok {url} elements={len(data.get('elements', []))}")
            return
        except Exception as e:  # noqa: BLE001
            print(f"fail {url}: {e}")
    raise SystemExit("all endpoints failed")


if __name__ == "__main__":
    main(sys.argv[1])
