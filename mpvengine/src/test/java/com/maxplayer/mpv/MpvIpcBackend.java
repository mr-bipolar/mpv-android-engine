package com.maxplayer.mpv;

import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DESKTOP TEST BACKEND — NOT part of the shipped Android library.
 *
 * Drives a real {@code mpv} process (libmpv 0.37 on this machine) through mpv's JSON IPC so the real
 * MpvPlayer code can be exercised against a real mpv engine. It is NOT the Android JNI path: differences
 * are listed in docs/VERIFICATION.md (typed IPC values vs JNI conversions, options applied after the core
 * is running, end-file reason delivered via IPC).
 */
final class MpvIpcBackend implements MpvBackend {
    private Process proc; private SocketChannel ch; private OutputStream out; private Thread reader;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> observed = new ConcurrentHashMap<>();
    private final Map<Integer, String> obsNames = new ConcurrentHashMap<>();
    private volatile EventSink sink;
    private Path sockPath;
    final boolean deliverEndReason;
    final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String> extraArgs = new ArrayList<>();
    private int nextObsId = 1;
    // Events must NOT be dispatched on the reader thread: handlers call back into get_property, which needs the reader to deliver the reply.
    private final java.util.concurrent.ExecutorService events = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ipc-events"); t.setDaemon(true); return t; });

    MpvIpcBackend(boolean deliverEndReason) { this.deliverEndReason = deliverEndReason; }

