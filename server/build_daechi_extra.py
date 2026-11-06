"""대치동 지도에 근처 장소·방범 레이어를 더한다 (daechi_map.json에 publicParking·toilets·safety 키 추가).

입력 (공공데이터, 이용허락 제한 없음 또는 공공누리 1유형)
- 서울특별시 강남구_공영주차장 정보_20250905.csv (data.go.kr 15115393, CP949) — 이름·유형·면수·운영시간·요금·위경도
- 서울교통공사_역사공중화장실정보_20260212.csv (data.go.kr 15044453, CP949) — 역·게이트 안/밖·출입구·개방시간
    ※ 3호선 일부 역의 위경도가 한 역씩 밀려 있어(예: '매봉' 행 좌표가 실제 대치역) 위치는 OSM 출입구로 잡고 역명으로 정보를 붙인다
- 안심귀갓길 안전시설물 통합데이터_SHP (서울 열린데이터광장 OA-21696, 공공누리 1유형, 2023 현장조사) — 안심벨·CCTV·보안등·112 안내

사용: python build_daechi_extra.py <공영주차장.csv> <역화장실.csv> <시설물.shp 경로(확장자 없이)>
"""
import csv
import json
import struct
import sys
from pathlib import Path

HERE = Path(__file__).parent
BBOX = (37.4930, 127.0540, 37.5030, 127.0700)
inside = lambda lat, lon: BBOX[0] <= lat <= BBOX[2] and BBOX[1] <= lon <= BBOX[3]
FACILITY = {"301": "안심벨", "302": "CCTV", "303": "안내표지판", "304": "노면표기", "305": "보안등", "306": "안심귀갓길 안내판", "307": "112 위치 신고 안내", "308": "기타"}


def dbf(path, enc="cp949"):
    b = Path(path).read_bytes()
    n, hlen, rlen = struct.unpack("<IHH", b[4:12])
    fields, off = [], 32
    while b[off] != 0x0D:
        fields.append((b[off:off + 11].split(b"\0")[0].decode(enc, "replace"), b[off + 16]))
        off += 32
    rows = []
    for i in range(n):
        rec, p, row = b[hlen + i * rlen + 1: hlen + (i + 1) * rlen], 0, {}
        for name, flen in fields:
            row[name] = rec[p:p + flen].decode(enc, "replace").strip()
            p += flen
        rows.append(row)
    return rows


def shp_points(path):
    b, off, pts = Path(path).read_bytes(), 100, []
    while off < len(b):
        clen = struct.unpack(">I", b[off + 4:off + 8])[0] * 2
        pts.append(struct.unpack("<dd", b[off + 12:off + 28]) if struct.unpack("<i", b[off + 8:off + 12])[0] == 1 else None)
        off += 8 + clen
    return pts


def hhmm(v):
    v = (v or "").strip()
    return v if len(v) == 5 and v[2] == ":" else None


def main(parking_csv, toilet_csv, shp_base):
    path = HERE / "daechi_map.json"
    m = json.loads(path.read_text(encoding="utf-8"))

    parking = []
    with open(parking_csv, encoding="cp949") as f:
        parking_rows = list(csv.DictReader(f))
    for r in parking_rows:
        lat, lon = float(r["위도"]), float(r["경도"])
        if not inside(lat, lon):
            continue
        parking.append({"name": f"{r['주차장명']} 공영주차장", "lat": lat, "lon": lon, "type": r["주차장유형"], "spaces": int(r["주차구획수"] or 0),
                        "weekday": [hhmm(r["평일운영시작시각"]), hhmm(r["평일운영종료시각"])],
                        "saturday": [hhmm(r["토요일운영시작시각"]), hhmm(r["토요일운영종료시각"])],
                        "fee": f"{r['주차기본시간']}분 {r['주차기본요금']}원" if r["주차기본요금"] else ""})

    # 역 화장실: 위치는 OSM 출입구(가까운 출입구 번호 → 그 출구, 없으면 첫 출구)
    exits = {c["name"]: c for c in m["candidates"] if c["kind"] == "subway"}
    toilets = []
    with open(toilet_csv, encoding="cp949") as f:
        toilet_rows = list(csv.DictReader(f))
    for r in toilet_rows:
        r = {k.strip(): (v or "").strip() for k, v in r.items()}
        st = r["역명"]
        ex = exits.get(f"{st}역 {r['(근접) 출입구 번호']}번 출구") or next((c for n, c in exits.items() if n.startswith(f"{st}역")), None)
        if not ex:
            continue
        toilets.append({"name": f"{st}역 화장실", "lat": ex["lat"], "lon": ex["lon"], "inside": r["게이트 내외 구분"] == "내부",
                        "exit": r["(근접) 출입구 번호"], "hours": r["개방시간"], "detail": r["상세위치"], "kind": "subway"})

    safety = []
    for row, p in zip(dbf(shp_base + ".dbf"), shp_points(shp_base + ".shp")):
        if p and inside(p[1], p[0]):
            code = row.get("시설코드", "")
            safety.append({"lat": p[1], "lon": p[0], "code": code, "label": FACILITY.get(code, "기타"), "where": row.get("세부위치", "")})

    m["publicParking"], m["toilets"], m["safety"] = parking, toilets, safety
    src = [s for s in m["sources"] if not s.startswith(("서울특별시 강남구_공영주차장", "서울교통공사_", "안심귀갓길"))]
    m["sources"] = src + ["서울특별시 강남구_공영주차장 정보_20250905 (공공데이터포털, 공공누리 1유형) — 공영주차장",
                          "서울교통공사_역사공중화장실정보_20260212 (공공데이터포털) — 역 화장실",
                          "안심귀갓길 안전시설물 통합데이터 (서울 열린데이터광장 OA-21696, 공공누리 1유형, 2023) — 안심벨·CCTV·보안등"]
    path.write_text(json.dumps(m, ensure_ascii=False), encoding="utf-8")
    print(f"publicParking={len(parking)} toilets={len(toilets)} safety={len(safety)}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
