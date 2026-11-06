"""Tago(타고) 시연 서버 — 대치동 실제 데이터 버전.

- 지도: daechi_map.json (OSM 도로·정류장·횡단보도·신호·학교·POI + 서울시 소방용수시설 소화전)
- 차량(에뮬레이터) → http://10.0.2.2:8790, PC 브라우저 → http://127.0.0.1:8790
- 차는 도곡로를 동쪽(남동쪽)으로 달린다. 우측 통행이므로 진행 방향 오른쪽 길가가 '바로 설 수 있는 쪽'.
- 정차 금지(도로교통법 제32조): 횡단보도 10m, 버스정류장 10m, 교차로 가장자리 5m(신호교차로로 근사),
  소화전 5m, 어린이보호구역(학교 반경 300m로 추정 — 경찰청 보호구역 API로 교체 예정)
- 시연 전용: HTTP·무인증. 제품은 HTTPS, 익명 세션, 링크 자동 만료, 위치정보 동의가 필요하다.
"""

import heapq
import json
import math
import os
import re
import secrets
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).parent
# 지역 프로필: 정차 규칙(정차 금지 'stop' / 주차만 금지라 잠깐 태우기는 되는 'pickup'), 시간 한도, 언어, 시연 시나리오
REGION = os.environ.get("MEEOS_REGION", "daechi")
PORT = int(os.environ.get("MEEOS_PORT", "8790"))
PROFILE = json.loads((HERE / "regions" / f"{REGION}.json").read_text(encoding="utf-8"))
LANG = PROFILE["lang"]
MAP = json.loads((HERE / PROFILE["map"]).read_text(encoding="utf-8"))


def L(ko, en):
    """지역 언어에 맞는 문장 (한국어 문장은 대치동 결과와 같게 그대로 둔다)"""
    return ko if LANG == "ko" else en


CAR_CRUISE = 8.3                                   # m/s, 정차 중 ETA 계산용(30km/h)
WALK_SPEED = {"adult": 1.2, "child": 1.0, "senior": 0.7}
WALK_WEIGHT = {"adult": 1.0, "child": 1.3, "senior": 2.5}
# 대상별로 피하는 곳 / 선호하는 곳 (시연 규칙: 선호 = 비용에서 빼는 초)
AVOID = PROFILE["avoid"]
PREFER = {k: {kk: tuple(vv) for kk, vv in v.items()} for k, v in PROFILE["prefer"].items()}
WALK_DETOUR = 1.25                                 # 직선 거리 대비 실제 보행 거리
CROSS_WAIT = 60.0                                  # 큰길 횡단(신호 대기 포함)
UTURN_PENALTY = 120.0
LOOP_DETOUR = 100.0                                # 블록을 돌아 반대편으로 가는 추가 시간
# 정차 규칙 (레이어, 반경 m, 사유, 종류). 대치동: 도로교통법 제32조, 모두 주민신고 대상(1분 간격 사진 2장이면 과태료)
RULES = [(r["layer"], r["radius"], r["reason"], r.get("ban", "stop")) for r in PROFILE["rules"]]
# 승용차 과태료(만원). 서울시·지자체 안내 기준, 시행령 별표 원문 대조 필요. 보호구역 12만원은 08–20시, 그 밖의 시간은 4만원
FINE = {r["layer"]: r.get("fine", 0) for r in PROFILE["rules"]}
# 가로변 버스전용차로: 운영 시간에는 정차 금지 (평일만). 서울시 운영 기준, 2025-09 고시 원문 대조 필요
BUS_LANES = {k: (v[0], [tuple(x) for x in v[1]]) for k, v in PROFILE["busLanes"].items()}
STOP_LIMIT = PROFILE["stopLimit"]                  # 대치동: 정차는 5분까지. 넘으면 주차 — 강남구 고정형 CCTV는 5분 이상이면 단속
PEAK = tuple(PROFILE["peak"]) if PROFILE["peak"] else None   # 대치동 학원가 하원 시간 집중 단속(전담 단속반, 2025~)
PEAK_STOP = PROFILE["peakStop"]                    # 집중 단속 시간에는 차가 2분 넘게 서 있지 않게(시연 규칙)
PICKUP_ONLY = PROFILE["pickupOnly"]                # 주차만 금지인 곳: 잠깐 태우기만, 기다리지 않음
CAR_HALF = 2.5                                     # 차 길이 약 5m의 절반 — 차체 어느 부분도 금지 구간에 걸치지 않게
SEARCH_RADIUS = 450.0                              # 아이 위치에서 후보를 찾는 반경(m)
# 후보·평가 규칙의 고정값(앱 MeeosEngine 의 같은 이름과 같은 값 — tools/check_engine_consts.py 가 대조한다)
CURB_MAX_D = 40                                    # POI 에서 가장 가까운 도로가 이보다 멀면 후보에서 뺀다(m)
CURB_SHIFT_MAIN, CURB_SHIFT_SIDE = 9.0, 3.0        # 길가 정차점: 도로 중심에서 POI 쪽으로 옮기는 거리(경로 도로 / 골목, m)
RULE_CAR_HALF_BELOW = 100                          # 반경이 이보다 작은 규칙에만 차 길이 절반(CAR_HALF)을 더한다(m)
SIGNAL_ON_ROUTE = 30                               # 경로에서 이 안의 신호를 경로 위 신호로 본다(m)
SIDE_SPEED = 5.6                                   # 이면도로·못 갈 때 직선 속도(m/s, 20 km/h)
PASSED_BACK = 120                                  # 지나친 곳으로 되돌아오는 거리(m)
TURN_RIGHT_WAIT, TURN_LEFT_WAIT = 20, 45           # 반대편 후보로 가는 회전 신호 대기(초)
CAM_HOURS_ARTERIAL, CAM_HOURS_SIDE = (7, 22), (8, 22)   # 주정차 단속 CCTV 운영 시간(간선 / 이면, 시)
SCHOOL_FINE, SCHOOL_FINE_HOURS, SCHOOL_FINE_OFF = 12, (8, 20), 4   # 어린이 보호구역 과태료(만원)·가중 시간·시간 밖(대치동 값)
CROSS_NEAR = 200                                   # 큰길을 건너는지 보는 폭(m)
NIGHT_SAFE_PTS, NIGHT_SAFE_MAX = 5, 3              # 밤 방범 시설 가점: 개당·최대 개수
RAIN_WALK = 1.5                                    # 비 올 때 걷기 가중
MISMATCH_W, COVERED_BONUS, OVER_W = 0.5, 30, 2.0   # 비용: 서로 기다림 가중 · 지붕 있는 곳 가점(초) · 정차 한도 초과 가중
SENIOR_NEAR_M = 40                                 # 어르신이 '거의 걷지 않는' 거리(m)
WALK_ONE_MIN_S = 45                                # '도보 1분 이내'(초)
NEARBY_TOP = 3                                     # 근처 장소·포장 매장 개수
DEFAULT_HHMM = "22:00"                             # 시연 시계 기본 시각
ARRIVE_M, ARRIVE_MS = 30, 0.5                      # 확정 지점 이 거리 안·이 속도 아래면 도착
PLACE_NEAR_M = 150                                 # 차 위치를 'OO 근처'로 부르는 거리(m)

LAT0, LON0 = MAP["center"]


def xy(lat, lon):
    return ((lon - LON0) * math.cos(math.radians(LAT0)) * 111320.0, (lat - LAT0) * 110540.0)


def latlon(x, y):
    return (LAT0 + y / 110540.0, LON0 + x / (math.cos(math.radians(LAT0)) * 111320.0))


def rnd(x):
    """반올림(0.5는 올림) — Python rnd()는 짝수 쪽으로 반올림해 앱(Kotlin)과 결과가 갈린다."""
    return int(math.floor(x + 0.5))


def r1(x):
    return math.floor(x * 10 + 0.5) / 10


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


# ---------------------------------------------------------------- 경로(도곡로) 기하

# 강남구 불법주정차 단속 CCTV 위치(선택). 없으면 대치동 학원가 일부 24시간 단속을 감안해 모든 후보에 5분 기준을 적용
CCTV_FILE = HERE / PROFILE["cctv"] if PROFILE.get("cctv") else None
CCTV_RAW = json.loads(CCTV_FILE.read_text(encoding="utf-8")) if CCTV_FILE and CCTV_FILE.exists() else []
CCTV = [xy(p["lat"], p["lon"]) for p in CCTV_RAW]
CAM_RANGE = 100                                    # 고정형 CCTV 촬영 반경 "100m 내외"(강남구)
CAM_STOP = 240                                     # 운영 중인 카메라 앞: 5분 단속 전 1분 여유

ROUTE = [xy(*p) for p in MAP["route"]]
CUM = [0.0]
for i in range(1, len(ROUTE)):
    CUM.append(CUM[-1] + dist(ROUTE[i - 1], ROUTE[i]))
ROUTE_LEN = CUM[-1]
LAYERS = {k: [xy(p["lat"], p["lon"]) for p in MAP.get(k, [])] for k, _, _, _ in RULES}
LAYERS.setdefault("signals", [xy(p["lat"], p["lon"]) for p in MAP.get("signals", [])])


