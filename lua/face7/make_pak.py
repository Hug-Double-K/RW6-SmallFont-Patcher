# DK_07(물결) 그림 묶음 만들기: 그림 여러 장을 파일 하나(face7.pak)로 — 워치의 Lua가 한 번 받아 풀어 씀.
# 꼴: "RW6PAK 1 <개수>\n" 뒤에 그림마다 "<이름> <바이트 수>\n<내용>"
import os, sys
src = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/work/faces/lua7/images'
out = sys.argv[2] if len(sys.argv) > 2 else '/home/claude/rw6-smallfont-patcher/lua/face7/face7.pak'
names = sorted(n for n in os.listdir(src) if n.endswith('.png') and n != 'preview.png' and not n.startswith('tap'))
body = b''
for n in names:
    data = open(os.path.join(src, n), 'rb').read()
    assert data[:4] == b'\x89PNG' and ' ' not in n
    body += f'{n} {len(data)}\n'.encode() + data
open(out, 'wb').write(f'RW6PAK 1 {len(names)}\n'.encode() + body)
print(len(names), 'files', os.path.getsize(out), 'bytes')
