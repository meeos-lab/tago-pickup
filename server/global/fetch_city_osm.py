"""해외 시연 동네 OSM 데이터 수집 (키 불필요, ODbL: © OpenStreetMap contributors).

사용: python fetch_city_osm.py <도시키> <out.json>
대치동 수집(fetch_daechi_osm.py)과 같은 요소 + 화장실·드라이브스루·패스트푸드·감시카메라·가로등
"""
import json
import sys
import urllib.parse
import urllib.request

CITIES = {   # 남,서,북,동
    "irvine": "33.7050,-117.7700,33.7200,-117.7500",       # 미국 캘리포니아 어바인 Northwood (현대 미국 본사 Fountain Valley 인근)
    "gurugram": "28.4550,77.0750,28.4680,77.0920",          # 인도 하리아나 구루그람 Sector 43 (현대 인도 본사 소재 도시)
    "markham": "43.8600,-79.3250,43.8740,-79.3050",         # 캐나다 온타리오 마컴 Unionville (현대 캐나다 본사 소재 도시)
}


def query(bb):
    return f"""
[out:json][timeout:120];
(
  way["highway"~"^(primary|secondary|tertiary|residential|unclassified|living_street|service)$"]({bb});
  node["highway"~"^(bus_stop|crossing|traffic_signals|stop|street_lamp)$"]({bb});
  node["public_transport"="platform"]({bb});
  node["emergency"="fire_hydrant"]({bb});
  node["man_made"="surveillance"]({bb});
  nwr["amenity"~"^(school|kindergarten|pharmacy|parking|cafe|fast_food|toilets|police|fuel)$"]({bb});
  nwr["shop"~"^(convenience|supermarket)$"]({bb});
  nwr["drive_through"="yes"]({bb});
  way["highway"]["parking:both"]({bb});
  way["highway"]["parking:left"]({bb});
  way["highway"]["parking:right"]({bb});
);
out center geom tags;
"""


def main(city, out_path):
    body = urllib.parse.urlencode({"data": query(CITIES[city])}).encode()
    for url in ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"):
        try:
            req = urllib.request.Request(url, data=body, headers={"User-Agent": "meeos-prototype/0.1 (Hyundai Pleos contest demo)"})
            raw = urllib.request.urlopen(req, timeout=180).read()
            data = json.loads(raw)
            json.dump(data, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
            print(f"ok {city} {url} elements={len(data.get('elements', []))} bytes={len(raw)}")
            return
        except Exception as e:  # noqa: BLE001
            print(f"fail {url}: {e}")
    raise SystemExit("all endpoints failed")


if __name__ == "__main__":
    main(*sys.argv[1:3])
