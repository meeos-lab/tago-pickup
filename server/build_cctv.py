"""강남구 불법주정차 단속 고정형 CCTV 현황(xlsx) → daechi_cctv.json (대치동 시연 범위만).

입력
- 강남구청 주차 안내 페이지 첨부 「강남구_불법주정차 단속 고정형 CCTV 현황(2026.8.3).xlsx」
  시트: 고정형 CCTV 현황(연번·CCTV NO.·CCTV명·도로명 주소·지번 주소), 심야운영 CCTV 현황, 토·공휴일 운영 CCTV 현황
  좌표가 없어 도로명 주소를 OSM 건물 주소(addr:street + addr:housenumber)와 맞춰 좌표를 붙인다
- fetch_daechi_addr.py 결과(OSM 주소 JSON, © OpenStreetMap contributors, ODbL)
사용: python build_cctv.py <cctv.xlsx> <osm_addr.json> [daechi_cctv.json]
"""

import json
import re
import sys
from pathlib import Path

import openpyxl

from build_daechi_map import BBOX, in_bbox

# 간선도로(07–22시 단속)와 이면도로(08–22시)를 나누는 기준: 강남구 안내의 간선도로 이름 [추정 분류]
ARTERIAL = {"도곡로", "삼성로", "남부순환로", "영동대로", "선릉로", "역삼로", "테헤란로", "언주로", "봉은사로"}


def parse_road(addr):
    """'도곡로 84길 6' / '영동대로417' → ('도곡로84길', 6). 번지가 없으면 None."""
    if not addr:
        return None
    a = re.sub(r"\s+", " ", str(addr).strip())
    m = re.match(r"^(.+?(?:로|길))\s*(\d+)(?:-\d+)?$", a.replace(" 길", "길"))
    if not m:
        return None
    return m.group(1).replace(" ", ""), int(m.group(2))


def osm_index(path):
    idx = {}
    for el in json.load(open(path, encoding="utf-8"))["elements"]:
        t = el.get("tags", {})
        c = (el["lat"], el["lon"]) if "lat" in el else ((el["center"]["lat"], el["center"]["lon"]) if el.get("center") else None)
        num = re.match(r"^(\d+)", t.get("addr:housenumber", ""))
        if not c or not num:
            continue
        idx.setdefault(t["addr:street"].replace(" ", ""), []).append((int(num.group(1)), c))
    return idx


def locate(idx, road, num):
    """정확히 같은 번지 → 같은 도로·같은 쪽(홀짝)의 가장 가까운 번지(±10). (좌표, 방식) 또는 None."""
    cands = idx.get(road, [])
    exact = [c for n, c in cands if n == num]
    if exact:
        return exact[0], "exact"
    near = sorted(((abs(n - num), c) for n, c in cands if n % 2 == num % 2 and abs(n - num) <= 10), key=lambda x: x[0])
    return (near[0][1], f"near±{near[0][0]}") if near else None


def nominatim(query, cache):
    """OSM 주소에 없는 대치동 CCTV만 Nominatim으로 한 건씩 찾는다(공개 시설 주소, 1초 간격). 결과는 캐시 파일에 남긴다."""
    if query in cache:
        return cache[query]
    import time
    import urllib.parse
    import urllib.request
    time.sleep(1.1)
    url = "https://nominatim.openstreetmap.org/search?" + urllib.parse.urlencode({"q": query, "format": "json", "limit": 1, "countrycodes": "kr"})
    req = urllib.request.Request(url, headers={"User-Agent": "pleos-meeos-prototype/0.1 (contest demo)", "Accept-Language": "ko"})
    try:
        res = json.loads(urllib.request.urlopen(req, timeout=20).read())
        cache[query] = [float(res[0]["lat"]), float(res[0]["lon"])] if res else None
    except Exception as e:  # noqa: BLE001
        print("  nominatim 실패:", query, e)
        cache[query] = None
    return cache[query]


def main(xlsx, osm_addr, out="daechi_cctv.json"):
    wb = openpyxl.load_workbook(xlsx, read_only=True, data_only=True)
    sheets = wb.worksheets
    night = {r[1] for r in sheets[1].iter_rows(min_row=4, values_only=True) if r[1]} if len(sheets) > 1 else set()
    weekend = {r[1] for r in sheets[2].iter_rows(min_row=4, values_only=True) if r[1]} if len(sheets) > 2 else set()
    idx = osm_index(osm_addr)
    cache_file = Path(osm_addr).with_name("nominatim_cache.json")
    cache = json.loads(cache_file.read_text(encoding="utf-8")) if cache_file.exists() else {}
    out_rows, missed, total = [], [], 0
    for r in sheets[0].iter_rows(min_row=4, values_only=True):
        if not r or not r[0] or not r[1]:
            continue
        no, name, road_addr, jibun = str(r[1]).strip(), str(r[2] or "").strip(), r[3], str(r[4] or "").strip()
        if not jibun.startswith(("대치동", "도곡동", "역삼동", "삼성동")):
            continue
        total += 1
        pr = parse_road(road_addr)
        hit = locate(idx, *pr) if pr else None
        if not hit and jibun.startswith("대치동"):
            q = f"서울 강남구 {str(road_addr).strip()}" if pr else f"서울 강남구 {jibun}"
            ll = nominatim(q, cache) or (nominatim(f"서울 강남구 {jibun}", cache) if pr else None)
            if ll:
                hit = (tuple(ll), "nominatim")
        if not hit:
            missed.append(f"{no} {name} / {road_addr or '-'} / {jibun}")
            continue
        (lat, lon), how = hit
        if not in_bbox(lat, lon):
            continue
        road = re.sub(r"\d+길$", "", pr[0]) if pr else ""
        out_rows.append({"lat": lat, "lon": lon, "id": no, "name": name, "addr": str(road_addr or jibun).strip(), "match": how,
                         "arterial": bool(pr) and road in ARTERIAL and not re.search(r"\d+길$", pr[0]),
                         "night": no in night, "weekend": no in weekend})
    # 서로 다른 주소가 같은 좌표로 나오면 Nominatim이 동 중심점을 돌려준 것이므로 버린다
    from collections import Counter
    dup = Counter((x["lat"], x["lon"]) for x in out_rows if x["match"] == "nominatim")
    for x in [x for x in out_rows if x["match"] == "nominatim" and dup[(x["lat"], x["lon"])] > 1]:
        out_rows.remove(x)
        missed.append(f"{x['id']} {x['name']} / {x['addr']} (Nominatim이 동 중심점만 돌려줌)")
    cache_file.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
    Path(out).write_text(json.dumps(out_rows, ensure_ascii=False, indent=0), encoding="utf-8")
    print(f"대치·도곡·역삼·삼성동 {total}대 중 시연 범위 {BBOX} 안 {len(out_rows)}대 좌표 확보 "
          f"(정확 {sum(1 for x in out_rows if x['match'] == 'exact')} · 근사 {sum(1 for x in out_rows if x['match'].startswith('near'))} · Nominatim {sum(1 for x in out_rows if x['match'] == 'nominatim')}), "
          f"주소로 못 찾음 {len(missed)}대 · 심야 운영 {sum(x['night'] for x in out_rows)} · 토·공휴일 {sum(x['weekend'] for x in out_rows)} → {out}")
    for m in missed:
        print("  못 찾음:", m)


if __name__ == "__main__":
    main(*sys.argv[1:4])
