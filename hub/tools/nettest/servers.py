#!/usr/bin/env python3
"""시험용 서버들: HTTP(받기·올리기·중간에 끊기), 가짜 DNS"""
import hashlib, http.server, os, socket, socketserver, struct, threading, sys
os.chdir(sys.argv[1])
class H(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path.startswith("/cut"):          # 내용 길이는 크게 말하고 중간에 끊음
            self.send_response(200); self.send_header("Content-Length", "500000"); self.end_headers()
            self.wfile.write(b"x" * 50000); self.wfile.flush(); self.connection.shutdown(socket.SHUT_RDWR); return
        return super().do_GET()
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0")); h = hashlib.sha256(); left = n
        while left > 0:
            d = self.rfile.read(min(65536, left))
            if not d: break
            h.update(d); left -= len(d)
        body = (h.hexdigest() + " " + str(n - left)).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
class T(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads = True; allow_reuse_address = True
def dns():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind(("127.0.0.1", 5353))
    while True:
        q, a = s.recvfrom(2048)
        i = 12
        while q[i]: i += 1 + q[i]
        qe = i + 5
        r = q[:2] + b"\x81\x80" + b"\x00\x01\x00\x01\x00\x00\x00\x00" + q[12:qe] + b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04" + bytes([10, 1, 10, 50])
        s.sendto(r, a)
threading.Thread(target=dns, daemon=True).start()
def echo():                                     # UDP 되돌이
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind(("127.0.0.1", 7007))
    while True:
        d, a = s.recvfrom(4096); s.sendto(b"echo:" + d, a)
threading.Thread(target=echo, daemon=True).start()
print("서버 준비", flush=True)
T(("127.0.0.1", 8099), H).serve_forever()
