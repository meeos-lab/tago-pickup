"""링크 스키마 점검(gilbeot-road/1).

1. 모든 *.schema.json 이 JSON Schema 2020-12 로 올바른지(메타 스키마 검사).
2. 요청 스키마(*.request·*.query)가 허브 검사기(link_server.schema_error = LinkHub.schemaError)가 아는 키워드만 쓰는지 —
   모르는 키워드는 허브가 조용히 무시하므로 여기서 막는다.
3. 허브 검사기와 jsonschema 라이브러리가 같은 판정을 내리는지(요청 표본, 받는 것·거절하는 것).
4. (주소를 주면) check_link_parity.py 의 세 대본을 그 서버에 돌려, 오간 모든 응답을 응답 스키마로 검사한다.

사용: python -X utf8 server/link/schema/check_schemas.py [http://127.0.0.1:8788 [ko|en]]
필요: pip install jsonschema(4.18 이상, referencing 포함). 없으면 종료 코드 2.
종료 코드: 0 = 모두 통과, 1 = 문제 있음, 2 = jsonschema 없음.
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent))

try:
    import jsonschema
    from referencing import Registry, Resource
except ImportError:
    print("jsonschema 가 없다 — pip install jsonschema")
    sys.exit(2)

import link_server  # noqa: E402  같은 폴더의 허브(요청 검사기·REQ_SCHEMA)

HUB_KEYWORDS = {"$schema", "$id", "title", "description", "type", "required", "properties", "items", "minimum", "maximum",
                "minLength", "maxLength", "pattern", "minItems", "maxItems"}
RESPONSE = {  # (메서드, 경로 모양) → 200 응답 스키마
    "reset": "road-reset.response", "join": "road-join.response", "view": "road-view.response",
    "world": "road-world.response", "route": "road-route.response"}


def load():
    docs = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in sorted(HERE.glob("*.schema.json"))}
    reg = Registry().with_resources([(d["$id"], Resource.from_contents(d)) for d in docs.values()])
    return docs, reg


def keywords(s, path, bad):
    for k, v in s.items():
        if k not in HUB_KEYWORDS:
            bad.append(f"{path}: {k}")
        if k == "properties":
            for pk, pv in v.items():
                keywords(pv, f"{path}.{pk}", bad)
        elif k == "items":
            keywords(v, f"{path}[]", bad)


SAMPLES = [  # (요청 스키마, 본문) — 허브와 라이브러리가 같은 판정이어야 한다
    ("road-state.request", {"me": "c1", "t": 1.0, "s": 1.0, "speed": 25, "wiper": "OFF", "temp": 3.5}),
    ("road-state.request", {"me": "c1", "lat": 37.2, "lon": 127.1, "speed": None}),
    ("road-state.request", {"t": 1}),
    ("road-state.request", {"me": "c1", "speed": True}),
    ("road-state.request", {"me": "c1", "lat": 91}),
    ("road-state.request", {"me": "c1", "dest_s": None, "on": True}),
    ("road-event.request", {"me": "c1", "type": "IMPACT", "value": "x", "samples": [[0.0, 1.0]] * 20}),
    ("road-event.request", {"me": "c1", "type": "IMPACT", "value": "x", "samples": [[0.0, 1.0]] * 21}),
    ("road-event.request", {"me": "c1", "type": "IMPACT", "samples": [[0.1]]}),
    ("road-event.request", {"me": "c1", "type": "", "value": 3}),
    ("road-event.request", {"me": "c1", "type": "X", "value": None}),
    ("road-official.request", {"me": "c1", "events": [{"type": "ACCIDENT", "lat": 37.0, "lon": 127.0, "start": None, "end": 1.0}]}),
    ("road-official.request", {"me": "c1", "events": [{"lat": 37.0}]}),
    ("road-official.request", {"me": "c1", "events": {}}),
    ("road-join.request", {"kind": "ev", "token": "x", "name": "\U0001F697" * 200}),
    ("road-join.request", {"name": "\U0001F697" * 201}),
    ("road-respond.request", {"me": "c1", "ok": None, "timeout": True}),
    ("road-respond.request", {"me": "c1", "ok": 1}),
    ("road-demo.request", {"me": "c1", "ems": "all"}),
    ("road-reset.request", {}),
    ("road-reset.request", []),
    ("road-view.query", {"me": "c1", "t": "1762300000.5"}),
    ("road-view.query", {"t": ""}),
    ("road-view.query", {"t": "1e5"}),
    ("road-view.query", {"t": "-1"}),
]


def main(argv):
    docs, reg = load()
    bad = []
    for name, d in docs.items():
        try:
            jsonschema.Draft202012Validator.check_schema(d)
        except jsonschema.SchemaError as e:
            bad.append(f"{name}: 2020-12 아님 — {e.message}")
        if name.endswith((".request.schema.json", ".query.schema.json")):
            kw = []
            keywords(d, name, kw)
            bad += [f"허브가 모르는 키워드 {k}" for k in kw]
    used = {f"{n}.schema.json" for n in link_server.REQ_SCHEMA.values()}
    bad += [f"REQ_SCHEMA 가 가리키는 파일 없음: {n}" for n in used - set(docs)]
    n_agree = 0
    for sch, body in SAMPLES:
        lib = jsonschema.Draft202012Validator(docs[f"{sch}.schema.json"], registry=reg).is_valid(body)
        hub = link_server.schema_error(link_server.SCHEMAS[sch], body) is None
        if lib != hub:
            bad.append(f"판정 다름 {sch} {json.dumps(body, ensure_ascii=False)[:80]}: 라이브러리 {lib} · 허브 {hub}")
        else:
            n_agree += 1
    print(f"스키마 {len(docs)}개 · 요청 표본 {n_agree}/{len(SAMPLES)} 판정 같음")
    if len(argv) > 1:
        bad += live(argv[1], argv[2] if len(argv) > 2 else "ko", docs, reg)
    for b in bad:
        print("  ✗", b)
    print("통과" if not bad else f"문제 {len(bad)}건")
    return 1 if bad else 0


def live(base, lang, docs, reg):
    """대조 대본을 한 서버에 돌리며 응답마다 스키마 검사(200 은 경로별 응답 스키마, 허브 오류는 오류 스키마)"""
    import check_link_parity as P
    seen, bad = [], []
    orig = P.call

    def call(b, method, path, body=None):
        r = orig(b, method, path, body)
        seen.append((method, path, r))
        return r
    P.call = call
    try:
        P.run(base, lang)
        P.run_ev(base, lang)
        P.run_schema(base, lang)
    finally:
        P.call = orig
    val = {k: jsonschema.Draft202012Validator(docs[f"{v}.schema.json"], registry=reg) for k, v in RESPONSE.items()}
    err = jsonschema.Draft202012Validator(docs["road-error.response.schema.json"], registry=reg)
    counts = {}
    for method, path, r in seen:
        p = path.split("?")[0]
        if "status" in r and "api" in r:                  # 허브 오류는 경로와 관계없이(그룹 /api/link 포함) RFC 9457 오류 스키마
            v, key = err, "error"
        elif "status" in r or not p.startswith("/api/road"):
            continue                                      # 전송 층 오류(api 없음)·그룹 200 응답은 범위 밖
        else:
            act = p[len("/api/road"):].strip("/")
            key = {"reset": "reset", "join": "join", "world": "world", "route": "route"}.get(act, "view" if act in ("", "state", "event", "respond", "official", "demo") else None)
            if key is None:
                continue                                  # chargerstatus 등 1~10 밖
            v = val[key]
        counts[key] = counts.get(key, 0) + 1
        for e in v.iter_errors(r):
            bad.append(f"{method} {path[:60]} → {key}: {'/'.join(map(str, e.absolute_path))} {e.message[:120]}")
            break
    print(f"응답 {sum(counts.values())}건 스키마 검사: " + ", ".join(f"{k} {n}" for k, n in sorted(counts.items())))
    return bad


if __name__ == "__main__":
    sys.exit(main(sys.argv))