def project(p, line=ROUTE, cum=CUM):
    """점 p를 폴리라인에 투영: (경로상 거리 s, 부호 있는 수직 거리 off(+왼쪽), 투영점)."""
    best = (1e18, 0.0, 0.0, line[0])
    for i in range(len(line) - 1):
        a, b = line[i], line[i + 1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        L2 = dx * dx + dy * dy or 1e-9
        t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
        q = (a[0] + t * dx, a[1] + t * dy)
        d = dist(p, q)
        if d < best[0]:
            cross = dx * (p[1] - a[1]) - dy * (p[0] - a[0])
            best = (d, cum[i] + t * math.sqrt(L2), d if cross > 0 else -d, q)
    return best[1], best[2], best[3]


def point_at(s):
    s = max(0.0, min(ROUTE_LEN, s))
    for i in range(len(CUM) - 1):
        if CUM[i] <= s <= CUM[i + 1]:
            t = (s - CUM[i]) / ((CUM[i + 1] - CUM[i]) or 1e-9)
            a, b = ROUTE[i], ROUTE[i + 1]
            return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
    return ROUTE[-1]


SIGNAL_S = sorted(s for s, off, _ in map(project, LAYERS["signals"]) if abs(off) < SIGNAL_ON_ROUTE)   # 신호마다 투영은 한 번
ROADS = [[xy(*c) for c in r["coords"]] for r in MAP["roads"]]
ROAD_NAMES = [r["name"] for r in MAP["roads"]]


def nearest_road(p):
    best = (1e18, None, None)
    for line, name in zip(ROADS, ROAD_NAMES):
        for i in range(len(line) - 1):
            a, b = line[i], line[i + 1]
            dx, dy = b[0] - a[0], b[1] - a[1]
            L2 = dx * dx + dy * dy or 1e-9
            t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
            q = (a[0] + t * dx, a[1] + t * dy)
            d = dist(p, q)
            if d < best[0]:
                best = (d, q, name)
    return best


# ---------------------------------------------------------------- 도로망 경로 (직선 대신 실제 길)
# OSM 도로를 이어 그래프를 만든다. 걷기: 모든 도로 양방향(거리 m) / 차: 일방통행을 지키고 도로 등급별 속도(시간 s)
ROAD_SPEED = {"primary": 11.1, "secondary": 11.1, "tertiary": 8.3}   # m/s, 그 밖(이면도로) 5.6
NODE_ID, NODES, ADJ_W, ADJ_D, SEGS = {}, [], [], [], []


def _node(c):
    k = (c[0], c[1])
    if k not in NODE_ID:
        NODE_ID[k] = len(NODES)
        NODES.append(xy(c[0], c[1]))
        ADJ_W.append([])
        ADJ_D.append([])
    return NODE_ID[k]


for _r in MAP["roads"]:
    _ow, _v = _r.get("oneway", ""), ROAD_SPEED.get(_r["highway"], SIDE_SPEED)
    _ids = [_node(c) for c in _r["coords"]]
    for _a, _b in zip(_ids, _ids[1:]):
        if _a == _b:
            continue
        _L = dist(NODES[_a], NODES[_b])
        _fwd, _bwd = _ow != "-1", _ow not in ("yes", "1", "true")
        ADJ_W[_a].append((_b, _L))
        ADJ_W[_b].append((_a, _L))
        if _fwd:
            ADJ_D[_a].append((_b, _L / _v))
        if _bwd:
            ADJ_D[_b].append((_a, _L / _v))
        SEGS.append((_a, _b, _L, _v, _fwd, _bwd))


def snap(p):
    """점 → 가장 가까운 도로 구간 (거리, 구간 번호, 비율 t, 투영점)"""
    best = None
    for i, (a, b, L, v, fwd, bwd) in enumerate(SEGS):
        A, B = NODES[a], NODES[b]
        dx, dy = B[0] - A[0], B[1] - A[1]
        L2 = dx * dx + dy * dy or 1e-9
        t = max(0.0, min(1.0, ((p[0] - A[0]) * dx + (p[1] - A[1]) * dy) / L2))
        q = (A[0] + t * dx, A[1] + t * dy)
        d = dist(p, q)
        if best is None or d < best[0]:
            best = (d, i, t, q)
    return best


def _w(i, drive):
    a, b, L, v, fwd, bwd = SEGS[i]
    return L / v if drive else L


def dijkstra(sn, drive):
    """구간 위 한 점(snap)에서 모든 교차점까지 최소 비용(걷기 m / 차 s)과 직전 노드"""
    a, b, L, v, fwd, bwd = SEGS[sn[1]]
    w, t = _w(sn[1], drive), sn[2]
    adj = ADJ_D if drive else ADJ_W
    D, prev, h = [math.inf] * len(NODES), [-1] * len(NODES), []
    for node, c, ok in ((a, t * w, bwd or not drive), (b, (1 - t) * w, fwd or not drive)):
        if ok and c < D[node]:
            D[node] = c
            heapq.heappush(h, (c, node))
    while h:
        c, u = heapq.heappop(h)
        if c > D[u]:
            continue
        for x, wx in adj[u]:
            nc = c + wx
            if nc < D[x]:
                D[x], prev[x] = nc, u
                heapq.heappush(h, (nc, x))
    return D, prev


def arrive(D, src, dst, drive):
    """출발 snap에서 도착 snap까지 비용과 마지막 노드(-2 = 같은 구간에서 바로)"""
    a, b, L, v, fwd, bwd = SEGS[dst[1]]
    w, t = _w(dst[1], drive), dst[2]
    best, end = math.inf, -1
    if (fwd or not drive) and D[a] + t * w < best:
        best, end = D[a] + t * w, a
    if (bwd or not drive) and D[b] + (1 - t) * w < best:
        best, end = D[b] + (1 - t) * w, b
    if src[1] == dst[1]:
        dt = dst[2] - src[2]
        if (not drive or (dt >= 0 and fwd) or (dt <= 0 and bwd)) and abs(dt) * w < best:
            best, end = abs(dt) * w, -2
    return best, end


def route(p0, p1, drive, sn1=None):
    """실제 길 경로: (비용, 길이 m, [위경도…]). 못 가면 직선"""
    sn0, sn1 = snap(p0), sn1 or snap(p1)
    D, prev = dijkstra(sn0, drive)
    return reach(D, prev, p0, p1, sn0, sn1, drive)


def reach(D, prev, p0, p1, sn0, sn1, drive):
    cost, end = arrive(D, sn0, sn1, drive)
    if cost == math.inf:
        L = dist(p0, p1)
        return (L / SIDE_SPEED if drive else L), L, [latlon(*p0), latlon(*p1)]
    nodes = []
    while end >= 0:
        nodes.append(end)
        end = prev[end]
    pts = [sn0[3]] + [NODES[n] for n in reversed(nodes)] + [sn1[3]]
    if not drive:
        pts = [p0] + pts + [p1]
    length = sum(dist(x, y) for x, y in zip(pts, pts[1:]))
    return cost, length, [latlon(*x) for x in pts]


# 근처 장소·방범 레이어. 대치동: 공영주차장·역 화장실·안심귀갓길 시설물 / 해외: OSM 화장실·가로등·감시카메라
PUBLIC_PARKING = MAP.get("publicParking", [])
TOILETS = MAP.get("toilets", [])
DRIVE_THRU = MAP.get("driveThru", [])              # 드라이브스루(OSM drive_through=yes) — 음성 주문 목업
SAFETY = [xy(p["lat"], p["lon"]) for p in MAP.get("safety", MAP.get("lamps", []) + MAP.get("cameras", []))]
TOILET_SN = [(xy(t["lat"], t["lon"]), snap(xy(t["lat"], t["lon"]))) for t in TOILETS]
DRIVE_THRU_SN = [(xy(t["lat"], t["lon"]), snap(xy(t["lat"], t["lon"]))) for t in DRIVE_THRU]
PARKING_SN = [(xy(q["lat"], q["lon"]), snap(xy(q["lat"], q["lon"]))) for q in PUBLIC_PARKING]
SAFE_RANGE = 30.0                                  # 만남 지점 반경 30m 안 방범 시설 수
TOILET_RANGE = 150.0                               # 화장실이 급할 때: 화장실 150m 안 후보를 크게 우대
TOILET_BONUS = 90
RESTROOM_KINDS = ("cafe", "fast_food", "fuel")     # 손님 화장실을 쓸 수 있는 곳(카페·패스트푸드·주유소)
NIGHT = [(19, 24), (0, 6)]                         # 방범 시설을 따지는 시간(밤)

# 후보의 길가 정차점과 금지 사유는 세션과 무관하므로 미리 계산
CANDS = []
for i, c in enumerate(MAP["candidates"]):
    p = xy(c["lat"], c["lon"])
    d, q, road = nearest_road(p)
    if q is None or d > CURB_MAX_D:
        continue
    # 길가 정차점: 도로 중심선에서 POI 쪽으로 (큰길은 차로 폭을 감안해 9m, 골목은 3m)
    k = min(d, CURB_SHIFT_MAIN if road == MAP["routeName"] else CURB_SHIFT_SIDE) / (d or 1)
    curb = (q[0] + (p[0] - q[0]) * k, q[1] + (p[1] - q[1]) * k) if d > 0 else q
    s, off, _ = project(curb)
    blocked, pickup, fine = [], [], 0
    for layer, radius, reason, ban in RULES:
        near = min((dist(curb, o) for o in LAYERS[layer]), default=1e9)
        if near <= radius + (CAR_HALF if radius < RULE_CAR_HALF_BELOW else 0):
            (blocked if ban == "stop" else pickup).append(f"{reason} ({rnd(near)}m)")
            if ban == "stop":
                fine = max(fine, FINE[layer])
    cams = sorted((rnd(d), j) for j, d in ((j, dist(curb, o)) for j, o in enumerate(CCTV)) if d <= CAM_RANGE)
    if PROFILE.get("routeCurbBan") and road == MAP["routeName"]:
        blocked.append(PROFILE["routeCurbBan"])
    if c["kind"] == "academy" and PROFILE.get("academyRule"):
        # 학원 정문은 하원 시간 픽업 차량이 몰려 이중 정차가 생기는 곳(시연 규칙) — 출발지로만 쓴다
        blocked.append("학원 정문 앞 — 하원 시간 픽업 차량 밀집")
    clat, clon = latlon(*curb)
    CANDS.append({
        "id": f"c{i}", "name": c["name"], "icon": c["icon"], "kind": c["kind"], "covered": bool(c.get("covered")),
        "lat": c["lat"], "lon": c["lon"], "curb": [clat, clon], "road": road or L("골목", "side street"), "onRoute": road == MAP["routeName"],
        "s": r1(s), "off": r1(off), "blocked": blocked, "xy": curb, "poi": p, "fine": fine,
        "cams": cams, "pickup": pickup,
        "safe": sum(1 for o in SAFETY if dist(p, o) <= SAFE_RANGE),
        "wsnap": snap(p), "dsnap": snap(curb),
        "toilet": min((dist(p, tp) for tp, _ in TOILET_SN), default=None),
    })


def josa(word, with_final, without):
    last = word[-1] if word else ""
    if "가" <= last <= "힣" and (ord(last) - ord("가")) % 28:
        return with_final
    return without


# ---------------------------------------------------------------- 엔진

sessions = {}
lock = Lock()
# 세션 저장 상한(앱 내장 엔진 MAX_SESSIONS 와 같다). 넘치면 끝난 세션(탑승·하차)이나 오래 조용한 세션부터, 없을 때만 가장 오래된 것부터 지운다
MAX_BODY = 64 * 1024   # 요청 본문 상한(앱 LocalServer·pt_relay 의 MAX_BODY 와 같은 값)
MAX_SESSIONS = 100
IDLE_EVICT_SEC = 30 * 60
touched = {}                                       # 세션 id → 마지막으로 조회·조작된 시각(공개 상태에는 넣지 않는다)


def touch(sid):
    touched[sid] = time.time()


def evict_one():
    """세션 하나를 지운다: 끝난 세션 → 오래 조용한 세션 → 가장 오래 만든 세션 순(진행 중인 픽업은 마지막까지 남긴다)"""
    now = time.time()
    victim = next((sid for sid, s in sessions.items()
                   if s["status"] in ("boarded", "dropped") or now - touched.get(sid, s["created"]) > IDLE_EVICT_SEC), None)
    if victim is None:
        victim = next(iter(sessions), None)
    if victim is None:
        return
    sessions.pop(victim, None)
    touched.pop(victim, None)
    PATH_CACHE.pop(victim, None)


# 기다리는 사람의 메시지(차에서 소리 내어 읽는다): 제어 문자·줄바꿈은 빈칸으로, 보이지 않는 방향·폭 없는 문자는 빼고 80자까지
MSG_MAX = 80
MSG_CTRL = re.compile(r"[\x00-\x1f\x7f-\x9f\u2028\u2029]")
MSG_INVIS = re.compile(r"[\u200b\u200c\u200e\u200f\u202a-\u202e\u2066-\u2069\ufeff]")   # 보이지 않는 문자는 이스케이프로 적는다(앱 MSG_INVIS 와 같은 집합)

RIDER_DELAY = re.compile(r"([0-9]+) *분만|give me ([0-9]+) min", re.I)   # 기다리는 사람 "N분만"(앱 RIDER_DELAY 와 같다 — [0-9]·공백만)
HHMM = re.compile(r"[0-9]{2}:[0-9]{2}")   # 시연 시각 형식(앱 HHMM 과 같다 — Python \d 는 유니코드 숫자도 받으므로 [0-9])


def valid_clock(hhmm):
    """'hh:mm'(00:00–23:59)인지 — 세션 생성·시각 바꾸기 공용(앱 validClock 과 같다)"""
    return isinstance(hhmm, str) and bool(HHMM.fullmatch(hhmm)) and int(hhmm[:2]) < 24 and int(hhmm[3:5]) < 60


def finite_num(v):
    """JSON 숫자(불리언·글 아님)이고 유한하면 float, 아니면 None — 입력 검사용(앱 finiteNum 과 같다)"""
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) else None


