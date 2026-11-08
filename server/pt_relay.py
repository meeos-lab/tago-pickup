"""PT 시연용 중계: 심사위원 휴대폰(같은 와이파이)이 차 앱의 기다리는 사람 화면을 직접 열게 한다.

차 앱의 내장 서버는 에뮬레이터 안 127.0.0.1에서만 열리고, `adb forward tcp:8791 tcp:<앱 내장 서버 포트>`(판마다 다르다: BuildConfig.LOCAL_PORT)로 PC 127.0.0.1:8791에 닿는다.
이 중계는 PC의 모든 네트워크 주소(0.0.0.0:8800)에서 받아 127.0.0.1:8791로 넘긴다. 외부 인터넷으로는 열지 않는다(공유기 안에서만).
같은 와이파이의 누구나 닿을 수 있으므로 휴대폰 화면(rider.html·link/link.html)이 실제로 부르는 경로만 넘기고 나머지는 404 다
(세션 만들기·차량 조작·후방 카메라·3D 관제 화면·/api/latest 같은 차 쪽 경로는 넘기지 않는다). 본문 64KB·소켓 10초·동시 처리 32개 상한.

  python server/pt_relay.py [포트] [앱 주소] [바인드 주소, 기본 0.0.0.0]
  - 발표 화면(PC 브라우저)에 http://127.0.0.1:8800/join 을 띄우면, 휴대폰으로 칠 짧은 주소(http://<PC IP>:8800/j)를 크게 보여 준다
  - 휴대폰에서 /j 를 열면 가장 최근 픽업 세션의 기다리는 사람 화면(/m/<세션>?demo=1)으로 간다
  - demo=1: 휴대폰 GPS 대신 시연 학원 위치로 공유한다(시연 지도는 대치동뿐. HTTP 주소라 휴대폰이 GPS도 막는다)
표준 라이브러리만 쓴다.
"""

import json
import re
import socket
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8800
APP = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8791"
BIND = sys.argv[3] if len(sys.argv) > 3 else "0.0.0.0"   # 시험할 때는 127.0.0.1 (휴대폰에는 안 열림)
MAX_BODY = 64 * 1024        # 휴대폰이 보내는 본문은 위치·버튼 정도(수백 바이트)
SOCKET_TIMEOUT = 10         # 초: 느리게 보내는 연결이 스레드를 붙들지 못하게
MAX_THREADS = 32            # 동시에 처리하는 요청 수 — 넘치면 바로 503
MAX_QUERY_VALUE = 64        # 넘기는 쿼리 값 길이
LATEST_TIMEOUT = 3          # 초: /j 가 앱에 가장 최근 세션을 묻는 시간
UPSTREAM_TIMEOUT = 10       # 초: 앱 내장 서버로 넘긴 요청을 기다리는 시간

# 넘기는 경로(휴대폰 페이지가 실제로 부르는 것만). (메서드, 경로 정규식, 넘길 쿼리 키)
SID = r"[0-9a-f]{16}"       # 픽업 세션 id(16자리 16진수, MeeosEngine·meeos_server.py 와 같은 형식)
GID = r"[0-9a-f]{6}"        # 가족 그룹 id(LinkHub rid(6))
ROUTES = [
    # 페이지: 기다리는 사람 /m/<세션>, 보호자 보기 /f/<세션>, 도로·가족 링크 /link[/<그룹>]
    ("GET", rf"/[mf]/{SID}/?", ()),
    ("GET", rf"/link(/({GID}|road))?/?", ()),
    # 기다리는 사람 화면(rider.html)
    ("GET", r"/api/map", ("sid",)),
    ("GET", r"/api/presets", ("sid",)),
    ("GET", rf"/api/session/{SID}", ()),
    ("GET", rf"/api/session/{SID}/nearby", ("kind", "from")),
    ("POST", rf"/api/session/{SID}/(rider|choose|clock|message|toilet|ack|aboard)", ()),
    # 도로·가족 링크 휴대폰(link/link.html). ?t=(시계 바꾸기)는 넘기지 않는다
    ("GET", r"/api/road", ("me",)),
    ("GET", r"/api/road/(route|world)", ("me",)),
    ("GET", rf"/api/link/{GID}", ("me",)),
    ("POST", r"/api/link/join", ()),
    ("POST", r"/api/road/state", ()),
    ("POST", rf"/api/link/{GID}/(steps|steps/done|journey/ride|journey/ack|rest|rest/answer)", ()),
]
ROUTES = [(m, re.compile(p), keys) for m, p, keys in ROUTES]
PASS_HEADERS = {"content-type", "cache-control", "x-content-type-options"}   # 앱 응답에서 돌려주는 헤더
ERRORS = {400: "bad request", 404: "not found", 405: "method", 413: "too large"}


def lan_ips():
    """공유기 안 주소(사설 대역)만 — VPN·가상 어댑터 주소는 휴대폰이 닿지 않는다"""
    ips = set()
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        ip = info[4][0]
        if ip.startswith("192.168.") or (ip.startswith("172.") and 16 <= int(ip.split(".")[1]) <= 31):
            ips.add(ip)
    return sorted(ips)


