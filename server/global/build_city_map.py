"""해외 시연 동네 지도 데이터 생성 (대치동 daechi_map.json과 같은 구조).

입력: fetch_city_osm.py 결과 (© OpenStreetMap contributors, ODbL)
출력: <도시>_map.json — 경로, 도로, 정차 규칙 요소(버스정류장·횡단보도·신호·정지표지·소화전·학교·소방서), 만남 후보,
      근처 장소(주차장·화장실·드라이브스루), 방범 참고(가로등·감시카메라)

사용: python build_city_map.py <도시키> <osm.json> <out.json>
"""
import json
import math
import sys

# 도시별 설정: 경로(차가 달리는 큰길)와 진행 방향(축 벡터, 북동 기준 x=동, y=북), 상호 언어
CITIES = {
    "irvine": {"bbox": (33.7050, -117.7700, 33.7200, -117.7500), "route": "Irvine Boulevard", "axis": (1.0, -0.45),
               "label": "Northwood, Irvine CA"},
    "gurugram": {"bbox": (28.4550, 77.0750, 28.4680, 77.0920), "route": "Sector 30M Wide Road", "axis": (1.0, 0.3),
                 "label": "Sector 43, Gurugram"},
    "markham": {"bbox": (43.8600, -79.3250, 43.8740, -79.3050), "route": "Main Street Unionville", "axis": (0.15, 1.0),
                "label": "Unionville, Markham ON"},
}

CAND_KIND = [  # (조건, kind, 기본 이름, 비 피함)
    (lambda t: t.get("shop") == "convenience", "convenience", "Convenience store", False),
    (lambda t: t.get("shop") == "supermarket", "supermarket", "Supermarket", True),
    (lambda t: t.get("amenity") == "pharmacy", "pharmacy", "Pharmacy", True),
    (lambda t: t.get("amenity") == "cafe", "cafe", "Cafe", True),
    (lambda t: t.get("amenity") == "fast_food", "fast_food", "Fast food", True),
    (lambda t: t.get("amenity") == "fuel", "fuel", "Gas station", True),
    (lambda t: t.get("amenity") == "parking" and t.get("access", "yes") in ("yes", "customers", "public", ""), "parking", "Parking lot", False),
]


def center_of(el):
    if "lat" in el:
        return el["lat"], el["lon"]
    if el.get("center"):
        return el["center"]["lat"], el["center"]["lon"]
    g = el.get("geometry") or []
    return (sum(p["lat"] for p in g) / len(g), sum(p["lon"] for p in g) / len(g)) if g else None