def opt_string(d, key, fallback=""):
    """앱 엔진(org.json optString)과 같은 문자열 꺼내기: 키가 없으면 fallback, null 은 "null", 불리언은 true/false"""
    if key not in d:
        return fallback
    v = d[key]
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    return v if isinstance(v, str) else str(v)


def opt_bool(d, key, fallback=True):
    """앱 엔진(org.json optBoolean)과 같은 불리언 꺼내기: 불리언, 또는 문자열 true/false(대소문자 무시), 그 밖은 fallback"""
    v = d.get(key)
    if isinstance(v, bool):
        return v
    if isinstance(v, str) and v.lower() in ("true", "false"):
        return v.lower() == "true"
    return fallback


def clean_message(raw):
    t = MSG_INVIS.sub("", MSG_CTRL.sub(" ", "" if raw is None else str(raw)))
    t = re.sub(r" {2,}", " ", t).strip(" ")
    return t[:MSG_MAX].rstrip(" ")


def car_s(s):
    return min(ROUTE_LEN, s["scenario"]["carStartS"] + s["car"]["progress"])


def car_time(sess, c):
    cs = car_s(sess)
    v = max(sess["car"]["speed"], CAR_CRUISE) if sess["car"]["gear"] == "D" else CAR_CRUISE
    ahead = c["s"] - cs
    right = c["off"] < 0
    if c["onRoute"]:
        if right and ahead >= 0:
            return ahead / v, False, L("직진 후 오른쪽 길가 정차", "Straight ahead, stop at the right curb")
        if not right and ahead >= 0:
            # 왼쪽(반대편) 길가: 블록을 돌거나(우회전 3번) 다음 신호 교차로에서 유턴
            loop = ahead / v + LOOP_DETOUR
            nxt = next((x for x in SIGNAL_S if x > c["s"]), ROUTE_LEN)
            uturn = (nxt - cs) / v + UTURN_PENALTY + (nxt - c["s"]) / v
            return (loop, False, L("블록을 돌아 반대편 정차(유턴 없음)", "Go around the block to the far side (no U-turn)")) if loop <= uturn else (uturn, True, L("신호 교차로에서 유턴", "U-turn at the signal"))
        return (-ahead + PASSED_BACK) / v + UTURN_PENALTY * 2, True, L("지나쳐서 되돌아와야 함", "Passed it — must come back around")
    # 골목(옆길) 후보: 경로에서 꺾어 들어감
    turn = TURN_RIGHT_WAIT if right else TURN_LEFT_WAIT                              # 우회전 / 좌회전 신호 대기
    if ahead >= 0:
        return ahead / v + abs(c["off"]) / v + turn, False, L("우회전 후 골목 정차", "Turn right into the side street") if right else L("좌회전 후 골목 정차", "Turn left into the side street")
    return (-ahead + PASSED_BACK) / v + abs(c["off"]) / v + UTURN_PENALTY, True, L("지나쳐서 되돌아와야 함", "Passed it — must come back around")


def clock(sess):
    """시연 시계: (시각(시, 소수), 평일 여부). 실서비스는 차량 시계."""
    c = sess.get("clock") or {"hhmm": DEFAULT_HHMM, "weekday": True}
    h, mnt = (int(x) for x in c["hhmm"].split(":"))
    return h + mnt / 60, bool(c.get("weekday", True))


def in_hours(h, spans):
    return any(a <= h < b for a, b in spans)


def cam_active(cam, h, weekday):
    """강남구 고정형 CCTV 운영 시간: 간선 07–22시, 이면 08–22시, 심야 운영 카메라는 종일[추정], 주말은 토·공휴일 운영 카메라만."""
    if not weekday and not cam.get("weekend"):
        return False
    return cam.get("night") or in_hours(h, [CAM_HOURS_ARTERIAL] if cam.get("arterial") else [CAM_HOURS_SIDE])


