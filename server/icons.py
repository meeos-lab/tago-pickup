"""앱 아이콘 — ORBIT 심볼 세트(server/icons/orbit, 사용자 제공 원본)를 한 곳에서 읽어
rider.html(인라인 SVG 글리프 + 지도 핀 색)과 안드로이드 VectorDrawable(res/drawable/ic_*.xml)을 만든다.

ORBIT: 24개 단색 실루엣. icons/ = 투명 배경 글리프(viewBox -1 -1 26 26), markers/ = 물방울 핀(48×48, 끝점 [24,42]),
selected/ = 선택 상태 테두리. 핀은 rider.html에서 글리프 + 물방울 + 분류색으로 그린다(파일 48개를 싣지 않음).
Pleos 디자인 가이드: 아이콘은 1~2단어 라벨과 함께, OS 경고등과 닮은 모양은 피한다.

실행: python server/icons.py
"""
import os
import re
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
ORBIT = os.path.join(HERE, "icons", "orbit")


def circle_path(cx, cy, r):
    """원 → path (VectorDrawable에는 circle이 없다)"""
    return f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0Z"


def load():
    """이름 → [(path d, evenodd 여부)], 이름 → 분류색"""
    icons, colors = {}, {}
    for f in sorted(os.listdir(os.path.join(ORBIT, "icons"))):
        if not f.endswith(".svg"):
            continue
        name = f[:-4]
        s = Path(ORBIT, "icons", f).read_text(encoding="utf-8")
        els = []
        for m in re.finditer(r"<(path|circle)\b([^>]*)/?>", s):
            tag, attrs = m.group(1), m.group(2)
            a = dict(re.findall(r'([\w-]+)=["\']([^"\']*)["\']', attrs))
            if tag == "circle":
                els.append((circle_path(float(a["cx"]), float(a["cy"]), float(a["r"])), False))
            else:
                els.append((a["d"], a.get("fill-rule") == "evenodd"))
        icons[name] = els
        mk = Path(ORBIT, "markers", f).read_text(encoding="utf-8")
        colors[name] = re.search(r"fill='(#[0-9A-Fa-f]{6})'", mk).group(1)
    return icons, colors


SVG_EVENODD = ' fill-rule="evenodd"'          # f-string 식 안에 백슬래시를 쓰지 않게(3.11 이하 문법 오류)
VD_EVENODD = ' android:fillType="evenOdd"'


def svg_inner(els):
    return "".join(f'<path d="{d}" fill="currentColor"{SVG_EVENODD if eo else ""}/>' for d, eo in els)


def vector_xml(els):
    paths = "\n".join(f'        <path android:pathData="{d}" android:fillColor="#FFFFFFFF"{VD_EVENODD if eo else ""} />'
                      for d, eo in els)
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- server/icons.py가 ORBIT 심볼(server/icons/orbit)에서 만든 파일. 직접 고치지 말 것 -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp" android:viewportWidth="26" android:viewportHeight="26">\n'
            '    <group android:translateX="1" android:translateY="1">\n' + paths + "\n    </group>\n</vector>\n")


if __name__ == "__main__":
    icons, colors = load()
    block = ("/* ICONS:BEGIN (server/icons.py가 ORBIT 심볼에서 씀) */\nconst ICONS = {\n"
             + "".join(f"  {n}: '{svg_inner(e)}',\n" for n, e in icons.items())
             + "};\nconst ICON_COLORS = {" + ", ".join(f"{n}: '{c}'" for n, c in colors.items()) + "};\n/* ICONS:END */")
    for page in (os.path.join(HERE, "rider.html"), os.path.join(HERE, "link", "link.html")):   # 휴대폰 웹 페이지 두 개
        s = Path(page).read_text(encoding="utf-8")
        s, n = re.subn(r"/\* ICONS:BEGIN.*?/\* ICONS:END \*/", lambda m: block, s, flags=re.S)
        assert n == 1, f"{page}에 ICONS 블록 표시가 없다"
        Path(page).write_text(s, encoding="utf-8")
    res = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")
    os.makedirs(res, exist_ok=True)
    for name, els in icons.items():
        Path(res, f"ic_{name}.xml").write_text(vector_xml(els), encoding="utf-8")
    print(f"ORBIT {len(icons)}개 → rider.html, link/link.html, res/drawable")
