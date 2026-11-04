"""PC 시연 서버(meeos_server.py)와 앱 내장 엔진(MeeosEngine.kt)의 결과가 같은지 대조한다.

준비: PC 서버 실행(:8790), 에뮬레이터에서 타고 앱 실행 후 `adb forward tcp:8791 tcp:8790`
사용: python check_parity.py [PC주소] [앱주소] [지역]
 해외 지역은 PC 서버를 지역별로 띄운다: MEEOS_REGION=irvine MEEOS_PORT=8792 python meeos_server.py
 예) python check_parity.py http://127.0.0.1:8792 http://127.0.0.1:8791 irvine
"""

import json
import sys
import urllib.request

CASES = [  # (대상, 이름, 와이퍼, 시각, 평일, 장소)
    ("child", "지수", "OFF", "22:00", True, "academy"),
    ("adult", "엄마", "OFF", "22:00", True, "academy"),
    ("senior", "할머니", "OFF", "22:00", True, "academy"),
    ("child", "지수", "CONTINUOUS_LEVEL_3", "22:00", True, "academy"),
    ("adult", "엄마", "OFF", "08:00", True, "academy"),
    ("adult", "엄마", "OFF", "18:00", True, "station"),
    ("adult", "엄마", "OFF", "22:00", True, "station"),
    ("child", "지수", "OFF", "18:00", False, "station"),
    ("senior", "할머니", "OFF", "14:00", True, "station"),
    ("adult", "엄마", "OFF", "14:00", False, "academy"),   # 주말: 토·공휴일 운영 CCTV만
    ("child", "지수", "OFF", "20:30", True, "academy"),    # 평일 저녁: 이면도로 CCTV 운영
    ("adult", "엄마", "OFF", "23:30", True, "station"),    # 심야: 심야 운영 CCTV만
]


def call(base, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())


GLOBAL_CASES = [  # 해외 지역: 대상 3종 × 비 (시간대 규칙 없음)
    ("child", "Emma", "OFF", "15:00", True, "academy"),
    ("adult", "Mom", "OFF", "15:00", True, "academy"),
    ("senior", "Grandma", "OFF", "15:00", True, "academy"),
    ("child", "Emma", "CONTINUOUS_LEVEL_3", "15:00", True, "academy"),
    ("adult", "Mom", "CONTINUOUS_LEVEL_3", "08:00", False, "academy"),
]


def passed_case(base, kind, name, wiper, hhmm, weekday, spot, region):
    sid = call(base, "/api/session", {"member": {"name": name, "type": kind}, "clock": {"hhmm": hhmm, "weekday": weekday}, "region": region})["id"]
    call(base, f"/api/session/{sid}/car", {"wiper": wiper})
    call(base, f"/api/session/{sid}/rider", {"lat": spot["lat"], "lon": spot["lon"]})
    st = call(base, f"/api/session/{sid}/confirm", {})
    x = st["target"]["x"]
    call(base, f"/api/session/{sid}/car", {"progress": max(0.0, x + 40), "speed": 10.0, "gear": "D"})
    st = call(base, f"/api/session/{sid}/passed", {})
    t = st.get("target") or {}
    ack0 = (st.get("replan") or {}).get("ack")
    ack1 = (call(base, f"/api/session/{sid}/ack", {}).get("replan") or {}).get("ack")
    # 탑승: 좌석·문은 '감지', 본인이 [제가 탔어요]를 눌러야 확인
    b0 = call(base, f"/api/session/{sid}/boarding", {"state": "boarded", "seat": "ROW_2_RIGHT", "seatLabel": "2열 오른쪽", "belt": False})
    b1 = call(base, f"/api/session/{sid}/aboard", {})
    return (t.get("name"), st.get("confirmedBy"), st.get("status"), (st.get("replan") or {}).get("from"), (st.get("eta") or {}).get("carSec"),
            ack0, ack1, (b0.get("boarding") or {}).get("riderConfirmed"), (b1.get("boarding") or {}).get("riderConfirmed"), b1["events"][-1]["text"], b0["events"][-1]["text"],
            sorted(e["name"] for e in ((st.get("proposal") or {}).get("excluded") or []) if "서지 못하고" in e["reason"] or "couldn't stop" in e["reason"]))