def stop_limit(sess, c):
    """이 지점에서 차가 서 있어도 되는 시간(초)과 사유."""
    h, weekday = clock(sess)
    if PEAK and in_hours(h, [PEAK]):
        return PEAK_STOP, "학원가 하원 집중 단속 시간(21–23시)"
    if c.get("pickup"):
        return PICKUP_ONLY, L(f"잠깐 태우기만 — {c['pickup'][0]}", f"Pickup only, no waiting — {c['pickup'][0]}")
    if not CCTV:
        return STOP_LIMIT, PROFILE.get("stopWhyDefault", "정차 5분 초과 시 주차(학원가 CCTV 24시간)")
    on = [(d, CCTV_RAW[j]) for d, j in c["cams"] if cam_active(CCTV_RAW[j], h, weekday)]
    if on:
        d, cam = on[0]
        return CAM_STOP, f"단속 CCTV {d}m 운영 중({cam['name']}) — 5분 넘으면 단속"
    if c["cams"]:
        return STOP_LIMIT, f"단속 CCTV {c['cams'][0][0]}m 있지만 지금은 운영 시간 아님 — 정차는 5분까지"
    return STOP_LIMIT, "주변 단속 CCTV 없음 — 정차는 5분까지"


def evaluate(sess):
    r = sess["rider"]
    if r["lat"] is None:
        return None
    kind = sess["member"]["type"]
    rp = xy(r["lat"], r["lon"])
    r_off = project(rp)[1]
    raining = sess["car"].get("wiper", "OFF") not in ("OFF", "OTHER", None, "", "null")   # 앱 raining 판정과 같은 집합
    hour, weekday = clock(sess)
    scored, excluded = [], []
    passed, cs0 = sess.get("passed", []), car_s(sess)   # 평가 중 세션은 바뀌지 않는다 — 후보마다 다시 계산하지 않는다
    for c in CANDS:
        straight = dist(rp, c["poi"])
        if straight > SEARCH_RADIUS:
            continue
        if c["id"] in passed:
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "reason": L("방금 서지 못하고 지나침", "Just drove past it — couldn't stop"),
                             "curb": c["curb"], "dist": rnd(straight), "fine": None})
            continue
        if passed and c["s"] < cs0:   # 한 번 지나친 뒤에는 차보다 앞에 있는 곳만
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "reason": L("이미 지나온 곳", "Already behind the car"),
                             "curb": c["curb"], "dist": rnd(straight), "fine": None})
            continue
        if c["blocked"]:
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "reason": c["blocked"][0], "curb": c["curb"],
                             "dist": rnd(straight), "fine": c["fine"] if c["fine"] != SCHOOL_FINE or in_hours(hour, [SCHOOL_FINE_HOURS]) else SCHOOL_FINE_OFF})
            continue
        lane = BUS_LANES.get(c["road"])
        if lane and weekday and in_hours(hour, lane[1]):
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "reason": lane[0], "curb": c["curb"],
                             "dist": rnd(straight), "fine": 4})
            continue
        crossing = c["onRoute"] and abs(r_off) < CROSS_NEAR and (r_off > 0) != (c["off"] > 0)
        crossing = crossing or (not c["onRoute"] and abs(r_off) < CROSS_NEAR and abs(c["off"]) < CROSS_NEAR and (r_off > 0) != (c["off"] > 0))
        if crossing and kind in ("child", "senior"):
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "curb": c["curb"], "dist": rnd(straight),
                             "reason": L(f"{'아이가' if kind == 'child' else '어르신이'} 큰길({MAP['routeName']})을 건너야 함",
                                         f"{'Child' if kind == 'child' else 'Senior'} would have to cross {MAP['routeName']}")})
            continue
        if c["kind"] in AVOID.get(kind, {}):
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "curb": c["curb"], "dist": rnd(straight),
                             "reason": AVOID[kind][c["kind"]]})
            continue
        # 걷기는 직선 × 1.25: OSM 차도 데이터에는 보도·횡단보도·지하통로가 없어 도로망으로 재면 크게 부풀어진다(대치역 출구 간 등)
        walk = straight * WALK_DETOUR / WALK_SPEED[kind] + (CROSS_WAIT if crossing else 0)
        bonus, why = PREFER.get(kind, {}).get(c["kind"], (0, ""))
        if sess.get("needToilet"):
            if c["toilet"] is not None and c["toilet"] <= TOILET_RANGE:
                bonus, why = bonus + TOILET_BONUS, L("화장실에 들르기 좋은", "next to a restroom")
            elif c["kind"] in RESTROOM_KINDS:
                bonus, why = bonus + TOILET_BONUS, L("화장실을 쓸 수 있는", "with a customer restroom")
        if in_hours(hour, NIGHT):
            bonus += NIGHT_SAFE_PTS * min(c["safe"], NIGHT_SAFE_MAX)          # 밤에는 방범 시설(보안등·CCTV·안심벨) 있는 곳을 조금 더
        ct, uturn, how = car_time(sess, c)
        w = WALK_WEIGHT[kind] * (RAIN_WALK if raining else 1.0)
        # 아이가 지금 출발한다고 볼 때 차가 길가에서 기다리는 시간. 정차 한도를 넘으면 후보에서 뺀다
        car_wait = max(0.0, walk - ct)
        limit, limit_why = stop_limit(sess, c)
        if car_wait > STOP_LIMIT:
            excluded.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "curb": c["curb"], "dist": rnd(straight),
                             "reason": L(f"차가 {rnd(car_wait / 60)}분 넘게 서 있어야 함 — 주차로 보고 CCTV 단속",
                                         f"Car would wait over {rnd(car_wait / 60)} min — that counts as parking"), "fine": FINE.get("busStops", 0)})
            continue
        over = max(0.0, car_wait - limit)
        cost = (ct + w * walk + MISMATCH_W * abs(ct - walk) - (COVERED_BONUS if raining and c["covered"] else 0) - bonus + OVER_W * over)
        scored.append({"id": c["id"], "name": c["name"], "icon": c["icon"], "curb": c["curb"], "road": c["road"],
                       "cost": rnd(cost), "carSec": rnd(ct), "walkSec": rnd(walk), "uturn": uturn,
                       "crossing": crossing, "route": how, "covered": c["covered"], "s": c["s"], "prefer": why,
                       "carWait": rnd(car_wait), "stopLimit": limit, "stopWhy": limit_why, "safety": c["safe"]})
    scored.sort(key=lambda x: x["cost"])
    if not scored:
        return {"best": None, "alt": None, "excluded": excluded, "reason": L("근처에 정차 가능한 곳이 없습니다", "No legal place to stop nearby")}
    best = scored[0]
    reason, speech = (explain if LANG == "ko" else explain_en)(sess, best, excluded, raining, scored)
    return {"best": best, "alt": scored[1] if len(scored) > 1 else None, "excluded": excluded,
            "considered": len(scored) + len(excluded), "reason": reason, "speech": speech, "raining": raining}


def explain(sess, best, excluded, raining, scored):
    name = sess["member"]["name"]
    kind = sess["member"]["type"]
    parts = []
    banned = [e for e in excluded if (c := cand_by_id(e["id"])) and c["blocked"][:1] == [e["reason"]]]   # 정차 금지 규칙(blocked)으로 뺀 곳만 센다(앱과 같다) — 문구로 세면 어바인 소화전(§22514)처럼 금지 문구가 없는 규칙을 놓친다
    lead = ""
    words = {"소화전": "소화전과", "횡단보도": "횡단보도와", "버스정류장": "버스정류장과", "교차로": "교차로와"}
    legal = [e for e in banned if 20 <= e["dist"] < 150 and any(e["reason"].startswith(k) for k in words)]
    if legal:
        e = min(legal, key=lambda e: e["dist"])
        k = next(k for k in words if e["reason"].startswith(k))
        gap = e["reason"].rsplit("(", 1)[-1].rstrip(")") if "(" in e["reason"] else ""
        lead = f"{'바로 옆' if e['dist'] < 60 else '근처'} {e['name']}{josa(e['name'], '은', '는')} {words[k]} {gap} 거리라 제외했습니다. "
    if banned:
        lead += f"정차 금지 {len(banned)}곳 제외. "
    who = f"{name}{josa(name, '이가', '가')}"
    walk_m = best["walkSec"] * WALK_SPEED[kind]
    closest = min(scored, key=lambda x: x["walkSec"])
    saved = closest["carSec"] - best["carSec"]
    if kind == "senior" and walk_m < SENIOR_NEAR_M:
        parts.append(f"{who} 거의 걷지 않도록 차가 {'블록을 돌아 ' if '블록' in best['route'] else ''}바로 앞에 서는")
    elif kind in ("child", "senior") and not best["crossing"]:
        parts.append(f"{who} 큰길을 건너지 않는")
    elif best["crossing"]:
        parts.append(f"{who} 횡단보도로 건너오는")
    if raining and best["covered"]:
        parts.append("비를 피할 수 있는")
    if best.get("prefer") and not (kind == "senior" and walk_m < SENIOR_NEAR_M):
        parts.append(best["prefer"])
    if sess.get("needToilet") and best.get("prefer") in ("화장실에 들르기 좋은", "화장실을 쓸 수 있는"):
        if best["prefer"] in parts:
            parts.remove(best["prefer"])
        parts.insert(0, best["prefer"])            # 화장실이 급할 때는 그 이유를 먼저 말한다
    if kind == "adult" and best is not closest and saved >= 20:
        parts.insert(0, f"조금 걷지만 차가 {rnd(saved / 60) if saved >= 60 else saved}{'분' if saved >= 60 else '초'} 빨리 도착하는")
    if not best["uturn"] and "블록" not in best["route"]:
        parts.append("유턴 없이 가는")
    walk_txt = "도보 1분 이내" if best["walkSec"] < WALK_ONE_MIN_S else f"도보 {rnd(best['walkSec'] / 60)}분"
    lane = next((e for e in excluded if "버스전용차로" in e["reason"]), None)
    if lane:
        lead += f"버스전용차로 운영 시간이라 {lane['name']} 쪽 큰길은 제외했습니다. "
    tail = ""
    if best["stopLimit"] == PEAK_STOP and kind != "senior":
        tail = f" 하원 단속 시간이라 {name}에게 1분 먼저 나오도록 알립니다."
    elif best["stopLimit"] == PEAK_STOP:
        tail = f" 야간이라 {name}{josa(name, '이', '가')} 밖에서 기다리지 않도록, 차량 도착 직전에 나오시도록 알립니다."
    screen = (f"{lead}{', '.join(parts[:2]) + ' ' if parts else ''}{best['name']} 앞을 제안합니다. "
              f"{name}{josa(name, '이는', '는')} {walk_txt}, 차량은 {max(1, rnd(best['carSec'] / 60))}분 후 도착합니다.{tail}")
    # 운전 중 음성은 한 문장(주의분산 최소화): 금지 구역 수 + 핵심 이유 1개 + 장소
    hook = f"정차 금지 {len(banned)}곳 제외. " if banned else ""
    key = parts[0] if parts else ""
    if kind == "senior" and walk_m < SENIOR_NEAR_M:
        key = f"{who} 거의 걷지 않는"
    speech = f"{hook}{key + ' ' if key else ''}{best['name']} 앞을 제안합니다."
    return screen, speech


