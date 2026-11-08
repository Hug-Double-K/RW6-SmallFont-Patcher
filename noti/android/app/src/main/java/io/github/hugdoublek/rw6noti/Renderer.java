package io.github.hugdoublek.rw6noti;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
    private static final int MAX_HEIGHT = 1800;  // 워치 메모리 한계 (5000으로 했다가 워치가 재시작됨). 글 알림은 이미지가 아니라 글로 보냄

    public static Bitmap render(Drawable icon, String appName, String convTitle, String sender,
                                String body, Bitmap picture, int fontSize, long when) {
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
        if (picture != null && body != null && body.contains("사진을 보냈습니다")) body = ""; // 사진이 보이면 안내문은 생략
        List<String> bodyLines = wrap(body == null ? "" : body, bodyP, contentW, 0);

        float lhSmall = lineHeight(small), lhSender = lineHeight(senderP), lhBody = lineHeight(bodyP);
        float y = PAD + Math.max(iconSize, lhSmall) + 6;
        float senderH = senderLines.isEmpty() || senderLines.get(0).isEmpty() ? 0 : senderLines.size() * lhSender + 6;

        Bitmap pic = null;
        int picH = 0;
        if (picture != null) {
            // 가로를 카드 폭에 꽉 채움. 아주 긴 사진(캡처 등)은 위쪽 1100px까지만
            float scale = (float) contentW / picture.getWidth();
            int pw = contentW;
            int fullH = Math.max(1, Math.round(picture.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(picture, pw, fullH, true);
            picH = Math.min(fullH, 1100);
            pic = picH < fullH ? Bitmap.createBitmap(scaled, 0, 0, pw, picH) : scaled;
        }

        // 본문이 너무 길면 줄 수를 자른다
        float fixed = y + senderH + (pic != null ? picH + 12 : 0) + PAD;
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
        if (icon != null) {
            icon.setBounds(PAD, PAD, PAD + iconSize, PAD + iconSize);
            icon.draw(c);
        }
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
            c.drawBitmap(pic, PAD, cy, null);
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
