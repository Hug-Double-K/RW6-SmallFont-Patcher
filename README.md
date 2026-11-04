# RW6-SmallFont-Patcher

레드미 워치 6 내수판(miwear.watch.p65)용 **알림 본문 축소 폰트 패치** 배포 저장소입니다.

- 패치 방식·도구·로더는 [maga32/RedmiWatch6-ResourcePatcher](https://github.com/maga32/RedmiWatch6-ResourcePatcher)(룽즈님)를 그대로 사용하고, 받아오는 주소만 이 저장소로 바꿨습니다.
- 폰트: 나눔고딕(SIL OFL 1.1, 라이선스 `font/License/`)을 글리프·자간·행간 85% / 80%로 축소한 수정본.

| 패치 이름 | MiSans-Regular | MiSans-Medium | MiSans-Demibold |
| --- | --- | --- | --- |
| NanumSmall85Body | 나눔고딕 85% | 나눔고딕 | 나눔고딕 |
| NanumSmall80Body | 나눔고딕 80% | 나눔고딕 | 나눔고딕 |

지원 펌웨어: 3.100.043. `restore/`는 공식 원본 복원용(원 저장소와 동일).

도구: `make_small_font.py`(축소 폰트 생성), `gen_font_patch.py`(restore 조각만으로 패치 생성 — 원 저장소의 NanumGothic 패치와 바이트 단위 동일함을 검증).