def mins_en(sec):
    return "under a minute" if sec < WALK_ONE_MIN_S else f"{rnd(sec / 60)} min"


def explain_en(sess, best, excluded, raining, scored):
    name = sess["member"]["name"]
    kind = sess["member"]["type"]
    banned = [e for e in excluded if (c := cand_by_id(e["id"])) and c["blocked"][:1] == [e["reason"]]]   # 정차 금지 규칙(blocked)으로 뺀 곳만 센다(앱과 같다) — 문구로 세면 어바인 소화전(§22514)처럼 금지 문구가 없는 규칙을 놓친다
    spots = f"{len(banned)} no-stopping spot{'s' if len(banned) != 1 else ''}"
    lead = f"Skipped {spots}. " if banned else ""
    parts = []
    walk_m = best["walkSec"] * WALK_SPEED[kind]
    closest = min(scored, key=lambda x: x["walkSec"])
    saved = closest["carSec"] - best["carSec"]
    if kind == "senior" and walk_m < SENIOR_NEAR_M:
        parts.append(f"{name} barely has to walk")
    elif kind in ("child", "senior") and not best["crossing"]:
        parts.append(f"{name} doesn't cross {MAP['routeName']}")
    elif best["crossing"]:
        parts.append(f"{name} crosses at the crosswalk")
    if raining and best["covered"]:
        parts.append("out of the rain")
    if best.get("prefer") and not (kind == "senior" and walk_m < SENIOR_NEAR_M):
        parts.append(best["prefer"])
    if sess.get("needToilet") and best.get("prefer") in ("next to a restroom", "with a customer restroom"):
        if best["prefer"] in parts:
            parts.remove(best["prefer"])
        parts.insert(0, best["prefer"])
    if kind == "adult" and best is not closest and saved >= 20:
        parts.insert(0, f"a short walk saves the car {mins_en(saved)}")
    if not best["uturn"] and "block" not in best["route"]:
        parts.append("no U-turn")
    if best["stopLimit"] == PICKUP_ONLY:
        parts.append("pickup only — be ready when the car arrives")
    screen = (f"{lead}Meet at {best['name']}: {', '.join(parts[:2])}. "
              f"{name} walks {mins_en(best['walkSec'])}, the car arrives in {max(1, rnd(best['carSec'] / 60))} min.")
    hook = f"Avoiding {spots}, " if banned else ""
    key = f" — {parts[0]}" if parts else ""
    speech = f"{hook}let's meet at {best['name']}{key}."
    return screen, speech[0].upper() + speech[1:]


def cand_by_id(cid):
    return next((c for c in CANDS if c["id"] == cid), None)


# 근처 장소: 주차장(정차 한도를 넘겨 기다려야 할 때) · 화장실(급할 때)
NEARBY_KINDS = ("parking", "toilet", "drivethru", "takeout")   # 근처 장소 종류(소속 검사만 한다)
# 가는 길 포장 주문: 앞에 차를 세울 수 있는(정차 금지 아님) 매장만. 준비 시간은 시연 가정
TAKEOUT_PREP = {"cafe": 300, "fast_food": 420, "convenience": 60}
TAKEOUT_STOP = 90                                  # 매장 앞에 세우고 받아 오는 시간(초)


WALK_PATH_MAX = 1.6                                # 도로망 걷는 길이 직선의 1.6배를 넘으면 지도에는 직선으로(보도 데이터 없음)


def walk_m_to(rp, c):
    """아이 위치 → 후보까지 걷는 거리(m): 직선 × 1.25 (보도 데이터를 넣으면 도로망으로 바꾼다)"""
    return dist(rp, c["poi"]) * WALK_DETOUR


def place_name(c):
    """OSM에 이름이 없는 주차장('주차장 입구')은 길 이름으로 부른다"""
    if c["name"] in ("주차장 입구", "주차장"):
        return "골목 안 주차장" if c["road"] == "골목" else f"{c['road']} 주차장"
    if c["name"] == "Parking lot":
        return f"Parking lot on {c['road']}"
    return c["name"]


def open_now(span, h):
    """운영 시간 [시작, 끝] 안인지. 00:00–00:00은 운영 안 함, 끝이 23:59면 자정까지"""
    a, b = span if span else (None, None)
    if not a or not b or a == b:
        return False
    ha, hb = int(a[:2]) + int(a[3:]) / 60, int(b[:2]) + int(b[3:]) / 60
    return ha <= h <= hb if ha < hb else (h >= ha or h <= hb)


