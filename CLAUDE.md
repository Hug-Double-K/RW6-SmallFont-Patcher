# 작업 메모 (Claude가 새 대화·압축 뒤에 먼저 읽을 것)

- 사용자와는 항상 **한국어**로 대화. 결과 위주로 짧게. 알림(푸시)은 원하지 않음.
- 기기: 중국판 REDMI Watch 6 (펌웨어 3.100.043, miwear.watch.p65), 안드로이드 폰, Mi Fitness(지역 중국), 설치는 MiFaceDown.
  - MiFaceDown: `.bin` = "워치페이스 선택", `.rpk` = "퀵앱 선택". **"펌웨어 선택"은 절대 금지** (워치가 꺼졌던 사고).
- 이 저장소: 폰트 축소 패치(patches/…/font), 패처 워치페이스, `noti/`(메신저 알림 = RW6 알림), `memo/`(RW6 메모).
- 빌드: 푸시하면 GitHub Actions가 apk/rpk를 빌드해 `noti/release/`, `memo/release/`에 "Build outputs <sha> [skip ci]" 커밋으로 올림. 버전은 build.gradle(폰)과 watch/src/manifest.json(워치)에서 올림.
- 서명: 폰·워치 앱은 같은 인증서(noti/sign, */watch/sign). 패키지 이름도 폰=워치.
- 안정 버전 기록: `stable/README.md` (메신저 알림 2.7, RW6 메모 1.7).
- 정리 문서(Claude Doc): https://claude.ai/artifact/7BJZKBxyzzH3jFqSXf1xUk
- 교훈: 워치는 큰 이미지(높이 5000px)에서 재시작됨 / 앱 업데이트 후 폰의 알림 접근이 끊기면 실제 알림만 안 옴(테스트는 됨) → 알림 접근 껐다 켜기.
- 나중에 할 일 후보: 폰 배터리 표시 앱(사용자가 "나중에"라고 함).
- 파일 이름 규칙(사용자 요청 2026-11-09): 보낼 때 `RW6Noti_V3.2.apk` / `RW6Noti_V3.2.rpk`처럼 뒤에 `_V<버전>`. 폰·워치 버전 번호를 같게 맞춤(메신저 알림은 3.2부터 통일). 메모 앱도 `RW6Memo_V1.7` 식.
- 앱별 설명서(Claude Doc): 메신저 알림 https://claude.ai/artifact/EUEBtbSGQtqWLw5eXZjxev · RW6 메모 https://claude.ai/artifact/Jc6tmisdL7e5grrdZY5r5h (새 버전 낼 때 버전 기록 표에 한 줄 추가)
- RW6 허브(`hub/`, Mi Fitness·MiFaceDown 대체, 2026-11-13 시작): 폰 앱 하나로 SPP(SAR v2) 직접 연결. 0.1 = 샤오미 로그인(웹 화면→passToken→hlth.io.mi.com get_source_list로 인증키) + 연결·인증 + 워치 정보. 다음: 2단계 퀵앱 메시지 중계(Noti·메모 이전). 참고 구현 AstroBox-NG(AGPL)은 방식만 참고, 코드 복사 금지. 워치 주소 E8:E6:09:CC:18:D6.
