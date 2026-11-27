#!/usr/bin/env python3
"""시험용: 리눅스의 진짜 TCP/IP(=워치 역할)를 TUN 장치로 꺼내 자바 중계기와 잇는다.
자바 쪽과는 localhost 소켓으로 [길이 BE16][IP 패킷]을 주고받는다. RATE(바이트/초)·DELAY(초)로 느린 블루투스를 흉내 낸다."""
import fcntl, os, socket, struct, sys, threading, time, select
TUNSETIFF = 0x400454ca; IFF_TUN = 0x0001; IFF_NO_PI = 0x1000
SIOCSIFADDR, SIOCSIFNETMASK, SIOCGIFFLAGS, SIOCSIFFLAGS, SIOCSIFMTU = 0x8916, 0x891c, 0x8913, 0x8914, 0x8922
name = b"rw6t0"
port = int(sys.argv[1]); rate = float(os.environ.get("RATE", "0")); delay = float(os.environ.get("DELAY", "0")); mtu = int(os.environ.get("MTU", "1400")); loss = float(os.environ.get("LOSS", "0"))
import random; random.seed(7)
tun = os.open("/dev/net/tun", os.O_RDWR)
fcntl.ioctl(tun, TUNSETIFF, struct.pack("16sH", name, IFF_TUN | IFF_NO_PI))
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
def addr(a): return struct.pack("16sH2s4s8s", name, socket.AF_INET, b"\0\0", socket.inet_aton(a), b"\0" * 8)
fcntl.ioctl(s, SIOCSIFADDR, addr("10.1.10.2"))
fcntl.ioctl(s, SIOCSIFNETMASK, addr("255.255.255.0"))
fcntl.ioctl(s, SIOCSIFMTU, struct.pack("16si", name, mtu))
flags = struct.unpack("16sH", fcntl.ioctl(s, SIOCGIFFLAGS, struct.pack("16sH", name, 0)))[1]
fcntl.ioctl(s, SIOCSIFFLAGS, struct.pack("16sH", name, flags | 1 | 0x40))
print("TUN 준비", flush=True)
c = socket.create_connection(("127.0.0.1", port))
c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
stats = {"to_java": 0, "to_tun": 0}
class Pacer:
    """rate 바이트/초로 내보내고, delay만큼 늦춘다"""
    def __init__(self, sink):
        self.q = []; self.cv = threading.Condition(); self.sink = sink; self.next = time.time()
        threading.Thread(target=self.run, daemon=True).start()
    def put(self, pkt):
        if loss > 0 and random.random() < loss: return
        with self.cv: self.q.append((time.time() + delay, pkt)); self.cv.notify()
    def run(self):
        while True:
            with self.cv:
                while not self.q: self.cv.wait()
                due, pkt = self.q.pop(0)
            now = time.time()
            t = max(due, self.next)
            if t > now: time.sleep(t - now)
            if rate > 0: self.next = max(time.time(), self.next) + len(pkt) / rate
            try: self.sink(pkt)
            except OSError: return
def to_java(pkt): c.sendall(struct.pack(">H", len(pkt)) + pkt); stats["to_java"] += 1
def to_tun(pkt): os.write(tun, pkt); stats["to_tun"] += 1
pj, pt = Pacer(to_java), Pacer(to_tun)
def from_tun():
    while True:
        pkt = os.read(tun, 4096)
        if pkt and (pkt[0] >> 4) == 4: pj.put(pkt)
threading.Thread(target=from_tun, daemon=True).start()
buf = b""
while True:
    d = c.recv(65536)
    if not d: break
    buf += d
    while len(buf) >= 2:
        n = struct.unpack(">H", buf[:2])[0]
        if len(buf) < 2 + n: break
        pt.put(buf[2:2 + n]); buf = buf[2 + n:]