def nearby(s, kind, origin=None):
    """근처 장소 — 거리·시간은 직선이 아니라 실제 길(차: 일방통행·도로 속도, 걷기: 도로)"""
    conf = cand_by_id(s["confirmed"]) if s["confirmed"] else None
    best = (s.get("proposal") or {}).get("best")
    car = point_at(car_s(s))
    rider = xy(s["rider"]["lat"], s["rider"]["lon"]) if s["rider"]["lat"] is not None else None
    if origin is None:
        origin = "car" if kind in ("toilet", "drivethru") else "target"
    if origin == "rider" and rider:
        ref = rider
    elif origin == "car" or not (conf or best):
        ref, origin = car, "car"
    else:
        ref, origin = (conf["xy"], "target") if conf else (cand_by_id(best["id"])["xy"], "proposal")
    drive = origin != "rider"
    ref_sn = snap(ref)
    D, prev = dijkstra(ref_sn, True) if drive else (None, None)

    def net(pt, sn):                               # (비용, 길이 m): 차는 도로망, 걷기는 직선 × 1.25
        if not drive:
            L = dist(ref, pt) * WALK_DETOUR
            return L, L
        c, L, _ = reach(D, prev, ref, pt, ref_sn, sn, True)
        return c, L

    h, weekday = clock(s)
    items = []
    if kind == "toilet":
        opts = []
        for t, (pt, sn) in zip(TOILETS, TOILET_SN):
            c, L = net(pt, sn)
            opts.append((L, t))
        opts.sort(key=lambda o: o[0])
        for L, t in opts[:NEARBY_TOP]:
            items.append({"name": t["name"], "lat": t["lat"], "lon": t["lon"], "curb": [t["lat"], t["lon"]],
                          "dist": rnd(L), "inside": bool(t.get("inside")), "hours": t.get("hours", ""),
                          "exit": t.get("exit", ""), "detail": t.get("detail", "")})
    elif kind == "takeout":
        # 기준: 확정 지점(없으면 제안 1순위). 남는 시간 = 아이가 실제 길로 걸어오는 시간 + "N분만 기다려" − 차가 바로 가는 시간
        tgt = conf or (cand_by_id(best["id"]) if best else None)
        if tgt:
            origin = "target" if conf else "proposal"
            car_sn = snap(car)
            CD, CP = dijkstra(car_sn, True)
            direct = arrive(CD, car_sn, tgt["dsnap"], True)[0]
            walk = walk_m_to(rider, tgt) / WALK_SPEED[s["member"]["type"]] if rider else 0.0
            slack = max(0.0, walk + s.get("riderDelay", 0) - direct)
            cs, opts = car_s(s), []
            for c in CANDS:
                if c["kind"] not in TAKEOUT_PREP or c["blocked"] or c["id"] == tgt["id"] or c["s"] < cs - 5:
                    continue
                to_store = arrive(CD, car_sn, c["dsnap"], True)[0]
                SD, _ = dijkstra(c["dsnap"], True)
                to_target = arrive(SD, c["dsnap"], tgt["dsnap"], True)[0]
                if to_store == math.inf or to_target == math.inf:
                    continue
                extra = max(0.0, to_store + TAKEOUT_STOP + to_target - direct)   # 경유지(매장)를 거쳐 늘어나는 시간
                wait = max(0.0, TAKEOUT_PREP[c["kind"]] - to_store)              # 지금 주문하면 매장 앞에서 기다리는 시간
                late = max(0.0, extra + wait - slack)                           # 아이보다 늦게 닿는 시간
                opts.append((rnd(late), rnd(extra), c, to_store, wait))
            opts.sort(key=lambda o: (o[0], o[1], o[2]["id"]))
            for late, extra, c, to_store, wait in opts[:NEARBY_TOP]:
                items.append({"id": c["id"], "name": c["name"], "kind": c["kind"], "lat": c["lat"], "lon": c["lon"], "curb": c["curb"],
                              "dist": rnd(reach(CD, CP, car, c["xy"], car_sn, c["dsnap"], True)[1]), "extraSec": extra, "lateSec": late,
                              "slackSec": rnd(slack), "carToStoreSec": rnd(to_store), "prepSec": TAKEOUT_PREP[c["kind"]], "waitSec": rnd(wait)})
    elif kind == "drivethru":
        cs, opts = car_s(s), []
        for t, (pt, sn) in zip(DRIVE_THRU, DRIVE_THRU_SN):
            c, L = net(pt, sn)
            opts.append((L, t, pt))
        opts.sort(key=lambda o: o[0])
        for L, t, pt in opts[:NEARBY_TOP]:
            items.append({"name": t["name"], "brand": t.get("brand", ""), "lat": t["lat"], "lon": t["lon"], "curb": [t["lat"], t["lon"]],
                          "dist": rnd(L), "ahead": project(pt)[0] >= cs, "hours": t.get("hours", "")})
    else:
        # 공영주차장(지금 운영 중, 요금·시간 있음) + OSM 주차장(운영 정보 없음)을 합쳐 실제 길로 가까운 3곳
        pool = []
        for q, (pt, sn) in zip(PUBLIC_PARKING, PARKING_SN):
            span = q["weekday"] if weekday else q["saturday"]
            if open_now(span, h):
                pool.append((net(pt, sn)[1], {"name": q["name"], "lat": q["lat"], "lon": q["lon"], "curb": [q["lat"], q["lon"]],
                             "fee": q["fee"], "type": q["type"], "spaces": q["spaces"], "hours": "–".join(x or "" for x in span), "public": True}))
        for c in CANDS:
            if c["kind"] == "parking":
                pool.append((net(c["xy"], c["dsnap"])[1], {"id": c["id"], "name": place_name(c), "lat": c["lat"], "lon": c["lon"], "curb": c["curb"], "public": False}))
        pool.sort(key=lambda x: x[0])
        items = [{**it, "dist": rnd(d)} for d, it in pool[:NEARBY_TOP]]
    return {"kind": kind, "from": origin, "items": items}


PATH_CACHE = {}


def paths(s, conf_or_best):
    """지도용 실제 경로선: 차 → (경유지) → 만남 지점, 아이 → 만남 지점"""
    c = conf_or_best
    via = s.get("via")
    key = (rnd(car_s(s)), s["rider"]["lat"], s["rider"]["lon"], c["id"], via and (via["lat"], via["lon"]))
    hit = PATH_CACHE.get(s["id"])
    if hit and hit[0] == key:
        return hit[1]
    car = point_at(car_s(s))
    if via:
        vp = xy(via["lat"], via["lon"])
        c1, l1, p1 = route(car, vp, True)
        c2, l2, p2 = route(vp, c["xy"], True, sn1=c["dsnap"])
        car_path, car_m, car_sec = p1 + p2[1:], l1 + l2, c1 + c2
    else:
        car_sec, car_m, car_path = route(car, c["xy"], True, sn1=c["dsnap"])
    out = {"carPath": car_path, "carPathM": rnd(car_m), "carPathSec": rnd(car_sec)}
    if s["rider"]["lat"] is not None:
        rp = xy(s["rider"]["lat"], s["rider"]["lon"])
        _, wm, wp = route(rp, c["poi"], False, sn1=c["wsnap"])
        straight = dist(rp, c["poi"])
        if wm > straight * WALK_PATH_MAX:
            wp, wm = [latlon(*rp), latlon(*c["poi"])], straight * WALK_DETOUR
        out.update({"walkPath": wp, "walkPathM": rnd(wm)})
    PATH_CACHE[s["id"]] = (key, out)
    return out


def log_event(s, text):
    s["events"].append({"t": time.time(), "text": text})
    del s["events"][:-30]
    print(f"[{s['id']}] {text}", flush=True)


def refresh(s):
    if s["status"] in ("boarded", "dropped"):
        return
    if s["confirmed"] is None:
        prop = evaluate(s)
        old = s["proposal"]["best"]["id"] if s["proposal"] and s["proposal"].get("best") else None
        s["proposal"] = prop
        new = prop["best"]["id"] if prop and prop.get("best") else None
        if new and new != old:
            s["proposalVersion"] += 1
            if s["status"] == "waiting_rider":
                s["status"] = "proposed"
            log_event(s, L(f"제안: {prop['best']['name']} (대안: {prop['alt']['name'] if prop['alt'] else '-'}, 제외 {len(prop['excluded'])}곳)",
                           f"Suggested: {prop['best']['name']} (alt: {prop['alt']['name'] if prop['alt'] else '-'}, {len(prop['excluded'])} ruled out)"))
        return
    c = cand_by_id(s["confirmed"])
    if c is None or s["rider"]["lat"] is None:
        return
    ct, _, how = car_time(s, c)
    rp = xy(s["rider"]["lat"], s["rider"]["lon"])
    walk = walk_m_to(rp, c) / WALK_SPEED[s["member"]["type"]]
    if s["status"] == "arrived":
        ct = 0
    s["eta"] = {"carSec": rnd(ct), "walkSec": rnd(walk), "route": how}
    # 집중 단속 시간: 아이·성인은 1분 먼저 나와 차가 서 있는 시간을 줄이고, 어르신은 밖에서 기다리지 않게 차가 먼저 도착
    peak = bool(PEAK) and in_hours(clock(s)[0], [PEAK])
    margin = (60 if s["member"]["type"] != "senior" else 0) if peak else 30
    s["eta"]["margin"] = margin
    if not s["leaveNow"] and ct <= walk + margin:
        s["leaveNow"] = True
        log_event(s, L(f"{s['member']['name']}에게 '지금 나오세요' 알림", f"Told {s['member']['name']} to head out now"))
    if s["status"] == "confirmed" and abs(car_s(s) - c["s"]) < ARRIVE_M and s["car"]["speed"] < ARRIVE_MS:
        s["status"] = "arrived"
        log_event(s, L(f"차가 {c['name']} 앞에 도착", f"Car arrived at {c['name']}"))


SC = PROFILE["scenario"]
RIDER_S, RIDER_OFF = float(SC["riderS"]), float(SC["riderOff"])   # 대치동: 은마아파트입구사거리 북측(학원가) — 대상별로 만남 지점이 갈리는 위치
CAR_BACK = float(SC["carBack"])     # 대치동: 차는 도곡로를 따라 500m 서쪽에서 출발


def preset_spots():
    """시연 장소: 학원가(대상별 차이), 대치역(시간대별 버스전용차로 차이)."""
    out = {"academy": scenario()["riderDefault"]}
    for pr in PROFILE.get("presets", []):
        st = next((c for c in CANDS if c["name"] == pr["cand"]), None)
        if st:
            out[pr["key"]] = {"lat": st["lat"] + pr["dlat"], "lon": st["lon"] + pr["dlon"], "name": pr["name"]}
    return out


def scenario():
    """시연 시나리오: 도곡로 북측(진행 방향 왼쪽) 은마아파트입구사거리 학원가에서 기다린다."""
    p, q = point_at(RIDER_S), point_at(RIDER_S + 5)
    dx, dy = q[0] - p[0], q[1] - p[1]
    n = math.hypot(dx, dy) or 1
    lat, lon = latlon(p[0] - dy / n * RIDER_OFF, p[1] + dx / n * RIDER_OFF)
    return {"riderDefault": {"lat": lat, "lon": lon, "name": SC["riderName"]},
            "carStartS": max(0.0, RIDER_S - CAR_BACK)}


