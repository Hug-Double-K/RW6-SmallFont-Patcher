#!/bin/sh
# Test-Face.lua 를 다섯 상황으로. A 는 알림 목록 파일이 있는 워치를 본뜸.
PAK=/home/claude/rw6-smallfont-patcher/lua/face7/face7.pak
for s in A B C D E; do
  d=$(mktemp -d); t=$(mktemp -d); q=$(mktemp -d); unset NOTI_FILE
  if [ $s = B ]; then python3 - "$d" "$PAK" <<'P'
import sys
d=open(sys.argv[2],'rb').read(); i=d.index(b'\n')+1; n=int(d[:i].split()[2])
for _ in range(n):
    j=d.index(b'\n',i); name,size=d[i:j].split(); open(sys.argv[1]+'/'+name.decode(),'wb').write(d[j+1:j+1+int(size)]); i=j+1+int(size)
open(sys.argv[1]+'/ver.txt','w').write('1')
P
  fi
  mkdir -p $q/mass $q/files
  if [ $s = A ]; then
    mkdir -p $q/mass/other.app $q/mass/com.tencent.wechatrtos/rw6noti_meta
    NOTI_FILE=$q/mass/com.tencent.wechatrtos/rw6noti_meta/index.json; export NOTI_FILE
    printf '%s' '[{"name":"1795000000000","kind":"text","uri":"internal://mass/rw6noti/1795000000000.json","a":"카카오톡","s":"홍길동","b":"안녕\n하세요 😀 줄바꿈 뒤","icon":"","w":1795000000000,"rk":"k|r"},{"name":"1794999000000","kind":"img","uri":"x.jpg","a":"","s":"","b":"","icon":""},{"name":"1794998000000","kind":"text","a":"문자","s":"긴 글","b":"가나다라마바사아자차카타파하가나다라마바사아자차카타파하","w":1794998000000,"e":"","ew":0}]' > $NOTI_FILE
  fi
  SCEN=$s RW6FACE_DIR=$d/ RW6FACE_TMP=$t/ RW6NOTI_BASES="$q/mass $q/files" texlua face_run.lua 2>&1 | tail -${TAIL:-1} | cut -c1-${WIDE:-150}
done
