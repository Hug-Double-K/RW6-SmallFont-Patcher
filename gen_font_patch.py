#!/usr/bin/env python3
"""
gen_font_patch.py — 원본 펌웨어 없이, 룽즈 패처 저장소의 restore 조각만으로
임의 TTF의 RW6 폰트 패치(patch.partNNN + manifest + patch.sh/verify)를 만든다.

원리: restore.partNNN 을 이어 붙이면 공식 원본의 ROMFS 엔트리 범위
[entryOffset, nextHeaderOffset) 전체가 된다. 여기에 size·checksum만 고치고
새 TTF를 dataOffset부터 쓰고, 새 EOF~originalAllocatedEnd를 0으로 채운다.
조각 배치(seek/count/blockSize)는 원본 restore manifest와 동일하게 쓴다.

사용:
  python3 gen_font_patch.py <repo>/patches/miwear.watch.p65/font/<ver>/restore \
      --regular R.ttf --medium M.ttf --demibold D.ttf -o out/<FontName>
"""
import argparse, hashlib, json, os, shutil, struct, sys

def sha(b): return hashlib.sha256(b).hexdigest()

def load_restore(slot_dir):
    m = json.load(open(os.path.join(slot_dir, "manifest.json")))
    p = m["payload"]
    data = b"".join(open(os.path.join(slot_dir, c["name"]), "rb").read() for c in p["restoreChunks"])
    assert len(data) == p["size"], "restore size mismatch"
    assert sha(data) == p["restoreSha256"], "restore sha mismatch"
    return m, bytearray(data)

def build(m, orig, ttf):
    t, p = m["target"], m["payload"]
    base = p["rangeStart"]["decimal"]
    ent = t["entryOffset"]["decimal"] - base
    dat = t["dataOffset"]["decimal"] - base
    alloc_end = t["originalAllocatedEnd"]["decimal"] - base
    if dat + len(ttf) > alloc_end:
        raise SystemExit(f"{t['path']}: 폰트가 슬롯보다 큼 ({len(ttf):,} > {alloc_end-dat:,})")
    # sanity: original header
    assert struct.unpack(">I", orig[ent+8:ent+12])[0] == t["originalSize"]["decimal"]
    buf = bytearray(orig)
    buf[ent+8:ent+12] = struct.pack(">I", len(ttf))
    buf[ent+12:ent+16] = b"\0\0\0\0"
    s = sum(struct.unpack(">%dI" % ((dat-ent)//4), bytes(buf[ent:dat]))) & 0xffffffff
    ck = (-s) & 0xffffffff
    buf[ent+12:ent+16] = struct.pack(">I", ck)
    assert sum(struct.unpack(">%dI" % ((dat-ent)//4), bytes(buf[ent:dat]))) & 0xffffffff == 0
    buf[dat:dat+len(ttf)] = ttf
    buf[dat+len(ttf):alloc_end] = b"\0" * (alloc_end - dat - len(ttf))
    return buf, ck

def write_slot(m, buf, ck, ttf, out_dir, template_dir):
    os.makedirs(out_dir, exist_ok=True)
    p = m["payload"]; base = p["rangeStart"]["decimal"]
    chunks, pos = [], 0
    for i, rc in enumerate(p["restoreChunks"]):
        part = bytes(buf[pos:pos+rc["size"]]); pos += rc["size"]
        name = "patch.part%03d" % i
        open(os.path.join(out_dir, name), "wb").write(part)
        c = dict(rc); c["name"] = name; c["sha256"] = sha(part); chunks.append(c)
    nm = json.loads(json.dumps(m))
    nm["target"]["newSize"] = {"decimal": len(ttf), "hex": "0x%08x" % len(ttf)}
    nm["target"]["newChecksum"] = "0x%08x" % ck
    nm["target"]["newFileSha256"] = sha(ttf)
    nm["payload"]["patchChunks"] = chunks
    nm["payload"]["patchChunkCount"] = len(chunks)
    nm["payload"]["patchSha256"] = sha(bytes(buf))
    json.dump(nm, open(os.path.join(out_dir, "manifest.json"), "w"), ensure_ascii=False, separators=(",", ":"))
    for s in ("patch.sh", "patch-verify.sh"):          # 범위가 같으므로 스크립트도 동일
        shutil.copy(os.path.join(template_dir, s), os.path.join(out_dir, s))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("restore_dir")
    ap.add_argument("--regular", required=True); ap.add_argument("--medium", required=True); ap.add_argument("--demibold", required=True)
    ap.add_argument("--template", help="기존 폰트 패치 폴더(예: .../NanumGothic) — patch.sh 복사용")
    ap.add_argument("-o", "--out", required=True)
    a = ap.parse_args()
    tmpl_root = a.template or os.path.join(os.path.dirname(a.restore_dir.rstrip("/")), "NanumGothic")
    for slot, f in (("MiSans-Medium", a.medium), ("MiSans-Demibold", a.demibold), ("MiSans-Regular", a.regular)):
        m, orig = load_restore(os.path.join(a.restore_dir, slot))
        ttf = open(f, "rb").read()
        buf, ck = build(m, orig, ttf)
        write_slot(m, buf, ck, ttf, os.path.join(a.out, slot), os.path.join(tmpl_root, slot))
        print(f"{slot}: {os.path.basename(f)} {len(ttf):,} B, checksum 0x{ck:08x} OK")

if __name__ == "__main__":
    main()
