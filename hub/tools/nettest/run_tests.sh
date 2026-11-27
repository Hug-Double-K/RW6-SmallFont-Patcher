#!/bin/bash
# 쓰는 법: RATE=바이트/초 DELAY=초 MTU=값 ./run_tests.sh  (RATE 없으면 제한 없음)
cd /home/claude/work/nettest; pkill -f "out Harness" 2>/dev/null; pkill -f bridge.py 2>/dev/null; pkill -f servers.py 2>/dev/null; sleep 0.5
rm -rf dl; mkdir dl; PORT=$((7700 + RANDOM % 200))
python3 servers.py www > servers.log 2>&1 &
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out Harness $PORT > java.log 2>&1 &
sleep 1.2; python3 bridge.py $PORT > bridge.log 2>&1 & sleep 1.5
set -f; export no_proxy="*" NO_PROXY="*"; C="curl -sS --max-time ${MAXT:-120}"; pass=0; fail=0
ok() { if [ "$2" = "$3" ]; then echo "  통과  $1"; pass=$((pass+1)); else echo "  실패  $1 (나옴: $2 / 기대: $3)"; fail=$((fail+1)); fi; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
t0=$(date +%s.%N)
ok "작은 파일" "$($C http://10.1.10.50:8099/small.txt)" "hello"
$C -o dl/mid.bin http://10.1.10.50:8099/mid.bin; ok "300KB 받기" "$(sha dl/mid.bin)" "$(sha www/mid.bin)"
t1=$(date +%s.%N); echo "    (300KB: $(python3 -c "print(round($t1-$t0,1))")초)"
if [ -z "$QUICK" ]; then
$C -o dl/big.bin http://10.1.10.50:8099/big.bin; ok "3MB 받기" "$(sha dl/big.bin)" "$(sha www/big.bin)"
pids=""; for i in 1 2 3 4; do $C -o dl/p$i.bin http://10.1.10.50:8099/mid.bin & pids="$pids $!"; done; wait $pids
ok "동시에 4개" "$(for i in 1 2 3 4; do sha dl/p$i.bin; done | sort -u)" "$(sha www/mid.bin)"
ok "1MB 올리기" "$($C --data-binary @up.bin -H 'Expect:' http://10.1.10.50:8099/up)" "$(sha up.bin) 1000000"
$C --limit-rate 30k -o dl/slow.bin http://10.1.10.50:8099/mid.bin; ok "천천히 읽는 상대(창 닫힘)" "$(sha dl/slow.bin)" "$(sha www/mid.bin)"
fi
r=$($C -o /dev/null -w "%{exitcode}" http://10.1.10.50:1/ 2>/dev/null); ok "닫힌 포트는 바로 거절" "$r" "7"
r=$($C -o dl/cut.bin -w "%{exitcode}" http://10.1.10.50:8099/cut 2>/dev/null); ok "서버가 중간에 끊음 → 오류로 끝남(멈추지 않음)" "$r" "18"
ok "DNS 넘겨주기" "$(python3 dnsq.py 10.1.10.53 test.example)" "(1, 0, '10.1.10.50')"
ok "DNS 답 없을 때 폰이 대신 풂" "$(python3 dnsq.py 10.1.10.54 localhost)" "(1, 0, '127.0.0.1')"
ok "DNS 대신 풀기: 없는 이름" "$(python3 dnsq.py 10.1.10.54 no-such-name.invalid | cut -c1-5)" "(0, 3"
ok "UDP 되돌이" "$(python3 -c "
import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.settimeout(5); s.sendto(b'abc',('10.1.10.50',7007)); print(s.recvfrom(100)[0].decode())")" "echo:abc"
ok "핑" "$(python3 -c "
import socket,struct,os
s=socket.socket(socket.AF_INET,socket.SOCK_RAW,socket.IPPROTO_ICMP); s.settimeout(5)
b=struct.pack('>BBHHH',8,0,0,1,1)+b'ping'; c=sum(struct.unpack('>6H',b)); c=(c&0xffff)+(c>>16); b=b[:2]+struct.pack('>H',~c&0xffff)+b[4:]
s.sendto(b, ('10.1.10.77',0)); d,_=s.recvfrom(100); print(d[20], d[-4:].decode())" 2>&1)" "0 ping"
sleep 3
ok "끝난 뒤 남은 연결 없음" "$(grep '열린 연결' java.log | tail -1 | grep -o '[0-9]*$')" "0"
echo "결과: 통과 $pass, 실패 $fail · $(grep "다시 보냄" java.log | tail -1)"; grep "기록" java.log | head -${LOGN:-6}
pkill -f "out Harness"; pkill -f bridge.py; pkill -f servers.py
