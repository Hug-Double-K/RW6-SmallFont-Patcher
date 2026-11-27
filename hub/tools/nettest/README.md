# 워치 인터넷 중계(NetRelay) PC 시험

워치 없이 `NetRelay.java`를 시험한다. 리눅스의 진짜 TCP/IP를 TUN 장치(10.1.10.2)로 꺼내 워치 역할을 시키고,
`curl` 등으로 10.1.10.50(→ localhost로 바꿔 붙임)에 접속해 내용이 한 바이트도 안 틀리는지 본다. root와 /dev/net/tun이 필요하다.

    mkdir -p out www && head -c 3000000 /dev/urandom > www/big.bin && head -c 300000 /dev/urandom > www/mid.bin
    head -c 1000000 /dev/urandom > up.bin && echo hello > www/small.txt
    javac -d out ../../android/app/src/main/java/io/github/hugdoublek/rw6hub/NetRelay.java && javac -cp out -d out Harness.java
    ./run_tests.sh                               # 제한 없음
    RATE=40000 DELAY=0.03 ./run_tests.sh         # 느린 블루투스 흉내 (초당 40KB)
    LOSS=0.03 RATE=80000 ./run_tests.sh          # 패킷 3% 잃음
    QUICK=1 RATE=6000 DELAY=0.1 ./run_tests.sh   # 아주 느림, 작은 것만

(스크립트 안의 경로 /home/claude/work/nettest 는 돌리는 곳에 맞게 바꿀 것)
