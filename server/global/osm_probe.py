"""해외 시연 동네 고르기: 후보 범위마다 OSM 요소 개수만 센다(작은 조회). © OpenStreetMap contributors, ODbL"""
import json, sys, time, urllib.parse, urllib.request
CANDS = {
    "irvine-woodbridge": "33.6720,-117.8060,33.6880,-117.7880",
    "irvine-northwood": "33.7050,-117.7700,33.7200,-117.7500",
    "irvine-univpark": "33.6550,-117.8300,33.6680,-117.8100",
    "gurugram-sec43": "28.4550,77.0750,28.4680,77.0920",
    "gurugram-dlf1": "28.4650,77.0880,28.4780,77.1050",
    "gurugram-sec56": "28.4180,77.0950,28.4320,77.1120",
    "markham-unionville": "43.8600,-79.3250,43.8740,-79.3050",
    "markham-village": "43.8700,-79.2700,43.8840,-79.2500",
    "markham-cornell": "43.8900,-79.2400,43.9040,-79.2200",
}
CATS = [("crossing", 'node["highway"="crossing"]'), ("signals", 'node["highway"="traffic_signals"]'),
        ("bus_stop", 'node["highway"="bus_stop"]'), ("hydrant", 'node["emergency"="fire_hydrant"]'),
        ("school", 'nwr["amenity"~"^(school|kindergarten)$"]'), ("shop_cafe", 'nwr["shop"="convenience"];nwr["amenity"~"^(cafe|fast_food|pharmacy)$"]'),
        ("parking", 'nwr["amenity"="parking"]'), ("toilets", 'nwr["amenity"="toilets"]'), ("drive_thru", 'nwr["drive_through"="yes"]'),
        ("named_roads", 'way["highway"~"^(primary|secondary|tertiary|residential)$"]["name"]')]
for name, bb in CANDS.items():
    q = "[out:json][timeout:60];" + "".join(f"({';'.join(s + f'({bb})' for s in sel.split(';'))};);out count;" for _, sel in CATS)
    req = urllib.request.Request("https://overpass-api.de/api/interpreter", data=urllib.parse.urlencode({"data": q}).encode(),
                                 headers={"User-Agent": "meeos-prototype/0.1 (Hyundai Pleos contest demo)"})
    try:
        d = json.loads(urllib.request.urlopen(req, timeout=90).read())
        counts = [int(e["tags"]["total"]) for e in d["elements"]]
        print(name, dict(zip([c for c, _ in CATS], counts)), flush=True)
    except Exception as e:
        print(name, "fail", e, flush=True)
    time.sleep(2)
