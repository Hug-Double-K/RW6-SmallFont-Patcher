set +e
echo INSTALL_START >/data/tmp-patch/install.status
if dd if=/data/tmp-patch/restore.part001 of=/dev/resource bs=65536 seek=1890 count=16 conv=notrunc; then echo W001_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part002 of=/dev/resource bs=65536 seek=1906 count=16 conv=notrunc; then echo W002_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part003 of=/dev/resource bs=65536 seek=1922 count=16 conv=notrunc; then echo W003_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part004 of=/dev/resource bs=65536 seek=1938 count=16 conv=notrunc; then echo W004_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part005 of=/dev/resource bs=65536 seek=1954 count=16 conv=notrunc; then echo W005_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part006 of=/dev/resource bs=65536 seek=1970 count=16 conv=notrunc; then echo W006_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part007 of=/dev/resource bs=65536 seek=1986 count=16 conv=notrunc; then echo W007_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part008 of=/dev/resource bs=65536 seek=2002 count=8 conv=notrunc; then echo W008_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part009 of=/dev/resource bs=16 seek=8232960 count=179 conv=notrunc; then echo W009_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/restore.part000 of=/dev/resource bs=16 seek=7739037 count=2403 conv=notrunc; then echo W000_OK >>/data/tmp-patch/install.status; fi