def main(city, osm_path, out_path):
    cfg = CITIES[city]
    s, w, n, e = cfg["bbox"]
    lat0, lon0 = (s + n) / 2, (w + e) / 2
    kx = math.cos(math.radians(lat0)) * 111320.0

    def xy(lat, lon):
        return ((lon - lon0) * kx, (lat - lat0) * 110540.0)

    def inside(lat, lon):
        return s <= lat <= n and w <= lon <= e

    els = json.load(open(osm_path, encoding="utf-8"))["elements"]
    roads, route_ways = [], []
    L = {k: [] for k in ("busStops", "crossings", "signals", "stops", "hydrants", "schools", "fireStations", "lamps", "cameras",
                         "parking", "toilets", "driveThru")}
    cands = []
    for el in els:
        t = el.get("tags", {})
        if el["type"] == "way" and "highway" in t and el.get("geometry"):
            if t["highway"] == "service" and not any(k.startswith("parking:") for k in t):
                continue
            roads.append({"name": t.get("name", ""), "highway": t["highway"], "oneway": t.get("oneway", ""),
                          "coords": [[p["lat"], p["lon"]] for p in el["geometry"]]})
            if t.get("name") == cfg["route"]:
                route_ways.append(el)
            continue
        c = center_of(el)
        if not c or not inside(*c):
            continue
        lat, lon = c
        name = t.get("name", "")
        p = {"lat": lat, "lon": lon}
        if t.get("highway") == "bus_stop" or (t.get("public_transport") == "platform" and t.get("bus") == "yes"):
            L["busStops"].append({**p, "name": name or "Bus stop"})
        elif t.get("highway") == "crossing":
            L["crossings"].append(p)
        elif t.get("highway") == "traffic_signals":
            L["signals"].append(p)
        elif t.get("highway") == "stop":
            L["stops"].append(p)
        elif t.get("highway") == "street_lamp":
            L["lamps"].append(p)
        elif t.get("emergency") == "fire_hydrant":
            L["hydrants"].append(p)
        elif t.get("man_made") == "surveillance":
            L["cameras"].append({**p, "type": t.get("surveillance:type", "")})
        elif t.get("amenity") in ("school", "kindergarten"):
            L["schools"].append({**p, "name": name or "School"})
        elif t.get("amenity") == "toilets":
            L["toilets"].append({**p, "name": name or "Public restroom", "access": t.get("access", ""), "hours": t.get("opening_hours", "")})
        if t.get("amenity") == "fire_station":
            L["fireStations"].append(p)
        if t.get("drive_through") == "yes" and t.get("amenity") in ("fast_food", "cafe", "restaurant", "pharmacy"):   # 은행 ATM 드라이브스루는 뺀다
            L["driveThru"].append({**p, "name": name or "Drive-thru", "brand": t.get("brand", ""), "hours": t.get("opening_hours", "")})
        for cond, kind, default, covered in CAND_KIND:
            if cond(t):
                c2 = {**p, "kind": kind, "icon": kind if kind not in ("supermarket", "fast_food", "fuel") else {"supermarket": "convenience", "fast_food": "cafe", "fuel": "parking"}[kind],
                      "name": name or default}
                if covered:
                    c2["covered"] = True
                if t.get("drive_through") == "yes":
                    c2["driveThru"] = True
                cands.append(c2)
                if kind == "parking":
                    L["parking"].append({**p, "name": name or default, "fee": t.get("fee", ""), "access": t.get("access", "")})
                break

    # 경로: 진행 방향 축으로 투영해 정렬. 일방통행으로 갈라진 구간은 진행 방향과 같은 쪽만 쓴다
    pts_all = [(p["lat"], p["lon"]) for w_ in route_ways for p in w_["geometry"]]
    if cfg["axis"] is None:   # 축이 없으면 가장 먼 두 점 방향
        xs = [xy(*q) for q in pts_all]
        a, b = max(((i, j) for i in range(0, len(xs), 3) for j in range(0, len(xs), 3)), key=lambda ij: math.dist(xs[ij[0]], xs[ij[1]]))
        ax = (xs[b][0] - xs[a][0], xs[b][1] - xs[a][1])
    else:
        ax = cfg["axis"]
    norm = math.hypot(*ax)
    ax = (ax[0] / norm, ax[1] / norm)
    proj = lambda q: xy(*q)[0] * ax[0] + xy(*q)[1] * ax[1]
    keep = []
    for w_ in route_ways:
        g = [(p["lat"], p["lon"]) for p in w_["geometry"]]
        if w_.get("tags", {}).get("oneway") == "yes" and proj(g[-1]) < proj(g[0]):
            continue
        keep += g
    pts = sorted(set(q for q in keep if inside(*q)), key=proj)
    route = [pts[0]]
    for q in pts[1:]:
        if math.dist(xy(*route[-1]), xy(*q)) > 3:
            route.append(q)
    length = sum(math.dist(xy(*a), xy(*b)) for a, b in zip(route, route[1:]))

    out = {"center": [lat0, lon0], "bbox": list(cfg["bbox"]), "routeName": cfg["route"], "route": route, "label": cfg["label"],
           "roads": roads, **L, "candidates": cands,
           "sources": ["© OpenStreetMap contributors (ODbL) — roads, crossings, signals, bus stops, hydrants, schools, POIs"]}
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print(city, f"route {len(route)}pts {length:.0f}m", {k: len(v) for k, v in L.items()}, "candidates", len(cands))


if __name__ == "__main__":
    main(*sys.argv[1:4])
