set +e
echo INSTALL_START >/data/tmp-patch/install.status
if dd if=/data/tmp-patch/patch.part001 of=/dev/resource bs=65536 seek=1472 count=4 conv=notrunc; then echo W001_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/patch.part002 of=/dev/resource bs=16 seek=6045696 count=802 conv=notrunc; then echo W002_OK >>/data/tmp-patch/install.status; fi
if dd if=/data/tmp-patch/patch.part000 of=/dev/resource bs=16 seek=6027774 count=1538 conv=notrunc; then echo W000_OK >>/data/tmp-patch/install.status; fi