def latest():
    with urllib.request.urlopen(APP + "/api/latest", timeout=LATEST_TIMEOUT) as r:
        return json.loads(r.read()).get("id")


def allowed(method, path, query):
    """허용 목록에 있으면 앱으로 보낼 경로(+허용한 쿼리만), 없으면 None"""
    for m, pat, keys in ROUTES:
        if m == method and pat.fullmatch(path):
            q = parse_qs(query, keep_blank_values=True)
            kept = [(k, q[k][0][:MAX_QUERY_VALUE]) for k in keys if k in q]
            return path + ("?" + urlencode(kept) if kept else "")
    return None


# 발표 화면: 순정 문법(흰 바탕·검정 글자·직각·1px 회색 테두리, 그림자·파랑 없음)
JOIN = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>Tago 픽업 — 휴대폰으로 참여</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui,'Malgun Gothic',sans-serif;background:#FFFFFF;color:#000000;display:grid;place-items:center;min-height:100vh;margin:0}
.c{background:#FFFFFF;border:1px solid #D4D4D4;padding:48px 64px;text-align:center}
h1{font-size:28px;margin:0 0 8px}p{font-size:20px;color:#6E6E6E;margin:6px 0}.u{font-size:56px;font-weight:800;letter-spacing:1px;margin:24px 0;color:#000000}
small{color:#6E6E6E;font-size:16px}</style></head><body><div class="c">
<h1>Tago 픽업 · 지수 휴대폰이 되어 보세요</h1><p>같은 와이파이에 연결한 뒤 휴대폰 브라우저에 입력하세요</p>
<div class="u">%s</div><p>링크를 열면 [내 위치 공유하기] → 차가 만날 곳을 정합니다</p><small>%s</small></div></body></html>"""


class Relay(BaseHTTPRequestHandler):
    timeout = SOCKET_TIMEOUT   # StreamRequestHandler 가 소켓 읽기·쓰기 시간 제한으로 건다

    def _send(self, code, body, ctype="text/html; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _error(self, code):
        self._send(code, json.dumps({"error": ERRORS.get(code, "error")}).encode(), "application/json; charset=utf-8")

    def _proxy(self, method):
        u = urlsplit(self.path)
        target = allowed(method, u.path, u.query)
        if target is None:
            return self._error(404)
        data = None
        if method == "POST":
            if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                return self._error(400)
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                return self._error(400)
            if n < 0:
                return self._error(400)
            if n > MAX_BODY:
                self.close_connection = True
                return self._error(413)
            data = self.rfile.read(n) if n else b""
            if len(data) < n:
                return self._error(400)
        headers = {"Content-Type": "application/json"} if method == "POST" else {}
        req = urllib.request.Request(APP + target, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT) as r:
                body, code, rh = r.read(), r.status, r.headers
        except urllib.error.HTTPError as e:
            # 앱의 오류 본문(예외 문구가 섞일 수 있다)은 넘기지 않고 일반 문구로 바꾼다
            e.close()
            return self._error(e.code if e.code in ERRORS else 400)
        except OSError as e:
            print(f"차 앱에 닿지 않음({APP}): {e}", flush=True)
            return self._send(502, "차 앱에 닿지 않습니다. 잠시 뒤 다시 열어 주세요.".encode(), "text/plain; charset=utf-8")
        self.send_response(code)
        for k, v in rh.items():
            if k.lower() in PASS_HEADERS:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path in ("/j", "/j/"):
            try:
                sid = latest()
            except (OSError, ValueError):
                sid = None
            if not sid or not re.fullmatch(SID, str(sid)):
                return self._send(200, "아직 픽업이 시작되지 않았어요. 차 화면에서 [지수]를 누른 뒤 다시 열어 주세요.".encode(), "text/plain; charset=utf-8")
            return self._send(302, b"", extra={"Location": f"/m/{sid}?demo=1"})
        if path in ("/join", "/join/"):
            ips = lan_ips()
            url = f"{ips[0]}:{PORT}/j" if ips else f"(PC IP):{PORT}/j"
            other = " · ".join(f"{ip}:{PORT}/j" for ip in ips[1:])
            return self._send(200, (JOIN % (url, ("다른 주소: " + other) if other else "")).encode())
        self._proxy("GET")

    def do_POST(self):
        self._proxy("POST")

    def do_PUT(self):
        self._error(405)

    do_DELETE = do_PATCH = do_OPTIONS = do_HEAD = do_PUT

    def log_message(self, *args):
        pass


class Server(ThreadingHTTPServer):
    """동시 처리 스레드 상한: 자리가 없으면 새 연결에 바로 503 을 주고 닫는다"""
    daemon_threads = True

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.slots = threading.BoundedSemaphore(MAX_THREADS)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(1)
                request.sendall(b"HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


if __name__ == "__main__":
    ips = lan_ips()
    print(f"PT 중계 :{PORT} → {APP}")
    print("휴대폰 주소:", ", ".join(f"http://{ip}:{PORT}/j" for ip in ips) or "(사설 IP 없음 — 와이파이 연결 확인)")
    print(f"발표 화면: http://127.0.0.1:{PORT}/join")
    Server((BIND, PORT), Relay).serve_forever()
