package io.github.hugdoublek.rw6hub.noti;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 알림 한 건을 워치 화면 폭(432px)에 맞는 카드 이미지로 그린다.
 * 폰에서 그리므로 워치 폰트와 무관하게 한글·글자 크기가 원하는 대로 나온다.
 */
public final class Renderer {
    public static final int WIDTH = 432;
    private static final int PAD = 14;          // 카드 안쪽 여백 (기존 18)
    private static final float HEADER_SCALE = 0.85f; // 앱 아이콘·헤더 줄 크기 (기존 대비 85%)
    /** 사진 세로 한도. 0.96(사용자 요청 2026-11-28: 사진 가로를 최대로): 640 → 1040.
     *  세로로 긴 사진(폰 화면 캡처 9:19.5~9:21)도 가로 432 를 꽉 채우게 — 432 폭에서 936~1008.
     *  카드 전체는 MAX_HEIGHT(1800, 워치에서 됐던 크기) 안. 이보다 더 긴 사진만 비율대로 줄어 가로가 좁아짐. */
    public static final int PIC_MAX_H = 1040;
    private static final int MAX_HEIGHT = 1800;  // 워치 메모리 한계 (5000으로 했다가 워치가 재시작됨). 글 알림은 이미지가 아니라 글로 보냄

    public static Bitmap render(Drawable icon, String appName, String convTitle, String sender,
                                String body, Bitmap picture, int fontSize, long when) {
        return render(icon, appName, convTitle, sender, body, picture, fontSize, when, 0);
    }

    /** maxPicW > 0 이면 사진 가로를 이 값과 원본 중 작은 쪽으로 (확대 안 함, 가운데 정렬). 카톡 이모티콘용 */
    public static Bitmap render(Drawable icon, String appName, String convTitle, String sender,
                                String body, Bitmap picture, int fontSize, long when, int maxPicW) {
        int fs = Math.max(14, fontSize);
        int smallFs = Math.round(fs * 0.78f * HEADER_SCALE);
        int senderFs = Math.round(fs * 1.05f);
        int contentW = WIDTH - PAD * 2;

        Paint small = paint(smallFs, Color.rgb(170, 170, 170), false);
        Paint senderP = paint(senderFs, Color.WHITE, true);
        Paint bodyP = paint(fs, Color.rgb(235, 235, 235), false);

        String time = new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(new Date(when));
        String header = appName + " · " + time;
        if (convTitle != null && !convTitle.isEmpty() && !convTitle.equals(sender)) {
            header = convTitle + " · " + time;
        }

        int iconSize = Math.round(fs * 0.78f * 1.6f * HEADER_SCALE);
        List<String> headerLines = wrap(header, small, contentW - iconSize - 10, 1);
        List<String> senderLines = wrap(sender == null ? "" : sender, senderP, contentW, 2);
        if (picture != null && body != null && (body.contains("사진을 보냈습니다") || body.contains("이모티콘을 보냈습니다"))) body = ""; // 그림이 보이면 안내문은 생략
        List<String> bodyLines = wrap(body == null ? "" : body, bodyP, contentW, 0);

        float lhSmall = lineHeight(small), lhSender = lineHeight(senderP), lhBody = lineHeight(bodyP);
        float y = PAD + Math.max(iconSize, lhSmall) + 6;
        float senderH = senderLines.isEmpty() || senderLines.get(0).isEmpty() ? 0 : senderLines.size() * lhSender + 6;

        Bitmap pic = null;
        int picH = 0;
        if (picture != null) {
            // 가로를 카드 폭에 꽉 채우고 세로는 비율대로. 세로가 한도(PIC_MAX_H)를 넘는 긴 사진만
            // 잘라내지 않고 전체를 비율 그대로 줄여서 한도 안에 맞춤 (가로가 조금 좁아지고 가운데 정렬)
            // 0.96: 일반 사진은 옆 여백 없이 화면 폭(432) 전체, 이모티콘(maxPicW > 0)은 예전대로 여백 안쪽
            float scale = Math.min((float) (maxPicW > 0 ? contentW : WIDTH) / picture.getWidth(), (float) PIC_MAX_H / picture.getHeight());
            if (maxPicW > 0) scale = Math.min(scale, Math.min(1f, (float) maxPicW / picture.getWidth()));
            int pw = Math.max(1, Math.round(picture.getWidth() * scale));
            picH = Math.max(1, Math.round(picture.getHeight() * scale));
            pic = Bitmap.createScaledBitmap(picture, pw, picH, true);
        }

        // 본문이 너무 길면 줄 수를 자른다
        boolean bleed = pic != null && maxPicW <= 0;   // 화면 폭을 꽉 채우는 사진: 아래 여백 없이 카드 끝까지
        float fixed = y + senderH + (pic != null ? picH + 12 : 0) + (bleed ? 0 : PAD);
        int maxBodyLines = Math.max(1, (int) ((MAX_HEIGHT - fixed) / lhBody));
        if (bodyLines.size() > maxBodyLines) {
            bodyLines = new ArrayList<>(bodyLines.subList(0, maxBodyLines));
            int last = bodyLines.size() - 1;
            bodyLines.set(last, bodyLines.get(last) + "…");
        }
        float bodyH = bodyLines.size() == 1 && bodyLines.get(0).isEmpty() ? 0 : bodyLines.size() * lhBody;
        int height = (int) Math.ceil(fixed + bodyH);

        Bitmap bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.rgb(32, 34, 40));
        c.drawRoundRect(new RectF(0, 0, WIDTH, height), 28, 28, bg);

