set +e
echo VERIFY_START >/data/tmp-patch/verify.status
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=16 skip=6944029 count=2787
if cmp /data/tmp-patch/patch.part000 /data/tmp-patch/readback.tmp; then echo V000_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1696 count=16
if cmp /data/tmp-patch/patch.part001 /data/tmp-patch/readback.tmp; then echo V001_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1712 count=16
if cmp /data/tmp-patch/patch.part002 /data/tmp-patch/readback.tmp; then echo V002_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1728 count=16
if cmp /data/tmp-patch/patch.part003 /data/tmp-patch/readback.tmp; then echo V003_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1744 count=16
if cmp /data/tmp-patch/patch.part004 /data/tmp-patch/readback.tmp; then echo V004_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1760 count=16
if cmp /data/tmp-patch/patch.part005 /data/tmp-patch/readback.tmp; then echo V005_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1776 count=16
if cmp /data/tmp-patch/patch.part006 /data/tmp-patch/readback.tmp; then echo V006_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1792 count=16
if cmp /data/tmp-patch/patch.part007 /data/tmp-patch/readback.tmp; then echo V007_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1808 count=16
if cmp /data/tmp-patch/patch.part008 /data/tmp-patch/readback.tmp; then echo V008_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1824 count=16
if cmp /data/tmp-patch/patch.part009 /data/tmp-patch/readback.tmp; then echo V009_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1840 count=16
if cmp /data/tmp-patch/patch.part010 /data/tmp-patch/readback.tmp; then echo V010_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1856 count=16
if cmp /data/tmp-patch/patch.part011 /data/tmp-patch/readback.tmp; then echo V011_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1872 count=16
if cmp /data/tmp-patch/patch.part012 /data/tmp-patch/readback.tmp; then echo V012_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=65536 skip=1888 count=1
if cmp /data/tmp-patch/patch.part013 /data/tmp-patch/readback.tmp; then echo V013_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
dd if=/dev/resource of=/data/tmp-patch/readback.tmp bs=16 skip=7737344 count=1693
if cmp /data/tmp-patch/patch.part014 /data/tmp-patch/readback.tmp; then echo V014_OK >>/data/tmp-patch/verify.status; fi
rm /data/tmp-patch/readback.tmp
