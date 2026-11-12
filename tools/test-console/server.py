"""로컬 테스트 콘솔 서버. index.html을 제공하고 /api/* 요청을 백엔드로 전달한다.

같은 origin에서 API를 호출하므로 백엔드에 CORS 설정이 필요 없다. Python 표준 라이브러리만 사용한다.
실행: python3 tools/test-console/server.py  (환경변수 MOEUM_BACKEND, TEST_CONSOLE_PORT로 변경)
"""
import os
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BACKEND = os.environ.get("MOEUM_BACKEND", "http://localhost:8081").rstrip("/")
PORT = int(os.environ.get("TEST_CONSOLE_PORT", "3000"))
PAGE = Path(__file__).with_name("index.html")
FORWARD_HEADERS = ("Authorization", "Content-Type", "Accept")
# AI 응답은 워커가 비동기로 처리하므로 API 호출 자체는 짧다. 백엔드가 멈춘 경우만 끊는다.
TIMEOUT_SECONDS = 15


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api/"):
            return self._proxy()
        if self.path in ("/", "/index.html"):
            body = PAGE.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        self._proxy()

    def do_PUT(self):
        self._proxy()

    def _proxy(self):
        if not self.path.startswith("/api/"):
            return self.send_error(404)
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length) if length else None
        request = urllib.request.Request(BACKEND + self.path, data=data, method=self.command)
        for name in FORWARD_HEADERS:
            if self.headers.get(name):
                request.add_header(name, self.headers[name])
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                self._relay(response.status, response.headers.get("Content-Type"), response.read())
        except urllib.error.HTTPError as error:
            self._relay(error.code, error.headers.get("Content-Type"), error.read())
        except (urllib.error.URLError, TimeoutError) as error:
            message = f'{{"code":"BACKEND_UNAVAILABLE","message":"{BACKEND} 연결 실패: {type(error).__name__}"}}'
            self._relay(502, "application/json", message.encode())

    def _relay(self, status, content_type, body):
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}", flush=True)


if __name__ == "__main__":
    print(f"테스트 콘솔: http://localhost:{PORT}  (API → {BACKEND})", flush=True)
    ThreadingHTTPServer(("localhost", PORT), Handler).serve_forever()
