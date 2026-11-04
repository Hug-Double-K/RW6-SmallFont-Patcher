#!/usr/bin/env python3
"""
make_small_font.py — 레드미 워치 6 폰트 패치용 '축소 폰트' 생성기

원리: 글리프 좌표·자간·행간(hhea/OS2)을 모두 pct%로 줄인 뒤 unitsPerEm만 원래 값으로
되돌립니다. 그러면 워치가 같은 픽셀 크기(예: 18px)를 요청해도 글자가 pct%로 그려집니다.

사용 예 (알림 본문만 줄이고 메뉴는 유지하려는 1차 시도):
    python3 make_small_font.py NanumGothic.ttf --regular 85 --medium 100 --demibold 100 -o input/font

사용 예 (전체 동일하게 85%):
    python3 make_small_font.py NanumGothic.ttf --all 85 -o input/font

출력: <out>/MiSans-Regular.ttf, MiSans-Medium.ttf, MiSans-Demibold.ttf
      → RedmiWatch6-ResourcePatcher 의 input/font/ 에 그대로 넣고 `node create-patch.js`

요구: pip install fonttools
주의: MiSans 자체는 라이선스상 외형 수정 금지. 나눔고딕(OFL) 등 수정 허용 폰트만 사용하세요.
      교체 폰트는 원본 슬롯보다 작아야 합니다 (3.100.043 기준 Regular 8,009,704 / Demibold 7,905,580 / Medium 12,719,916 바이트).
"""
import argparse, os, sys
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem

SLOTS = {"MiSans-Regular": 8_009_704, "MiSans-Demibold": 7_905_580, "MiSans-Medium": 12_719_916}

def shrink(src, pct, out_path, tag):
    f = TTFont(src)
    upem = f["head"].unitsPerEm
    if pct != 100:
        scale_upem(f, int(round(upem * pct / 100)))
        f["head"].unitsPerEm = upem          # 명목 크기는 그대로 → 실제 렌더링만 pct%
    # 내부 이름을 바꿔 원본과 구분 (OFL 준수: 수정본은 다른 이름 사용)
    for rec in f["name"].names:
        if rec.nameID in (1, 3, 4, 6, 16):
            try:
                rec.string = rec.toUnicode() + f" S{pct}"
            except Exception:
                pass
    f.save(out_path)
    size = os.path.getsize(out_path)
    limit = SLOTS.get(tag)
    ok = "OK" if (limit is None or size <= limit) else "TOO BIG!"
    print(f"{tag}: {pct}% -> {out_path} ({size:,} bytes, slot {limit:,}) {ok}")
    return ok == "OK"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("font", help="원본 TTF (수정 허용 라이선스)")
    ap.add_argument("--all", type=int, help="세 파일 모두 같은 비율(%)")
    ap.add_argument("--regular", type=int, default=100)
    ap.add_argument("--medium", type=int, default=100)
    ap.add_argument("--demibold", type=int, default=100)
    ap.add_argument("-o", "--out", default="input/font")
    a = ap.parse_args()
    if a.all:
        a.regular = a.medium = a.demibold = a.all
    os.makedirs(a.out, exist_ok=True)
    plan = {"MiSans-Regular": a.regular, "MiSans-Medium": a.medium, "MiSans-Demibold": a.demibold}
    all_ok = True
    for tag, pct in plan.items():
        all_ok &= shrink(a.font, pct, os.path.join(a.out, f"{tag}.ttf"), tag)
    sys.exit(0 if all_ok else 1)

if __name__ == "__main__":
    main()
