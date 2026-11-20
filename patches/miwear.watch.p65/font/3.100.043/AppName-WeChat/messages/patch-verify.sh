set +e
echo VERIFY_START >/data/tmp-patch/verify.status
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=16 skip=6027774 count=1538
if cmp /data/tmp-patch/patch.part000 /data/tmp-patch/readback.tmp; then echo V000_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1472 count=4
if cmp /data/tmp-patch/patch.part001 /data/tmp-patch/readback.tmp; then echo V001_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=16 skip=6045696 count=802
if cmp /data/tmp-patch/patch.part002 /data/tmp-patch/readback.tmp; then echo V002_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
