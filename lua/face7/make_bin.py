# Lua 워치페이스 bin 만들기: 패처 bin(687261404.bin)을 틀로, 들어 있는 lua/main.lua·ID·이름만 바꾼다. 미리보기 그림은 틀의 것 그대로.
# 쓰는 법: python3 make_bin.py <틀.bin> <main.lua> <나올.bin> <ID 9자리> <이름>
import sys, struct
tpl, lua, out, fid, name = sys.argv[1:6]
d = open(tpl, 'rb').read()
u32 = lambda o: struct.unpack_from('<I', d, o)[0]
blob_at, blob_len = u32(0x118), u32(0x11c)
head = u32(0x20)
assert d[:4] == bytes.fromhex('5aa53412') and blob_at == 0x130 and head == u32(0xac) == blob_at + blob_len
n = int.from_bytes(d[blob_at:blob_at + 3], 'little'); nl = d[blob_at + 3]
fname = d[blob_at + 20:blob_at + 20 + nl]
assert fname == b'lua/main.lua' and 20 + nl + n == blob_len
src = open(lua, 'rb').read()
src += b'\n' * (-len(src) % 4)                      # 틀처럼 4바이트 단위로 맞춤
blob = len(src).to_bytes(3, 'little') + bytes([nl]) + d[blob_at + 4:blob_at + 20] + fname + src
h = bytearray(d[:blob_at])
struct.pack_into('<I', h, 0x11c, len(blob))
struct.pack_into('<I', h, 0x20, blob_at + len(blob)); struct.pack_into('<I', h, 0xac, blob_at + len(blob))
assert len(fid) == 9 and fid.isdigit() and h[0x28:0x31] == b'687261404'
h[0x28:0x31] = fid.encode()
nb = name.encode(); assert len(nb) < 60
h[0x68:0x68 + 64] = nb + bytes(64 - len(nb))
open(out, 'wb').write(bytes(h) + blob + d[head:])
print(out, len(h) + len(blob) + len(d) - head, 'bytes, lua', len(src))
