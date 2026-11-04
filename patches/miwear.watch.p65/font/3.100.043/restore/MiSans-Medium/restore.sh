set +e
echo INSTALL_START >/data/tmp-patch/install.status
if dd if=/data/tmp-patch/restore.part001 of=/dev/resource bs=65536 seek=1696 count=16 conv=notrunc; then echo W001_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part002 of=/dev/resource bs=65536 seek=1712 count=16 conv=notrunc; then echo W002_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part003 of=/dev/resource bs=65536 seek=1728 count=16 conv=notrunc; then echo W003_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part004 of=/dev/resource bs=65536 seek=1744 count=16 conv=notrunc; then echo W004_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part005 of=/dev/resource bs=65536 seek=1760 count=16 conv=notrunc; then echo W005_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part006 of=/dev/resource bs=65536 seek=1776 count=16 conv=notrunc; then echo W006_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part007 of=/dev/resource bs=65536 seek=1792 count=16 conv=notrunc; then echo W007_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part008 of=/dev/resource bs=65536 seek=1808 count=16 conv=notrunc; then echo W008_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part009 of=/dev/resource bs=65536 seek=1824 count=16 conv=notrunc; then echo W009_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part010 of=/dev/resource bs=65536 seek=1840 count=16 conv=notrunc; then echo W010_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part011 of=/dev/resource bs=65536 seek=1856 count=16 conv=notrunc; then echo W011_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part012 of=/dev/resource bs=65536 seek=1872 count=16 conv=notrunc; then echo W012_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part013 of=/dev/resource bs=65536 seek=1888 count=1 conv=notrunc; then echo W013_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part014 of=/dev/resource bs=16 seek=7737344 count=1693 conv=notrunc; then echo W014_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part000 of=/dev/resource bs=16 seek=6944029 count=2787 conv=notrunc; then echo W000_OK >>/data/tmp-patch/install.status; fi
