import socket, struct, sys
def q(server, name, qtype=1, timeout=6):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(timeout)
    m = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\x00" + struct.pack(">HH", qtype, 1)
    s.sendto(m, (server, 53)); r, _ = s.recvfrom(2048)
    an = struct.unpack(">H", r[6:8])[0]; rcode = r[3] & 15
    return an, rcode, ".".join(str(b) for b in r[-4:]) if an else ""
print(q(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 1))
