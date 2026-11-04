"""대치동 시연 지도 데이터 생성.

입력
- OSM(Overpass) JSON: fetch_daechi_osm.py 결과 (© OpenStreetMap contributors, ODbL)
- 서울시 소방재난본부_소방용수시설 CSV (서울 열린데이터광장, 공공누리 1유형, EUC-KR)
출력
- daechi_map.json: 경로(도곡로), 도로, 정차 금지 요소(버스정류장·횡단보도·신호교차로·소화전·학교), 만남 후보

사용: python build_daechi_map.py <osm.json> <hydrants.csv> <out.json>
"""

import csv
import json
import math
import sys

BBOX = (37.4930, 127.0540, 37.5030, 127.0700)   # 남, 서, 북, 동
LAT0, LON0 = 37.4980, 127.0620
ROUTE_NAME = "도곡로"


def to_xy(lat, lon):
    return ((lon - LON0) * math.cos(math.radians(LAT0)) * 111320.0, (lat - LAT0) * 110540.0)


def center_of(el):
    if "lat" in el:
        return el["lat"], el["lon"]
    if el.get("center"):
        return el["center"]["lat"], el["center"]["lon"]
    g = el.get("geometry") or []
    if g:
        return sum(p["lat"] for p in g) / len(g), sum(p["lon"] for p in g) / len(g)
    return None


def in_bbox(lat, lon):
    return BBOX[0] <= lat <= BBOX[2] and BBOX[1] <= lon <= BBOX[3]


def build_route(ways):
    """도곡로 way들을 이어 서→동 순서의 한 줄 경로로 만든다(중복 점 제거)."""
    pts = []
    for w in ways:
        for p in w["geometry"]:
            pts.append((p["lat"], p["lon"]))
    # 도곡로는 북서→남동으로 뻗어 있어 경도 순 정렬로 진행 순서를 근사한다
    pts = sorted(set(pts), key=lambda p: p[1])
    out = [pts[0]]
    for p in pts[1:]:
        x0, y0 = to_xy(*out[-1])
        x1, y1 = to_xy(*p)
        if math.hypot(x1 - x0, y1 - y0) > 3:
            out.append(p)
    return out


def main(osm_path, hyd_path, out_path):
    osm = json.load(open(osm_path, encoding="utf-8"))["elements"]
    roads, route_ways = [], []
    stops, crossings, signals, schools, cands = [], [], [], [], []
    for el in osm:
        t = el.get("tags", {})
        if el["type"] == "way" and "highway" in t and el.get("geometry"):
            road = {"name": t.get("name", ""), "highway": t["highway"], "oneway": t.get("oneway", ""),
                    "coords": [[p["lat"], p["lon"]] for p in el["geometry"]]}
            roads.append(road)
            g = el["geometry"]
            # 일방통행으로 갈라진 구간은 차가 달리는 동쪽 방향 차로만 경로로 쓴다
            if t.get("name") == ROUTE_NAME and not (t.get("oneway") == "yes" and g[0]["lon"] > g[-1]["lon"]):
                route_ways.append(el)
            continue
        c = center_of(el)
        if not c or not in_bbox(*c):
            continue
        lat, lon = c
        name = t.get("name", "")
        if t.get("highway") == "bus_stop" or (t.get("public_transport") == "platform" and t.get("bus") == "yes"):
            stops.append({"lat": lat, "lon": lon, "name": name or "버스정류장"})
        elif t.get("highway") == "crossing":
            crossings.append({"lat": lat, "lon": lon})
        elif t.get("highway") == "traffic_signals":
            signals.append({"lat": lat, "lon": lon})
        elif t.get("amenity") in ("school", "kindergarten") and ("초등학교" in name or "유치원" in name or "중학교" in name):
            schools.append({"lat": lat, "lon": lon, "name": name})
        elif t.get("railway") == "subway_entrance":
            ref = t.get("ref", "")
            cands.append({"lat": lat, "lon": lon, "kind": "subway", "icon": "subway",
                          "name": f"대치역 {ref}번 출구" if ref else "지하철 출구"})
        elif t.get("shop") == "convenience":
            cands.append({"lat": lat, "lon": lon, "kind": "convenience", "icon": "convenience", "name": name or "편의점"})
        elif t.get("amenity") == "pharmacy":
            cands.append({"lat": lat, "lon": lon, "kind": "pharmacy", "icon": "pharmacy", "name": name or "약국", "covered": True})
        elif t.get("amenity") == "cafe":
            cands.append({"lat": lat, "lon": lon, "kind": "cafe", "icon": "cafe", "name": name or "카페", "covered": True})
        elif t.get("amenity") == "parking":
            cands.append({"lat": lat, "lon": lon, "kind": "parking", "icon": "parking", "name": name or "주차장 입구"})
        elif t.get("amenity") in ("prep_school", "school") or t.get("office") == "educational_institution":
            if name and "학원" in name or "수학" in name or "어학" in name or "논술" in name or "두각" in name:
                cands.append({"lat": lat, "lon": lon, "kind": "academy", "icon": "academy", "name": name, "academy": True})

    hydrants = []
    with open(hyd_path, encoding="euc-kr", errors="replace", newline="") as f:
        for row in csv.DictReader(f):
            try:
                lat, lon = float(row["위도"]), float(row["경도"])
            except (KeyError, ValueError):
                continue
            if in_bbox(lat, lon):
                hydrants.append({"lat": lat, "lon": lon, "id": row.get("시설번호", ""), "addr": row.get("소재지도로명주소", "").strip()})

    route = build_route(route_ways)
    out = {
        "center": [LAT0, LON0], "bbox": BBOX, "routeName": ROUTE_NAME, "route": route,
        "roads": roads, "busStops": stops, "crossings": crossings, "signals": signals,
        "hydrants": hydrants, "schools": schools, "candidates": cands,
        "sources": [
            "© OpenStreetMap contributors (ODbL) — 도로·정류장·횡단보도·신호·학교·POI",
            "서울시 소방재난본부_소방용수시설_20251231 (서울 열린데이터광장, 공공누리 1유형) — 소화전",
        ],
    }
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"route pts={len(route)} roads={len(roads)} busStops={len(stops)} crossings={len(crossings)} "
          f"signals={len(signals)} hydrants={len(hydrants)} schools={len(schools)} candidates={len(cands)}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