def public(s):
    cs = car_s(s)
    lat, lon = latlon(*point_at(cs))
    a = point_at(cs + 5)
    b = point_at(cs - 5)
    heading = math.degrees(math.atan2(a[0] - b[0], a[1] - b[1]))
    conf = cand_by_id(s["confirmed"]) if s["confirmed"] else None
    out = {k: v for k, v in s.items()}
    out["carPos"] = {"lat": lat, "lon": lon, "s": cs, "heading": heading}
    out["maxProgress"] = ROUTE_LEN - s["scenario"]["carStartS"]
    cp = point_at(cs)
    near = min(CANDS, key=lambda c: dist(cp, c["xy"]))
    out["carPlace"] = (L(f"{MAP['routeName']} {near['name']} 근처", f"{MAP['routeName']} near {near['name']}") if dist(cp, near["xy"]) < PLACE_NEAR_M
                       else L(f"{MAP['routeName']} (대치동)", f"{MAP['routeName']}, {PROFILE['area']}"))
    out["region"] = {"id": REGION, "lang": LANG, "country": PROFILE["country"], "label": PROFILE["label"]}
    out["target"] = ({"id": conf["id"], "name": conf["name"], "curb": conf["curb"], "x": conf["s"] - s["scenario"]["carStartS"]}
                     if conf else None)
    if s.get("via"):                               # 경유지(매장)의 경로상 위치 — 차량 앱이 매장 도착을 판단
        out["viaX"] = r1(project(xy(s["via"]["lat"], s["via"]["lon"]))[0] - s["scenario"]["carStartS"])
    best = (s.get("proposal") or {}).get("best")
    goal = conf or (cand_by_id(best["id"]) if best else None)
    if goal and s["status"] not in ("boarded", "dropped"):
        out.update(paths(s, goal))
    return out


VC_ORIGINS = {"http://127.0.0.1:8088", "http://localhost:8088"}