def run(base, region="daechi"):
    pre = call(base, f"/api/presets?region={region}")
    out = []
    for kind, name, wiper, hhmm, weekday, spot in (CASES if region == "daechi" else GLOBAL_CASES):
        sid = call(base, "/api/session", {"member": {"name": name, "type": kind}, "clock": {"hhmm": hhmm, "weekday": weekday}, "region": region})["id"]
        call(base, f"/api/session/{sid}/car", {"wiper": wiper})
        p = call(base, f"/api/session/{sid}/rider", {"lat": pre[spot]["lat"], "lon": pre[spot]["lon"]})["proposal"]
        out.append({
            "best": p["best"] and (p["best"]["name"], p["best"]["carSec"], p["best"]["walkSec"], p["best"]["cost"], p["best"]["stopLimit"], p["best"]["stopWhy"]),
            "alt": p["alt"] and p["alt"]["name"],
            "reason": p["reason"], "speech": p.get("speech"),
            "excluded": sorted((e["name"], e["reason"], e.get("fine")) for e in p["excluded"]),
            # 근처 주차장: 제안 기준 → 확정 기준
            "parking": [(i["name"], i["dist"]) for i in call(base, f"/api/session/{sid}/nearby?kind=parking")["items"]],
            # 실제 길 경로선(제안 기준) 길이·점 수
            "paths": (lambda st: (st.get("carPathM"), st.get("walkPathM"), len(st.get("carPath") or []), len(st.get("walkPath") or [])))(call(base, f"/api/session/{sid}")),
            "parkingAfterConfirm": [(i["name"], i["dist"]) for i in (call(base, f"/api/session/{sid}/confirm", {}) and call(base, f"/api/session/{sid}/nearby?kind=parking"))["items"]] if p["best"] else [],
            "safety": p["best"] and p["best"].get("safety"),
            "toilets": [(i["name"], i["dist"]) for i in call(base, f"/api/session/{sid}/nearby?kind=toilet&from=rider")["items"]],
            "driveThru": [(i["name"], i["dist"], i["ahead"]) for i in call(base, f"/api/session/{sid}/nearby?kind=drivethru")["items"]],
            "takeout": [(i["name"], i["extraSec"], i["lateSec"], i["waitSec"]) for i in call(base, f"/api/session/{sid}/nearby?kind=takeout")["items"]],
            "viaPath": (lambda st: (st.get("carPathM"), st.get("carPathSec")))(call(base, f"/api/session/{sid}/via", {"lat": p["best"]["curb"][0] + 0.0005, "lon": p["best"]["curb"][1], "name": "test"})) if p["best"] else None,
            "takeoutAfterDelay": [(i["name"], i["lateSec"], i["slackSec"]) for i in (call(base, f"/api/session/{sid}/message", {"text": "2분만 기다려 주세요" if region == "daechi" else "Give me 2 minutes"})
                                  and call(base, f"/api/session/{sid}/nearby?kind=takeout"))["items"]],
            # 화장실이 급해요 → 다시 제안
            "afterToilet": (lambda q: q["best"] and (q["best"]["name"], q["best"]["prefer"], q["reason"]))(
                call(base, f"/api/session/{sid}/toilet", {})["proposal"] or {"best": None}),
            # 확정 지점을 서지 못하고 지나침 → 그 지점을 빼고 다시 골라 자동 확정
            "afterPassed": passed_case(base, kind, name, wiper, hhmm, weekday, pre[spot], region) if p["best"] else None,
        })
    return out


def main(pc="http://127.0.0.1:8790", app="http://127.0.0.1:8791", region="daechi"):
    a, b = run(pc, region), run(app, region)
    bad = 0
    cases = CASES if region == "daechi" else GLOBAL_CASES
    for case, x, y in zip(cases, a, b):
        diff = [k for k in x if x[k] != y[k]]
        print(("OK  " if not diff else "DIFF"), case[0], case[2][:4], case[3], "평일" if case[4] else "주말", case[5], "|", x["best"][0] if x["best"] else "-", "" if not diff else diff)
        bad += bool(diff)
    print(f"{region}: {len(cases) - bad}/{len(cases)} 일치")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main(*sys.argv[1:4])
