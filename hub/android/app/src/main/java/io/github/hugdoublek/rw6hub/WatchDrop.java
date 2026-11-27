package io.github.hugdoublek.rw6hub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 워치 → 폰 보고함. 워치(시험용 Lua 화면 등)가 curl 로 http://10.1.10.1/ 에 글을 올리면 여기서 받는다.
 * NetRelay 가 10.1.10.1:80 을 이 안의 127.0.0.1 포트로 돌려 준다. 순수 자바(PC 에서 시험 가능).
 *
 *   PUT|POST /r/이름         본문 = 글 전체 (curl -T 파일, curl --data-binary @파일)
 *   GET      /g/이름/번호/16진수[/end]  조각으로 나눠 올리기 (번호 0 이면 새로 시작, end 가 붙으면 끝; /g?n=&i=&d=&end= 꼴도 받음)
 *   답: "ok <지금까지 받은 바이트 수>\n"
 */
public final class WatchDrop {
    public interface Sink { void log(String line); }
    /** 워치가 폰에 시키는 일 (예: /do/launch-noti). 돌려준 글이 워치로 감 ("ok …" 이면 성공) */
    public interface Command { String run(String name); }
    public static volatile Command command;

    static final int MAX_BYTES = 512 * 1024;

    private static WatchDrop instance;
    /** 받은 글을 파일로도 남길 폴더 (없으면 메모리에만) */
    public static volatile File dir;

    private final ServerSocket server;
    private final Map<String, ByteArrayOutputStream> parts = new HashMap<>();
    private volatile String lastName = "";
    private volatile String lastText = "";
    private final java.util.LinkedHashMap<String, String> texts = new java.util.LinkedHashMap<>();
    private volatile Sink sink;

    public static synchronized WatchDrop get() throws IOException {
        if (instance == null) instance = new WatchDrop();
        return instance;
    }

    /** 이미 떠 있을 때만 돌려줌 (없으면 null) */
    public static synchronized WatchDrop peek() { return instance; }

