set +e
echo INSTALL_START >/data/tmp-patch/install.status
if dd if=/data/tmp-patch/restore.part001 of=/dev/resource bs=65536 seek=1574 count=16 conv=notrunc; then echo W001_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part002 of=/dev/resource bs=65536 seek=1590 count=16 conv=notrunc; then echo W002_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part003 of=/dev/resource bs=65536 seek=1606 count=16 conv=notrunc; then echo W003_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part004 of=/dev/resource bs=65536 seek=1622 count=16 conv=notrunc; then echo W004_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part005 of=/dev/resource bs=65536 seek=1638 count=16 conv=notrunc; then echo W005_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part006 of=/dev/resource bs=65536 seek=1654 count=16 conv=notrunc; then echo W006_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part007 of=/dev/resource bs=65536 seek=1670 count=16 conv=notrunc; then echo W007_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part008 of=/dev/resource bs=65536 seek=1686 count=9 conv=notrunc; then echo W008_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part009 of=/dev/resource bs=16 seek=6942720 count=1309 conv=notrunc; then echo W009_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part000 of=/dev/resource bs=16 seek=6443389 count=3715 conv=notrunc; then echo W000_OK >>/data/tmp-patch/install.status; fi