        // 헤더: 아이콘 + 앱/대화방 · 시간
        Bitmap iconBmp = iconBitmap(icon, iconSize);
        if (iconBmp == null) iconBmp = letterIcon(appName, iconSize);
        if (iconBmp != null) c.drawBitmap(iconBmp, PAD, PAD, null);
        float hx = PAD + (icon != null ? iconSize + 10 : 0);
        float hy = PAD + (iconSize + small.getTextSize()) / 2f - 2;
        c.drawText(headerLines.get(0), hx, hy, small);

        // 보낸 사람
        float cy = y;
        if (senderH > 0) {
            for (String s : senderLines) {
                cy += lhSender;
                c.drawText(s, PAD, cy - senderP.descent(), senderP);
            }
            cy += 6;
        }
        // 본문
        if (bodyH > 0) {
            for (String s : bodyLines) {
                cy += lhBody;
                c.drawText(s, PAD, cy - bodyP.descent(), bodyP);
            }
        }
        // 사진
        if (pic != null) {
            cy += 12;
            // 이모티콘(maxPicW > 0)은 왼쪽 정렬, 일반 사진은 화면 폭 가운데(보통 폭 전체), 카드의 둥근 모서리에 맞춰 잘라 그림
            if (bleed) {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new RectF(0, 0, WIDTH, height), 28, 28, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawBitmap(pic, (WIDTH - pic.getWidth()) / 2f, cy, null);
                c.restore();
            } else c.drawBitmap(pic, PAD, cy, null);
        }
        return bmp;
    }

    /** 미리보기·테스트용: 사진이 든 카드 */
    public static Bitmap sample(int fontSize) {
        return render(null, "카카오톡", null, "김민수", "사진을 보냈습니다.",
                samplePicture(), fontSize, System.currentTimeMillis());
    }

    /** 테스트용 그림 (하늘·산·해) */
    static Bitmap samplePicture() {
        int w = 400, h = 300;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, Color.rgb(70, 130, 220), Color.rgb(200, 225, 255), android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(Color.rgb(255, 210, 80));
        c.drawCircle(300, 80, 40, p);
        p.setColor(Color.rgb(60, 110, 70));
        android.graphics.Path m = new android.graphics.Path();
        m.moveTo(0, h); m.lineTo(120, 120); m.lineTo(220, 230); m.lineTo(300, 150); m.lineTo(w, h); m.close();
        c.drawPath(m, p);
        p.setColor(Color.rgb(40, 80, 50));
        c.drawRect(0, 250, w, h, p);
        return b;
    }

    /** 이모지가 든 문단 하나를 워치 글 화면과 비슷한 모양의 그림으로 (투명 배경, 글자 흰색, 줄 폭에 딱 맞게) */
    public static Bitmap paraBitmap(String text, int fs, int maxW) {
        android.text.TextPaint p = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(fs);
        p.setColor(0xFFEEEEEE);
        android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), p, maxW)
                .setLineSpacing(0, 1.1f).build();
        float w = 1;
        for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineWidth(i));
        int bw = Math.min(maxW, (int) Math.ceil(w) + 2), bh = Math.max(1, l.getHeight());
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        l.draw(new Canvas(b));
        return b;
    }

    /** 한 줄(넘치면 2줄까지)을 글자 폭에 딱 맞는 투명 그림으로. 보낸 사람·대화방 이름의 이모지 표시용 */
    public static Bitmap lineBitmap(String text, int fs, int color, boolean bold, int maxW) {
        android.text.TextPaint p = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(fs);
        p.setColor(color);
        if (bold) p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), p, maxW)
                .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        float w = 1;
        for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineWidth(i));
        int bw = Math.min(maxW, (int) Math.ceil(w) + 2), bh = Math.max(1, l.getHeight());
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        l.draw(new Canvas(b));
        return b;
    }

    /** 이모지만 있는 메시지: 이모지만 딱 맞는 크기로 (투명 배경 PNG, 몇 KB) */
    public static Bitmap emojiBitmap(String text, int size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        String t = text.trim();
        int w = Math.max(1, Math.min(404, (int) Math.ceil(p.measureText(t)) + 4));
        Paint.FontMetrics fm = p.getFontMetrics();
        int h = (int) Math.ceil(fm.descent - fm.ascent) + 4;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawText(t, 2, 2 - fm.ascent, p);
        return b;
    }

    /** 앱 아이콘을 작은 정사각형 이미지로 (글 알림 카드용) */
    public static Bitmap iconBitmap(Drawable d, int size) {
        if (d == null) return null;
        try {
            int big = Math.max(size * 3, 144);     // 크게 그린 뒤 축소하면 또렷함
            Bitmap b = rasterize(d, big);
            if (b != null && !isBlank(b)) {
                if (big == size) return b;
                Bitmap s = Bitmap.createScaledBitmap(b, size, size, true);
                if (s != b) b.recycle();
                return s;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 드로어블을 size×size 비트맵으로. 적응형 아이콘은 배경·전경 층을 직접 합성(그냥 draw하면 투명하게 나오는 기기 대응) */
    private static Bitmap rasterize(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) d;
            Drawable bg = aid.getBackground();
            Drawable fg = aid.getForeground();
            int ext = Math.round(size * 0.25f);    // 108/72 비율: 안전영역이 화면을 채우도록 바깥으로 넓혀 그림
            int l = -ext, t = -ext, r = size + ext, bo = size + ext;
            if (bg != null) { bg.setBounds(l, t, r, bo); bg.draw(c); }
            if (fg != null) { fg.setBounds(l, t, r, bo); fg.draw(c); }
            return roundMask(b);                   // 둥근 사각형으로 다듬음
        }
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    /** 비트맵을 둥근 사각형으로 오려냄 */
    private static Bitmap roundMask(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = w * 0.24f;
        c.drawRoundRect(new RectF(0, 0, w, h), rad, rad, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    /** 그려진 아이콘이 거의 투명하면 true (빈 아이콘 판정) */
    private static boolean isBlank(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.min(w, h) / 10);
        int opaque = 0, total = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                total++;
                if ((b.getPixel(x, y) >>> 24) > 24) opaque++;
            }
        }
        return opaque * 100 < total * 4; // 불투명 픽셀 4% 미만이면 빈 것으로 봄
    }

    private static final int[] ICON_COLORS = {
            0xFF5B8DEF, 0xFF34C759, 0xFFFF9F0A, 0xFFFF6FA8, 0xFFAF7BFF, 0xFF00B8C4, 0xFFEF5B5B, 0xFF7C8AA5 };

    /** 앱 아이콘을 못 그릴 때 쓰는 대체 아이콘: 색 원 + 앱 이름 첫 글자 */
    public static Bitmap letterIcon(String name, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        String s = name == null ? "" : name.trim();
        String ch = s.isEmpty() ? "?" : s.substring(0, 1);
        int color = ICON_COLORS[Math.abs(s.hashCode()) % ICON_COLORS.length];
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(color);
        c.drawCircle(size / 2f, size / 2f, size / 2f, bg);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setFakeBoldText(true);
        tp.setTextSize(size * 0.56f);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText(ch, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, tp);
        return b;
    }

    /** 사진 카드는 JPEG (PNG보다 5~10배 작아 전송이 빠름). 투명한 곳은 검정으로 */
    public static String toBase64Jpeg(Bitmap bmp) {
        Bitmap opaque = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(opaque);
        c.drawColor(android.graphics.Color.BLACK);
        c.drawBitmap(bmp, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        opaque.compress(Bitmap.CompressFormat.JPEG, 70, out); // 70: 워치 화면에선 차이 거의 없고 크기는 훨씬 작음
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    public static String toBase64Png(Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static Paint paint(int size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    private static float lineHeight(Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return (fm.descent - fm.ascent) * 1.08f;
    }

    /** 글자 단위 줄바꿈(한글 대응). maxLines 0 = 제한 없음 */
    static List<String> wrap(String text, Paint p, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        for (String para : text.replace("\r", "").split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            int i = 0;
            while (i < para.length()) {
                int cp = para.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                if (p.measureText(line + ch) > maxW && line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                    if (maxLines > 0 && out.size() >= maxLines) return ellipsize(out, p, maxW);
                }
                line.append(ch);
                i += Character.charCount(cp);
            }
            out.add(line.toString());
            if (maxLines > 0 && out.size() >= maxLines) return out;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static List<String> ellipsize(List<String> lines, Paint p, float maxW) {
        int last = lines.size() - 1;
        String s = lines.get(last);
        while (s.length() > 0 && p.measureText(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        lines.set(last, s + "…");
        return lines;
    }
}