class Handler(BaseHTTPRequestHandler):
    timeout = 10   # 초: 아무것도 보내지 않는 연결이 스레드를 붙들지 못하게(pt_relay SOCKET_TIMEOUT 과 같다)

    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # 시연: Virtual Console 페이지(에뮬레이터 8088)에서 진행 상황을 조회한다 — 그 출처에만 CORS 를 연다(다른 사이트가 세션을 읽지 못하게)
        origin = self.headers.get("Origin")
        if origin in VC_ORIGINS:
            self.send_header("Access-Control-Allow-Origin", origin)
        self.send_header("Vary", "Origin")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, obj if isinstance(obj, bytes) else json.dumps(obj, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def do_GET(self):
        # 조회 중 예외(잘못 저장된 값 등)에도 연결을 그냥 끊지 않고 500 을 돌려준다(앱 LocalServer 와 같다). 내용은 서버 기록에만
        try:
            self._get()
        except Exception:  # noqa: BLE001
            traceback.print_exc()
            self._json(500, {"error": "server error"})

    def _get(self):
        path = urlparse(self.path).path
        parts = [p for p in path.split("/") if p]
        if len(parts) == 2 and parts[0] in ("m", "car", "f"):
            return self._send(200, (HERE / "rider.html").read_bytes(), "text/html; charset=utf-8")
        if path == "/api/presets":
            return self._json(200, preset_spots())
        if path == "/api/map":
            layers = {k: MAP.get(k, []) for k in ("route", "busStops", "crossings", "signals", "hydrants", "schools", "stops", "sources", "center", "routeName")}
            layers["rules"] = PROFILE["rules"]
            layers["candidates"] = [{k: c[k] for k in ("id", "name", "icon", "curb", "blocked", "kind")} for c in CANDS]
            layers["busLanes"] = [{"name": r["name"], "coords": r["coords"], "label": BUS_LANES[r["name"]][0], "spans": BUS_LANES[r["name"]][1]}
                                  for r in MAP["roads"] if r["name"] in BUS_LANES]
            layers["cctv"] = CCTV_RAW
            layers["roads"] = [{"name": r.get("name", ""), "highway": r.get("highway", ""), "coords": r["coords"]} for r in MAP["roads"]]   # 오프라인 바탕 지도용
            layers["peak"] = list(PEAK) if PEAK else None
            layers["region"] = {"id": REGION, "lang": LANG, "country": PROFILE["country"], "label": PROFILE["label"]}
            return self._json(200, layers)
        if path == "/api/latest":
            with lock:
                if not sessions:
                    return self._json(404, {"error": "none"})
                return self._json(200, {"id": max(sessions.values(), key=lambda x: x["created"])["id"]})
        if len(parts) == 4 and parts[:2] == ["api", "session"] and parts[3] == "nearby":
            q = parse_qs(urlparse(self.path).query)
            kind, origin = (q.get("kind") or ["parking"])[0], (q.get("from") or [None])[0]
            if kind not in NEARBY_KINDS:
                return self._json(400, {"error": "unknown kind"})
            with lock:
                s = sessions.get(parts[2])
                if s:
                    touch(parts[2])
                out = json.dumps(nearby(s, kind, origin), ensure_ascii=False).encode() if s else None
            return self._json(200, out) if s else self._json(404, {"error": "no session"})
        if len(parts) == 3 and parts[:2] == ["api", "session"]:
            with lock:
                s = sessions.get(parts[2])
                if s:
                    touch(parts[2])
                    refresh(s)
                    out = json.dumps(public(s), ensure_ascii=False).encode()
            return self._json(200, out) if s else self._json(404, {"error": "no session"})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        parts = [p for p in urlparse(self.path).path.split("/") if p]
        # 본문: chunked·숫자 아닌 길이·음수·상한 초과는 읽지 않고 400(앱 LocalServer 와 같다 — 전에는 상한 초과를 빈 본문으로 처리했다)
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            return self._json(400, {"error": "chunked not supported"})
        try:
            n = int(self.headers.get("Content-Length", 0) or 0)
        except ValueError:
            return self._json(400, {"error": "bad length"})
        if n < 0 or n > MAX_BODY:
            return self._json(400, {"error": "body too large" if n > MAX_BODY else "bad length"})
        try:
            body = json.loads(self.rfile.read(n) or b"{}") if n > 0 else {}
        except ValueError:
            return self._json(400, {"error": "bad json"})
        if not isinstance(body, dict):
            return self._json(400, {"error": "bad json"})
        # 예외 내용은 서버 기록에만 남기고, 응답에는 일반 문구만 보낸다(내부 키·경로가 새지 않게)
        try:
            code, out = self._post(parts, body)
        except (KeyError, TypeError, ValueError, AttributeError):
            traceback.print_exc()
            code, out = 400, {"error": "bad request"}
        except Exception:  # noqa: BLE001
            traceback.print_exc()
            code, out = 500, {"error": "server error"}
        self._json(code, out)

    def _post(self, parts, body):
        with lock:
            if parts == ["api", "session"]:
                # 요청 본문은 믿지 않는다(앱 createSession 과 같은 정리): member 는 이름(20자)·대상 종류만, clock 은 hhmm·weekday 만, carDesc 는 40자
                raw_m = body["member"] if isinstance(body.get("member"), dict) else PROFILE["member"]
                member = {"name": opt_string(raw_m, "name")[:20] or PROFILE["member"]["name"],
                          "type": t if (t := opt_string(raw_m, "type")) in WALK_SPEED else PROFILE["member"]["type"]}
                raw_c = body["clock"] if isinstance(body.get("clock"), dict) else None
                hhmm = opt_string(raw_c, "hhmm") if raw_c is not None else ""
                clk = {"hhmm": hhmm if valid_clock(hhmm) else DEFAULT_HHMM, "weekday": opt_bool(raw_c, "weekday") if raw_c is not None else True}
                sid = secrets.token_hex(8)   # 세션 id = 링크의 열쇠: 64비트 난수 16자리 16진수(앱 SecureRandom 과 같은 길이·엔트로피)
                s = {"id": sid, "created": time.time(), "member": member, "scenario": scenario(), "clock": clk,
                     "car": {"progress": 0.0, "speed": 0.0, "gear": "P", "wiper": "OFF", "desc": opt_string(body, "carDesc", PROFILE["carDesc"])[:40]},
                     "rider": {"lat": None, "lon": None, "name": member["name"]},
                     "proposal": None, "proposalVersion": 0, "confirmed": None, "confirmedBy": None,
                     "leaveNow": False, "eta": None, "status": "waiting_rider", "boarding": None, "events": [], "messages": [], "emergency": None,
                     "needToilet": False, "via": None, "passed": [], "replan": None}
                while len(sessions) >= MAX_SESSIONS:
                    evict_one()
                sessions[sid] = s
                touch(sid)
                log_event(s, L(f"픽업 시작: {member['name']}({member['type']}) · 대기 위치 {s['scenario']['riderDefault']['name']}",
                               f"Pickup started: {member['name']} ({member['type']}) · waiting at {s['scenario']['riderDefault']['name']}"))
                return 200, {"id": sid, "link": f"/m/{sid}"}
            if len(parts) != 4 or parts[:2] != ["api", "session"] or parts[2] not in sessions:
                return 404, {"error": "not found"}
            s, action = sessions[parts[2]], parts[3]
            touch(parts[2])
            if action == "car":
                # 진행 보고: 숫자 칸은 유한한 숫자, 글 칸은 글만 — 하나라도 어긋나면 아무것도 바꾸지 않고 400(앱과 같다)
                if any(k in body and finite_num(body[k]) is None for k in ("progress", "speed")) or \
                        any(k in body and not isinstance(body[k], str) for k in ("gear", "wiper")):
                    raise ValueError("car")
                s["car"].update({k: body[k] for k in ("progress", "speed", "gear", "wiper") if k in body})
            elif action == "rider":
                first = s["rider"]["lat"] is None
                lat, lon = finite_num(body.get("lat")), finite_num(body.get("lon"))   # 유한한 숫자·범위 안만(NaN·글은 400, 앱과 같다)
                if lat is None or lon is None or not -90 <= lat <= 90 or not -180 <= lon <= 180:
                    return 400, {"error": "lat/lon"}
                s["rider"].update({"lat": lat, "lon": lon})
                if first:
                    log_event(s, L(f"{s['member']['name']} 위치 공유 시작", f"{s['member']['name']} shared location"))
            elif action in ("choose", "confirm"):
                cid = body.get("pointId") or (s["proposal"]["best"]["id"] if s["proposal"] and s["proposal"].get("best") else None)
                if cid and cand_by_id(cid) and s["rider"]["lat"] is not None:
                    by = body.get("by", "rider") if action == "choose" else "driver"
                    s["confirmed"], s["confirmedBy"], s["status"] = cid, by, "confirmed"
                    log_event(s, L(f"{'운전자' if by == 'driver' else s['member']['name']} 확정: {cand_by_id(cid)['name']}",
                                   f"{'Driver' if by == 'driver' else s['member']['name']} confirmed: {cand_by_id(cid)['name']}"))
            elif action == "clock":
                # 잘못된 시각을 저장하면 그 세션의 모든 평가가 실패한다 — 검사하고 어긋나면 세션을 바꾸지 않고 400(앱과 같다)
                hhmm, weekday = body.get("hhmm", DEFAULT_HHMM), body.get("weekday", True)
                if not valid_clock(hhmm) or not isinstance(weekday, bool):
                    raise ValueError("bad clock")
                s["clock"] = {"hhmm": hhmm, "weekday": weekday}
                s["proposal"] = None                       # 시간 규칙이 바뀌었으니 다시 제안
                log_event(s, L(f"시연 시각 {s['clock']['hhmm']} ({'평일' if s['clock']['weekday'] else '주말'})",
                               f"Demo clock {s['clock']['hhmm']} ({'weekday' if s['clock']['weekday'] else 'weekend'})"))
            elif action == "emergency":
                # 차 안 응급 상황: 기다리는 사람 링크를 '픽업이 늦어져요'로 바꾼다 (상세 의료 정보는 보내지 않음)
                on = bool(body.get("on"))
                s["emergency"] = {"on": on, "t": time.time(), "card": body.get("card")} if on else None
                log_event(s, (L("응급 상황 카드 시작 — 픽업 지연 안내", "Emergency card started — pickup delay notice sent") if on
                              else L("응급 상황 종료", "Emergency ended")))
            elif action == "message":
                # 기다리는 사람의 원탭 메시지 → 차에서 음성으로 읽어 준다 (운전자는 답하지 않아도 됨)
                text = clean_message(body.get("text"))
                if text:
                    s["messages"].append({"t": time.time(), "from": body.get("from", "rider"), "text": text})
                    mm = RIDER_DELAY.search(text)
                    if mm:                                 # "2분만 기다려 주세요" → 아이가 2분 늦게 나온다(1~60분만 받는다 — 앱과 같다)
                        d = mm.group(1) or mm.group(2)
                        n = int(d) if len(d) <= 3 else 0
                        if 1 <= n <= 60:
                            s["riderDelay"] = max(s.get("riderDelay", 0), n * 60)
                    del s["messages"][:-10]
                    log_event(s, f"{s['member']['name']}: {text}")
            elif action == "via":
                if body.get("lat") is not None:
                    # 경유지 위치도 유한한 숫자·범위 안만(앱과 같다). lat 이 없거나 null 이면 경유지 해제
                    lat, lon = finite_num(body.get("lat")), finite_num(body.get("lon"))
                    if lat is None or lon is None or not -90 <= lat <= 90 or not -180 <= lon <= 180:
                        raise ValueError("via lat/lon")
                    s["via"] = {"lat": lat, "lon": lon, "name": opt_string(body, "name")[:40]}
                    log_event(s, L(f"경유지: {s['via']['name']}", f"Stop on the way: {s['via']['name']}"))
                else:
                    s["via"] = None
            elif action == "toilet":
                if s["status"] not in ("boarded", "dropped") and not s.get("needToilet"):
                    s["needToilet"] = True
                    s["messages"].append({"t": time.time(), "from": "rider", "text": L("화장실이 급해요", "I need a restroom")})
                    del s["messages"][:-10]
                    s["confirmed"], s["confirmedBy"], s["leaveNow"] = None, None, False
                    if s["status"] in ("confirmed", "arrived"):
                        s["status"] = "proposed"
                    s["proposal"] = None
                    log_event(s, L(f"{s['member']['name']}: 화장실이 급해요 — 화장실 가까운 곳으로 다시 정함",
                                   f"{s['member']['name']} needs a restroom — re-planning near one"))
            elif action == "passed":
                # 차가 확정 지점에서 서지 못하고 지나감 → 그 지점을 빼고 지금 차 위치에서 다시 골라 바로 확정(운전 중이라 묻지 않음)
                prev = cand_by_id(s["confirmed"]) if s["confirmed"] else None
                if prev and s["status"] == "confirmed":
                    s["passed"].append(prev["id"])
                    s["confirmed"], s["confirmedBy"], s["leaveNow"], s["status"], s["proposal"] = None, None, False, "proposed", None
                    log_event(s, L(f"{prev['name']}에 서지 못하고 지나감 — 다시 정함", f"Couldn't stop at {prev['name']} — re-planning"))
                    refresh(s)
                    nb = (s.get("proposal") or {}).get("best")
                    if nb:
                        nxt = cand_by_id(nb["id"])
                        s["confirmed"], s["confirmedBy"], s["status"] = nb["id"], "auto", "confirmed"
                        s["replan"] = {"t": time.time(), "why": "passed", "from": prev["name"], "to": nxt["name"] if nxt else "", "ack": False}
                        log_event(s, L(f"자동 확정: {nxt['name']} (지나친 {prev['name']} 대신)", f"Auto-confirmed: {nxt['name']} (instead of {prev['name']})"))
            elif action == "ack":
                # 기다리는 사람: 차가 다시 정한 만남 지점을 봤다고 확인
                rp = s.get("replan")
                if rp and not rp.get("ack"):
                    rp["ack"], rp["ackT"] = True, time.time()
                    log_event(s, L(f"{s['member']['name']}: 새 만남 지점 확인 ({rp.get('to')})", f"{s['member']['name']} saw the new spot ({rp.get('to')})"))
            elif action == "aboard":
                # 기다리는 사람: "제가 탔어요" — 좌석·문은 누가 탔는지 모르므로 탑승 감지와 본인 확인을 나눈다
                b = s.get("boarding")
                if s["status"] == "boarded" and b and not b.get("riderConfirmed"):
                    b["riderConfirmed"], b["riderT"] = True, time.time()
                    log_event(s, L(f"{s['member']['name']}: 탔어요 (본인 확인)", f"{s['member']['name']}: I'm in (confirmed)"))
            elif action == "boarding":
                # 탑승·하차 보고: 상태는 boarded·dropped 만, 저장은 좌석·표시·벨트만(본문을 통째로 대기자 화면에 내보내지 않는다 — 앱과 같다)
                state = body.get("state")
                if state not in ("boarded", "dropped"):
                    raise ValueError("boarding state")
                b = {"state": state}
                if "seat" in body:
                    b["seat"] = opt_string(body, "seat")[:40]
                if "seatLabel" in body:
                    b["seatLabel"] = opt_string(body, "seatLabel")[:40]
                if "belt" in body:
                    b["belt"] = body["belt"] if isinstance(body["belt"], bool) else None
                b["riderConfirmed"] = False
                s["boarding"], s["status"] = b, state
                boarded = state == "boarded"
                label = b.get("seatLabel", "")
                log_event(s, L(f"{label} {'탑승' if boarded else '하차'} 감지", f"{label}: {'someone got in' if boarded else 'someone got out'}"))
            else:
                return 404, {"error": "unknown action"}   # 모르는 동작은 세션을 건드리지 않고 404(앱 LocalServer 와 같다 — 전에는 200 + 상태)
            refresh(s)
            return 200, json.dumps(public(s), ensure_ascii=False).encode()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Tago 시연 서버({REGION}) :{PORT} · 경로 {MAP['routeName']} {ROUTE_LEN:.0f}m · 후보 {len(CANDS)}곳 "
          f"(정차 금지 {sum(1 for c in CANDS if c['blocked'])}곳) · 소화전 {len(MAP['hydrants'])}개", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