    private WatchDrop() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::acceptLoop, "watch-drop");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return server.getLocalPort(); }
    public void setSink(Sink s) { sink = s; }
    public String lastName() { return lastName; }
    public String lastText() { return lastText; }
    /** 이번에 받은 글 전부 (이름별 최신, 받은 순서) */
    public String allText() {
        StringBuilder b = new StringBuilder();
        synchronized (texts) {
            for (Map.Entry<String, String> e : texts.entrySet()) {
                if (b.length() > 400000) break;
                b.append("\n=== 워치 보고: ").append(e.getKey()).append(" ===\n").append(e.getValue()).append("\n");
            }
        }
        return b.toString();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "watch-drop-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("줄이 너무 김");
        }
        return b.toString("ISO-8859-1");
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) throw new IOException("본문이 중간에 끊김 (" + got + "/" + n + ")");
            got += r;
        }
        return buf;
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            OutputStream os = s.getOutputStream();
            String request = readLine(in);
            if (request == null) return;
            String[] rq = request.split(" ");
            if (rq.length < 2) { answer(os, 400, "bad request\n"); return; }
            String method = rq[0], target = rq[1];
            int length = -1;
            boolean chunked = false, expect = false;
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                String low = h.toLowerCase(java.util.Locale.ROOT);
                if (low.startsWith("content-length:")) {
                    try { length = Integer.parseInt(h.substring(15).trim()); } catch (NumberFormatException ignored) {}
                } else if (low.startsWith("transfer-encoding:") && low.contains("chunked")) chunked = true;
                else if (low.startsWith("expect:") && low.contains("100")) expect = true;
            }
            if (length > MAX_BYTES) { answer(os, 413, "too big\n"); return; }
            if (expect) { os.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); os.flush(); }

            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

            if ((method.equals("PUT") || method.equals("POST")) && path.startsWith("/r")) {
                byte[] body;
                if (chunked) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    while (true) {
                        String line = readLine(in);
                        if (line == null) break;
                        int semi = line.indexOf(';');
                        int n = Integer.parseInt((semi >= 0 ? line.substring(0, semi) : line).trim(), 16);
                        if (n == 0) break;
                        if (b.size() + n > MAX_BYTES) { answer(os, 413, "too big\n"); return; }
                        b.write(readN(in, n));
                        readLine(in);
                    }
                    body = b.toByteArray();
                } else if (length >= 0) {
                    body = readN(in, length);
                } else {
                    answer(os, 411, "length required\n");
                    return;
                }
                String name = path.length() > 3 ? path.substring(3) : "report";
                done(name, body);
                answer(os, 200, "ok " + body.length + "\n");
            } else if (method.equals("GET") && (path.equals("/g") || path.startsWith("/g/"))) {
                Map<String, String> p = new HashMap<>();
                for (String kv : query.split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) p.put(kv.substring(0, e), kv.substring(e + 1));
                }
                if (path.startsWith("/g/")) {      // /g/이름/번호/16진수[/end] — 워치 셸에서 ?와 &를 피하려는 꼴
                    String[] seg = path.substring(3).split("/");
                    if (seg.length >= 3) { p.put("n", seg[0]); p.put("i", seg[1]); p.put("d", seg[2]); }
                    if (seg.length >= 4 && seg[3].equals("end")) p.put("end", "1");
                }
                String name = p.containsKey("n") ? p.get("n") : "report";
                int index = 0;
                try { index = Integer.parseInt(p.containsKey("i") ? p.get("i") : "0"); } catch (NumberFormatException ignored) {}
                byte[] piece = unhex(p.containsKey("d") ? p.get("d") : "");
                int total;
                byte[] all = null;
                synchronized (parts) {
                    ByteArrayOutputStream b = parts.get(name);
                    if (b == null || index == 0) { b = new ByteArrayOutputStream(); parts.put(name, b); }
                    if (b.size() + piece.length <= MAX_BYTES) b.write(piece, 0, piece.length);
                    total = b.size();
                    if (p.containsKey("end")) { all = b.toByteArray(); parts.remove(name); }
                }
                if (all != null) done(name, all);
                answer(os, 200, "ok " + total + "\n");
            } else if (method.equals("GET") && path.startsWith("/do/")) {
                Command c = command;
                String result;
                try { result = c == null ? "err no handler" : c.run(path.substring(4)); }
                catch (RuntimeException e) { result = "err " + e; }
                answer(os, 200, result + "\n");
            } else if (method.equals("GET") && path.equals("/ping")) {
                answer(os, 200, "ok 0\n");
            } else {
                answer(os, 404, "not found\n");
            }
        } catch (IOException | RuntimeException e) {
            Sink k = sink;
            if (k != null) k.log("워치 보고: 받다가 실패 (" + e.getMessage() + ")");
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] unhex(String d) {
        int n = d.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(d.charAt(2 * i), 16), lo = Character.digit(d.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("16진수가 아님");
            out[i] = (byte) (hi * 16 + lo);
        }
        return out;
    }

    private static void answer(OutputStream os, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: text/plain\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.ISO_8859_1));
        os.write(b);
        os.flush();
    }

    private void done(String name, byte[] body) {
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty()) name = "report";
        lastName = name;
        lastText = new String(body, StandardCharsets.UTF_8);
        synchronized (texts) { texts.remove(name); texts.put(name, lastText); }
        File d = dir;
        boolean saved = false;
        if (d != null) {
            try {
                File folder = new File(d, "watchreport");
                folder.mkdirs();
                try (FileOutputStream f = new FileOutputStream(new File(folder, name))) { f.write(body); }
                saved = true;
            } catch (IOException ignored) {}
        }
        Sink k = sink;
        if (k != null) k.log("워치 보고 받음: " + name + " " + body.length + "바이트" + (saved ? "" : " (파일 저장 안 됨)")
                + " — '복사'를 누르면 기록 뒤에 함께 복사됩니다");
    }
}
