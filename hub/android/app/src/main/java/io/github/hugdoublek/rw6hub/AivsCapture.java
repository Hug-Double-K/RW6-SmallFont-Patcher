package io.github.hugdoublek.rw6hub;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 진단용: 크라운(샤오아이)을 눌렀을 때 워치가 보내는 것(AIVS type 14 패킷, 채널 10 음성 데이터)을 모아
 * 다운로드 폴더에 파일로 저장한다. 우리 AI가 샤오아이를 가로채게 만들 때 형식 분석에 씀.
 * 파일 형식: 반복 [시각ms(8, BE)][종류(1): 1=PB(복호화됨), 2=채널데이터 평문, 3=채널데이터(복호화됨)][채널(1)][길이(4, BE)][내용]
 */
public final class AivsCapture {
    private static final ByteArrayOutputStream BUF = new ByteArrayOutputStream();
    private static int aivsPackets, rawFrames;
    private static long rawBytes;

    static synchronized void addPb(byte[] plain) { add(1, 1, plain); aivsPackets++; }

    static synchronized void addRaw(int channel, int opcode, byte[] body) { add(opcode == 2 ? 3 : 2, channel, body); rawFrames++; rawBytes += body.length; }

    private static void add(int kind, int ch, byte[] d) {
        if (BUF.size() > 8 * 1024 * 1024) return;
        long t = System.currentTimeMillis();
        for (int i = 7; i >= 0; i--) BUF.write((int) (t >>> (i * 8)));
        BUF.write(kind);
        BUF.write(ch);
        BUF.write(d.length >>> 24); BUF.write(d.length >>> 16); BUF.write(d.length >>> 8); BUF.write(d.length);
        BUF.write(d, 0, d.length);
    }

    public static synchronized String summary() {
        return "샤오아이 패킷 " + aivsPackets + "개 · 음성 데이터 " + rawFrames + "조각 (" + rawBytes / 1024 + "KB)";
    }

    /** 다운로드 폴더에 저장. 저장한 파일 이름 또는 오류 문구 */
    public static synchronized String save(Context c) {
        if (BUF.size() == 0) return null;
        String name = "MiManager_aivs_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".bin";
        try {
            if (Build.VERSION.SDK_INT < 29) return "안드로이드 10 이상에서만 저장돼요";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return "저장 실패";
            try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                BUF.writeTo(os);
            }
            BUF.reset();
            aivsPackets = 0; rawFrames = 0; rawBytes = 0;
            return "다운로드/" + name;
        } catch (Exception e) {
            return "저장 실패: " + e.getMessage();
        }
    }
}
