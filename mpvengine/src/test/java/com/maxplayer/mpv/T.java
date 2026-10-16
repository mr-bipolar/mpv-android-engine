package com.maxplayer.mpv;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Minimal test harness (no JUnit available offline): named tests, assertions, polling waits. */
final class T {
    interface Body { void run() throws Exception; }
    static final Executor DIRECT = Runnable::run;
    static final List<String> failures = new ArrayList<>();
    static int passed, skipped;
    static String group = "";

    static void test(String name, Body b) {
        long t0 = System.currentTimeMillis();
        try { b.run(); passed++; System.out.printf("  PASS  %-62s %5d ms%n", name, System.currentTimeMillis() - t0); }
        catch (Skip s) { skipped++; System.out.printf("  SKIP  %-62s %s%n", name, s.getMessage()); }
        catch (Throwable e) {
            String m = group + " / " + name + ": " + e;
            failures.add(m);
            System.out.printf("  FAIL  %-62s %s%n", name, e);
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) System.out.println("          at " + st[i]);
        }
    }
    static final class Skip extends RuntimeException { Skip(String m) { super(m); } }

    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }
    static void eq(Object exp, Object act, String what) {
        if (exp == null ? act != null : !exp.equals(act)) throw new AssertionError(what + ": expected <" + exp + "> but was <" + act + ">");
    }
    static void near(double exp, double act, double tol, String what) {
        if (Double.isNaN(act) || Math.abs(exp - act) > tol) throw new AssertionError(what + ": expected ~" + exp + " (+/-" + tol + ") but was " + act);
    }
    static void throwsIae(String what, Body b) {
        try { b.run(); } catch (IllegalArgumentException e) { return; } catch (Exception e) { throw new AssertionError(what + ": wrong exception " + e); }
        throw new AssertionError(what + ": expected IllegalArgumentException");
    }
    static boolean waitFor(java.util.function.BooleanSupplier c, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try { if (c.getAsBoolean()) return true; } catch (RuntimeException ignored) { }
            try { Thread.sleep(25); } catch (InterruptedException e) { return false; }
        }
        try { return c.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }
    static void await(java.util.function.BooleanSupplier c, long timeoutMs, String what) {
        if (!waitFor(c, timeoutMs)) throw new AssertionError("timed out after " + timeoutMs + " ms waiting for " + what);
    }

    /** Records listener events for assertions. */
    static final class Rec implements MpvPlayerListener {
        final List<PlaybackState> states = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<MpvError> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> retries = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> rejected = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<LiveStatus> live = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Integer> playlistPos = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<EndReason> ends = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile MediaInfo loaded; volatile int completed; volatile int tracksChanged;
        @Override public void onStateChanged(PlaybackState n, PlaybackState o) { states.add(n); }
        @Override public void onMediaLoaded(MediaInfo i) { loaded = i; }
        @Override public void onError(MpvError e) { errors.add(e); }
        @Override public void onRetryScheduled(int a, int m, long d) { retries.add(a + "/" + m + "@" + d); }
        @Override public void onOptionRejected(String n, String v, int c, String r) { rejected.add(n + "=" + v + ":" + c); }
        @Override public void onLiveStatusChanged(LiveStatus s, String r) { live.add(s); }
        @Override public void onPlaylistPositionChanged(int p, int c) { if (playlistPos.isEmpty() || playlistPos.get(playlistPos.size() - 1) != p) playlistPos.add(p); }
        @Override public void onPlaybackCompleted() { completed++; }
        @Override public void onEntryEnded(EndReason r, MpvError e) { ends.add(r); }
        @Override public void onTracksChanged(List<AudioTrackInfo> a, List<SubtitleTrackInfo> s) { tracksChanged++; }
        @Override public void onLog(int level, String prefix, String text) { logs.add(prefix + ": " + text.trim()); }
    }
}
