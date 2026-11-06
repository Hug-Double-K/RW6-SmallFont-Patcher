package io.github.hugdoublek.rw6memo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 메모 한 건을 워치 폭(432px) 그림으로 그린다. 글씨 크기는 폰 설정을 따른다. */
public final class MemoRenderer {
    public static final int WIDTH = 432;
    private static final int PAD = 16;
    private static final int MAX_HEIGHT = 5000;

    public static Bitmap render(Memo m, int fontSize) {
        int fs = Math.max(14, fontSize);
        Paint small = paint(Math.round(fs * 0.7f), Color.rgb(160, 160, 160), false);
        Paint titleP = paint(Math.round(fs * 1.1f), Color.WHITE, true);
        Paint bodyP = paint(fs, Color.rgb(235, 235, 235), false);
        int w = WIDTH - PAD * 2;

        List<String> titleLines = wrap(m.title, titleP, w, 3);
        List<String> bodyLines = wrap(m.body, bodyP, w, 0);
        float lhS = lh(small), lhT = lh(titleP), lhB = lh(bodyP);

        float fixed = PAD + lhS + 4 + titleLines.size() * lhT + 12 + PAD;
        int maxBody = Math.max(1, (int) ((MAX_HEIGHT - fixed) / lhB));
        if (bodyLines.size() > maxBody) {
            bodyLines = new ArrayList<>(bodyLines.subList(0, maxBody));
            bodyLines.set(maxBody - 1, bodyLines.get(maxBody - 1) + "…");
        }
        boolean emptyBody = bodyLines.size() == 1 && bodyLines.get(0).isEmpty();
        int height = (int) Math.ceil(fixed + (emptyBody ? 0 : bodyLines.size() * lhB));

        Bitmap bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.rgb(32, 34, 40));
        c.drawRoundRect(new RectF(0, 0, WIDTH, height), 24, 24, bg);

        float y = PAD + lhS;
        c.drawText(new SimpleDateFormat("yyyy/MM/dd HH:mm 보냄", Locale.KOREA).format(new Date(m.time)), PAD, y - small.descent(), small);
        y += 4;
        for (String s : titleLines) { y += lhT; c.drawText(s, PAD, y - titleP.descent(), titleP); }
        y += 12;
        Paint rule = new Paint(); rule.setColor(Color.rgb(70, 72, 80));
        c.drawRect(PAD, y - 7, WIDTH - PAD, y - 6, rule);
        if (!emptyBody) for (String s : bodyLines) { y += lhB; c.drawText(s, PAD, y - bodyP.descent(), bodyP); }
        return bmp;
    }

    public static Memo sample() {
        return new Memo("sample", "장보기 목록", "우유 2개\n계란 한 판\n두부\n대파\n휴지 (30롤짜리)\n고양이 사료 - 연어맛", System.currentTimeMillis());
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

    private static float lh(Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return (fm.descent - fm.ascent) * 1.1f;
    }

    static List<String> wrap(String text, Paint p, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        for (String para : (text == null ? "" : text).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            int i = 0;
            while (i < para.length()) {
                int cp = para.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                if (p.measureText(line + ch) > maxW && line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                    if (maxLines > 0 && out.size() >= maxLines) { out.set(out.size() - 1, out.get(out.size() - 1) + "…"); return out; }
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
}