    @Override public void create() {
        try {
            sockPath = Files.createTempFile("mpvipc", ".sock"); Files.deleteIfExists(sockPath);
            List<String> cmd = new ArrayList<>(List.of("mpv", "--no-config", "--idle=yes", "--no-terminal", "--vo=null", "--ao=null",
                    "--input-ipc-server=" + sockPath, "--msg-level=all=no"));
            cmd.addAll(extraArgs);
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            for (int i = 0; i < 100 && !Files.exists(sockPath); i++) Thread.sleep(50);
            ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(sockPath));
            out = Channels.newOutputStream(ch);
            BufferedReader br = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            reader = new Thread(() -> readLoop(br), "ipc-reader"); reader.setDaemon(true); reader.start();
        } catch (Exception e) { throw new MpvException("cannot start desktop mpv", e); }
    }

    @Override public void initialize() { send(List.of("request_log_messages", "warn"), false); }

    @Override public void destroy() {
        events.shutdown();
        try { send(List.of("quit"), false); } catch (Exception ignored) { }
        try { if (proc != null && !proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (InterruptedException ignored) { }
        try { if (ch != null) ch.close(); } catch (IOException ignored) { }
        try { if (sockPath != null) Files.deleteIfExists(sockPath); } catch (IOException ignored) { }
    }

    // ---- IPC plumbing
    private Map<String, Object> send(List<?> command, boolean wait) {
        int id = ids.getAndIncrement();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        StringBuilder b = new StringBuilder("{\"command\":[");
        for (int i = 0; i < command.size(); i++) { if (i > 0) b.append(','); b.append(json(command.get(i))); }
        b.append("],\"request_id\":").append(id).append("}\n");
        try { synchronized (this) { out.write(b.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); } }
        catch (IOException e) { pending.remove(id); return null; }
        try { return f.get(5, TimeUnit.SECONDS); } catch (Exception e) { pending.remove(id); return null; }
    }

    static String json(Object o) {
        if (o instanceof Boolean || o instanceof Number) return o.toString();
        String s = String.valueOf(o); StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) { case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break; case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
        }
        return b.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private void readLoop(BufferedReader br) {
        try {
            String line;
            while ((line = br.readLine()) != null) {
                Map<String, Object> m = MiniJson.parseObject(line);
                if (m == null) continue;
                if (m.containsKey("request_id")) {
                    CompletableFuture<Map<String, Object>> f = pending.remove(((Double) m.get("request_id")).intValue());
                    if (f != null) f.complete(m);
                } else if (m.containsKey("event")) { try { events.execute(() -> dispatch(m)); } catch (java.util.concurrent.RejectedExecutionException closing) { return; } }
            }
        } catch (IOException ignored) { }
    }

    private void dispatch(Map<String, Object> m) {
        EventSink s = sink; if (s == null) return;
        String ev = (String) m.get("event");
        switch (ev) {
            case "property-change": {
                String name = (String) m.get("name"); Integer fmt = observed.get(name);
                Object data = m.get("data");
                if (fmt == null) return;
                if (!m.containsKey("data") || data == null || fmt == Mpv.Format.NONE) { s.onPropertyChange(name); return; }
                if (fmt == Mpv.Format.FLAG && data instanceof Boolean) s.onPropertyChange(name, (Boolean) data);
                else if (fmt == Mpv.Format.INT64 && data instanceof Double) s.onPropertyChange(name, ((Double) data).longValue());
                else if (fmt == Mpv.Format.DOUBLE && data instanceof Double) s.onPropertyChange(name, (Double) data);
                else if (fmt == Mpv.Format.STRING) s.onPropertyChange(name, String.valueOf(data));
                else s.onPropertyChange(name);
                break;
            }
            case "start-file": s.onEvent(Mpv.Event.START_FILE); break;
            case "end-file": {
                if (deliverEndReason) {
                    String r = String.valueOf(m.get("reason")); int reason;
                    switch (r) { case "eof": reason = 0; break; case "stop": reason = 2; break; case "quit": reason = 3; break;
                        case "error": reason = 4; break; case "redirect": reason = 5; break; default: reason = 4; }
                    int err = 0; String fe = (String) m.get("file_error");
                    if (fe != null) err = fe.contains("loading failed") ? -13 : fe.contains("unknown format") ? -17 : -1;
                    s.onEndFile(reason, err);
                }
                s.onEvent(Mpv.Event.END_FILE); break;
            }
            case "file-loaded": s.onEvent(Mpv.Event.FILE_LOADED); break;
            case "playback-restart": s.onEvent(Mpv.Event.PLAYBACK_RESTART); break;
            case "video-reconfig": s.onEvent(Mpv.Event.VIDEO_RECONFIG); break;
            case "audio-reconfig": s.onEvent(Mpv.Event.AUDIO_RECONFIG); break;
            case "seek": s.onEvent(Mpv.Event.SEEK); break;
            case "log-message": {
                String lv = String.valueOf(m.get("level")); int level = lv.equals("fatal") ? 10 : lv.equals("error") ? 20 : lv.equals("warn") ? 30 : lv.equals("info") ? 40 : 50;
                s.onLogMessage(String.valueOf(m.get("prefix")), level, String.valueOf(m.get("text")));
                break;
            }
            default: break;
        }
    }

    // ---- MpvBackend
    @Override public int setOptionString(String n, String v) {
        Map<String, Object> r = n.equals("profile") ? send(List.of("apply-profile", v), true) : send(List.of("set_property", n, v), true);
        String e = r == null ? "no reply" : String.valueOf(r.get("error"));
        if (e.equals("success")) return 0;
        errors.add("option " + n + "=" + v + ": " + e);
        if (e.contains("not found")) return Mpv.Err.OPTION_NOT_FOUND;
        if (e.contains("invalid parameter") || e.contains("error running")) return Mpv.Err.OPTION_ERROR;
        return Mpv.Err.OPTION_FORMAT;
    }
    @Override public void command(String... a) {
        Map<String, Object> r = send(List.of((Object[]) a), true);
        if (r == null || !"success".equals(r.get("error"))) errors.add("command " + String.join(" ", a) + ": " + (r == null ? "no reply" : r.get("error")));
    }
    private Object get(String n) { Map<String, Object> r = send(List.of("get_property", n), true); return r != null && "success".equals(r.get("error")) ? r.get("data") : null; }
    @Override public Integer getPropertyInt(String n) { Object o = get(n); return o instanceof Double ? Integer.valueOf(((Double) o).intValue()) : null; }
    @Override public Double getPropertyDouble(String n) { Object o = get(n); return o instanceof Double ? (Double) o : null; }
    @Override public Boolean getPropertyBoolean(String n) { Object o = get(n); return o instanceof Boolean ? (Boolean) o : null; }
    @Override public String getPropertyString(String n) {
        Object o = get(n);
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o ? "yes" : "no";
        if (o instanceof Double) return String.format(java.util.Locale.ROOT, "%f", (Double) o);
        if (o instanceof String) return (String) o;
        // node (map/list): re-serialise as JSON text, like libmpv does for MPV_FORMAT_STRING
        Map<String, Object> r = send(List.of("get_property", n), true);
        return toJson(r.get("data"));
    }
    @SuppressWarnings("unchecked")
    static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof Map) { StringBuilder b = new StringBuilder("{"); boolean f = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) { if (!f) b.append(','); f = false; b.append(json(e.getKey())).append(':').append(toJson(e.getValue())); }
            return b.append('}').toString(); }
        if (o instanceof List) { StringBuilder b = new StringBuilder("["); boolean f = true;
            for (Object e : (List<Object>) o) { if (!f) b.append(','); f = false; b.append(toJson(e)); } return b.append(']').toString(); }
        return json(o);
    }
    private void set(String n, Object v) {
        Map<String, Object> r = send(List.of("set_property", n, v), true);
        if (r == null || !"success".equals(r.get("error"))) errors.add("set " + n + "=" + v + ": " + (r == null ? "no reply" : r.get("error")));
    }
    @Override public void setPropertyInt(String n, int v) { set(n, v); }
    @Override public void setPropertyDouble(String n, double v) { set(n, v); }
    @Override public void setPropertyBoolean(String n, boolean v) { set(n, v); }
    @Override public void setPropertyString(String n, String v) { set(n, v); }
    @Override public void observeProperty(String n, int format) {
        observed.put(n, format);
        send(List.of("observe_property", nextObsId++, n), true);
    }
    @Override public void attachSurface(Surface s) { }
    @Override public void detachSurface() { }
    @Override public void setEventSink(EventSink s) { sink = s; }
}
