package com.maxplayer.mpv;

import static com.maxplayer.mpv.T.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Runs the REAL MpvPlayer against a REAL desktop mpv (libmpv 0.37 + FFmpeg 6.1) through MpvIpcBackend.
 * It proves property names, units, command syntax and the engine's logic against a real engine. It does NOT
 * prove anything about the Android JNI layer, MediaCodec, GPU rendering or Android audio output.
 */
final class IntegrationTests {
    static final Path DIR = Path.of("/tmp/it");
    static Process liveFfmpeg; static HttpServer server; static int port;

    static MpvPlayerConfig.Builder cfg() { return MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput("null"); }

    static final class Ctx { MpvPlayer p; MpvIpcBackend b; T.Rec rec = new T.Rec(); }
    static Ctx start(MpvPlayerConfig c, boolean exactEnd) {
        Ctx x = new Ctx(); x.b = new MpvIpcBackend(exactEnd); x.p = new MpvPlayer(x.b, c, DIRECT, null); x.p.addListener(x.rec); x.p.initialize(); return x;
    }
    static void stop(Ctx x) { x.p.release(); }

    static void run() throws Exception {
        if (!haveTool("mpv") || !haveTool("ffmpeg")) { T.test("integration environment", () -> { throw new Skip("mpv/ffmpeg not on PATH"); }); return; }
        Files.createDirectories(DIR);
        makeAssets();
        startLiveSource();
        try {
            group = "integration: VOD";
            test("local mp4: load, tracks, position, seek, pause, speed, stats, EOF -> ENDED", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p;
                check(x.rec.rejected.isEmpty(), "no option rejected on a real mpv: " + x.rec.rejected + " " + x.b.errors);
                eq(0, p.getOptionReport().size(), "option report empty"); check(p.getMpvVersion().startsWith("mpv 0."), p.getMpvVersion());
                p.load("/tmp/t_vod.mp4");
                await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING");
                MediaInfo mi = x.rec.loaded; check(mi != null, "onMediaLoaded"); near(6.0, mi.getDurationSeconds(), 0.2, "duration"); check(mi.isSeekable(), "seekable");
                eq(LiveStatus.VOD, p.getLiveStatus(), "VOD"); check(!p.isNetworkStream(), "local is not network");
                eq(1, p.getAudioTracks().size(), "audio tracks"); check(mi.hasVideo(), "video track"); eq("h264", mi.getVideoTracks().get(0).getCodec(), "codec"); eq(320, mi.getVideoTracks().get(0).getWidth(), "width");
                check(mi.getFileFormat().contains("mp4"), "format " + mi.getFileFormat()); check(mi.getMetadata().containsKey("encoder"), "metadata " + mi.getMetadata());
                await(() -> p.getPositionSeconds() > 0.5, 5000, "position advances"); check(p.seekTo(4.0), "seek accepted");
                await(() -> p.getPositionSeconds() >= 3.9, 5000, "position after seek"); p.pause(); await(() -> p.getState() == PlaybackState.PAUSED, 3000, "PAUSED");
                p.setSpeed(1.5); await(() -> Math.abs(p.getSpeed() - 1.5) < 1e-6, 3000, "speed event"); p.setSpeed(1.0);
                p.frameStep(); await(() -> p.getState() == PlaybackState.PAUSED, 3000, "paused after frame-step");
                VideoInfo vi = p.getVideoInfo(); eq(320, vi.getSourceWidth(), "video-params/w"); near(4.0 / 3, vi.getSourceAspect(), 0.01, "source aspect");
                PlaybackStats st = p.getStats(); check(st != null && "no".equals(st.hardwareDecoder), "software decode on desktop: " + st);
                BufferingInfo bi = p.getBufferingInfo(); check(bi != null && !bi.isStalled(), "buffering info " + bi);
                p.setVolume(60); await(() -> p.getVolume() == 60, 3000, "volume event"); p.setMuted(true); await(p::isMuted, 3000, "mute event");
                p.setMuted(false); p.play();
                await(() -> x.rec.completed == 1, 10000, "onPlaybackCompleted"); eq(PlaybackState.ENDED, p.getState(), "ENDED at EOF with keep-open");
                p.play(); await(() -> p.getState() == PlaybackState.PLAYING, 5000, "restart after ENDED"); check(p.getPositionSeconds() < 3, "restarted near 0: " + p.getPositionSeconds());
                p.stop(); await(() -> p.getState() == PlaybackState.IDLE, 5000, "IDLE after stop"); check(x.rec.errors.isEmpty(), "no errors: " + x.rec.errors);
                stop(x);
            });
            test("every property/option name the engine uses is accepted by a real mpv (no IPC errors)", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p; p.load("/tmp/t_vod.mp4"); await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING");
                p.setSubtitleSize(40); p.setSubtitleScale(1.1); p.setSubtitlePosition(95); p.setSubtitleDelay(0.2); p.setAudioDelay(0.1); p.setDeinterlace(true); p.setDeinterlace(false);
                p.setAspectRatio("16:9"); p.setVideoScaleMode(VideoScaleMode.FILL); p.setVideoScaleMode(VideoScaleMode.STRETCH); p.setVideoScaleMode(VideoScaleMode.ORIGINAL);
                p.setZoom(1.5); p.setPan(0.1, 0.1); p.setCrop(10, 10, 200, 150); p.clearCrop(); p.setRotation(90); p.resetVideoScaling();
                p.setSubtitlesVisible(false); p.setSubtitlesVisible(true); p.disableSubtitles(); p.disableAudio();
                for (String n : new String[]{"video-crop", "sub-pos", "video-rotate", "demuxer-lavf-o", "cache-pause-wait"}) check(p.isOptionSupported(n), n + " supported");
                check(!p.isOptionSupported("definitely-not-an-option"), "unknown option detected");
                check(x.b.errors.isEmpty(), "mpv rejected something: " + x.b.errors); stop(x);
            });
            test("geometry settings are really applied (read back from mpv)", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p; p.load("/tmp/t_vod.mp4"); await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING");
                p.setAspectRatio("16:9"); near(16.0 / 9, Double.parseDouble(x.b.getPropertyString("video-aspect-override")), 1e-3, "aspect override");
                p.resetAspectRatio(); String def = x.b.getPropertyString("option-info/video-aspect-override/default-value");
                near(Double.parseDouble(def), Double.parseDouble(x.b.getPropertyString("video-aspect-override")), 1e-6, "aspect back to build default (" + def + ")");
                p.setVideoScaleMode(VideoScaleMode.FILL); near(1.0, x.b.getPropertyDouble("panscan"), 1e-9, "panscan"); eq("yes", x.b.getPropertyString("keepaspect"), "keepaspect");
                p.setVideoScaleMode(VideoScaleMode.STRETCH); eq("no", x.b.getPropertyString("keepaspect"), "stretch");
                p.setVideoScaleMode(VideoScaleMode.FIT); near(0.0, x.b.getPropertyDouble("panscan"), 1e-9, "fit panscan");
                p.setZoom(2); near(1.0, x.b.getPropertyDouble("video-zoom"), 1e-9, "zoom log2"); p.setRotation(270); near(270, Double.parseDouble(x.b.getPropertyString("video-rotate")), 1e-9, "rotate");
                p.setCrop(16, 8, 200, 100); eq("200x100+16+8", x.b.getPropertyString("video-crop"), "crop");
                p.resetVideoScaling(); near(0, x.b.getPropertyDouble("video-zoom"), 1e-9, "zoom reset"); near(0, Double.parseDouble(x.b.getPropertyString("video-rotate")), 1e-9, "rotation reset"); eq("", x.b.getPropertyString("video-crop"), "crop reset");
                p.setSubtitleDelay(0.5); near(0.5, x.b.getPropertyDouble("sub-delay"), 1e-9, "sub-delay"); p.setSubtitleSize(33); near(33, x.b.getPropertyDouble("sub-font-size"), 1e-9, "sub-font-size");
                p.setSubtitlePosition(80); near(80, x.b.getPropertyDouble("sub-pos"), 1e-9, "sub-pos"); p.setAudioDelay(-0.2); near(-0.2, x.b.getPropertyDouble("audio-delay"), 1e-9, "audio-delay");
                try { p.setSubtitlePosition(151); throw new AssertionError("pos 151 accepted"); } catch (IllegalArgumentException ok) { }
                stop(x);
            });

            group = "integration: tracks & subtitles";
            test("audio/subtitle tracks, selection, external subtitle (mkv with 2 audio + 1 sub)", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p; p.load(DIR + "/multi.mkv"); await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING");
                List<AudioTrackInfo> a = p.getAudioTracks(); eq(2, a.size(), "2 audio"); eq("eng", a.get(0).getLanguage(), "lang 1"); eq("spa", a.get(1).getLanguage(), "lang 2"); eq("Spanish", a.get(1).getTitle(), "title");
                eq(2, a.get(0).getChannels(), "stereo"); check(a.get(0).isSelected() && !a.get(1).isSelected(), "first selected by default");
                int before = x.rec.tracksChanged; p.selectAudioTrack(a.get(1).getId());
                await(() -> p.getAudioTracks().get(1).isSelected() && !p.getAudioTracks().get(0).isSelected(), 4000, "audio switch reflected"); check(x.rec.tracksChanged > before, "onTracksChanged fired");
                List<SubtitleTrackInfo> s = p.getSubtitleTracks(); eq(1, s.size(), "1 embedded sub"); eq("eng", s.get(0).getLanguage(), "sub lang"); check(!s.get(0).isExternal(), "embedded");
                p.selectSubtitleTrack(s.get(0).getId()); await(() -> p.getSubtitleTracks().get(0).isSelected(), 4000, "sub selected");
                p.disableSubtitles(); await(() -> !p.getSubtitleTracks().get(0).isSelected(), 4000, "sub deselected");
                p.loadExternalSubtitle(DIR + "/ext.srt"); await(() -> p.getSubtitleTracks().size() == 2, 5000, "external track listed");
                SubtitleTrackInfo ext = p.getSubtitleTracks().get(1); check(ext.isExternal() && ext.isSelected(), "external selected: " + ext); check(ext.getExternalFilename().endsWith("ext.srt"), ext.getExternalFilename());
                p.addExternalAudio(DIR + "/extra.mka"); await(() -> p.getAudioTracks().size() == 3, 5000, "external audio listed"); check(p.getAudioTracks().get(2).isExternal(), "external audio");
                List<MpvPlayer.AudioDevice> devs = p.getAudioDevices(); check(!devs.isEmpty() && devs.get(0).id.equals("auto"), "audio devices from JSON: " + devs);
                stop(x);
            });

            group = "integration: live / HLS";
            test("live HLS: UNKNOWN->LIVE via duration growth, not seekable, live edge, jumpToLive", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p; p.load(liveUrl()); await(() -> p.getState() == PlaybackState.PLAYING, 15000, "PLAYING");
                check(p.isNetworkStream(), "network"); check(!p.isSeekable(), "live HLS reports seekable=no"); check(x.rec.loaded.getFileFormat().contains("hls"), x.rec.loaded.getFileFormat());
                await(() -> p.getLiveStatus() == LiveStatus.LIVE, 20000, "LIVE detection (reason: " + p.getLiveStatusReason() + ")");
                check(p.getLiveStatusReason().contains("growing"), p.getLiveStatusReason()); check(!Double.isNaN(p.getLiveEdgePositionSeconds()), "live edge"); check(p.getLiveLatencySeconds() >= 0, "latency");
                check(!p.seekTo(1), "seek refused on live"); check(x.rec.live.contains(LiveStatus.LIVE), "listener told: " + x.rec.live);
                System.out.printf("          [info] default profile: latency %.1fs, live edge %.1fs, duration %.1fs%n", p.getLiveLatencySeconds(), p.getLiveEdgePositionSeconds(), p.getDurationSeconds());
                int loads = x.rec.states.size(); check(p.jumpToLive(), "jumpToLive"); await(() -> p.getState() == PlaybackState.PLAYING, 15000, "PLAYING after jump"); stop(x);
            });
            test("VOD HLS (ENDLIST): classified VOD, seekable", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p; p.load("http://127.0.0.1:" + port + "/vod/vod.m3u8");
                await(() -> p.getState() == PlaybackState.PLAYING, 15000, "PLAYING"); eq(LiveStatus.VOD, p.getLiveStatus(), "VOD: " + p.getLiveStatusReason()); check(p.isSeekable(), "seekable");
                near(20, p.getDurationSeconds(), 0.5, "duration"); check(p.seekTo(10), "seek"); await(() -> p.getPositionSeconds() >= 9.5, 8000, "seek landed"); stop(x);
            });
            test("host hint LIVE wins immediately on a stream mpv cannot classify", () -> {
                Ctx x = start(cfg().liveHint(LiveStatus.LIVE).build(), true); MpvPlayer p = x.p; p.load("/tmp/t_vod.mp4");
                await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING"); check(p.isLive(), "hinted"); check(p.getLiveStatusReason().contains("hint"), p.getLiveStatusReason()); stop(x);
            });
            test("buffering profiles measured on simulated live HLS (informational + sanity)", () -> {
                double[] lat = new double[3]; MpvPlayerConfig.BufferingProfile[] ps = MpvPlayerConfig.BufferingProfile.values();
                for (int i = 0; i < ps.length; i++) {
                    Ctx x = start(cfg().profile(ps[i]).build(), true); MpvPlayer p = x.p; p.load(liveUrl());
                    await(() -> p.getState() == PlaybackState.PLAYING, 20000, ps[i] + " PLAYING"); Thread.sleep(6000);
                    double e = p.getBufferingInfo().getBufferedAheadSeconds(); lat[i] = e; check(!Double.isNaN(e), "buffered ahead known");
                    System.out.printf("          [info] %-12s buffered-ahead %.1fs  (cache=%s, cache-secs=%s)%n", ps[i], e, x.b.getPropertyString("cache"), x.b.getPropertyString("cache-secs"));
                    check(x.rec.errors.isEmpty(), ps[i] + " produced errors: " + x.rec.errors); stop(x);
                }
                check(lat[2] <= lat[0] + 0.5, "LOW_LATENCY (" + lat[2] + ") should not buffer more than STABLE (" + lat[0] + ")");
            });

            group = "integration: errors, retry, recovery";
            test("unreachable network stream: retries with backoff then ERROR (exact end reason)", () -> errorFlow(true));
            test("unreachable network stream: same outcome with INFERRED end reason (stock native layer)", () -> errorFlow(false));
            test("missing local file: ERROR immediately, no retry", () -> {
                Ctx x = start(cfg().retry(3, 100, 1).build(), false); MpvPlayer p = x.p; p.load("/tmp/it/does-not-exist.mp4");
                await(() -> p.getState() == PlaybackState.ERROR, 8000, "ERROR"); check(x.rec.retries.isEmpty(), "no retry"); eq(1, x.rec.errors.size(), "one error"); stop(x);
            });
            test("custom headers/user-agent/referrer reach the server (per-load and config-level quoting)", () -> {
                List<Map<String, List<String>>> seen = Collections.synchronizedList(new ArrayList<>());
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                s.createContext("/", ex -> { seen.add(new HashMap<>(ex.getRequestHeaders())); ex.sendResponseHeaders(404, -1); ex.close(); }); s.start();
                String u = "http://127.0.0.1:" + s.getAddress().getPort() + "/x.mp4";
                Ctx x = start(cfg().retry(0, 100, 1).httpHeader("X-Cfg", "one").httpHeader("X-Cfg2", "two").userAgent("CfgAgent/1").build(), false);
                x.p.load(u, LoadOptions.builder().userAgent("MaxTest/1.0, special=chars").referrer("http://ref.example/").header("X-Test", "a;b").header("X-Plain", "z").build());
                await(() -> x.p.getState() == PlaybackState.ERROR, 8000, "ERROR (404)"); s.stop(0);
                check(!seen.isEmpty(), "server saw a request"); Map<String, List<String>> h = lower(seen.get(0));
                eq("MaxTest/1.0, special=chars", h.get("user-agent").get(0), "per-load user agent overrides config"); eq("http://ref.example/", h.get("referer").get(0), "referrer");
                eq("a;b", h.get("x-test").get(0), "per-load header delivered"); eq("z", h.get("x-plain").get(0), "plain header");
                eq("one", h.get("x-cfg").get(0), "config-level header merged into the per-load list"); eq("two", h.get("x-cfg2").get(0), "config-level header 2 merged");
                stop(x);
                Ctx y = start(cfg().retry(0, 100, 1).httpHeader("X-Cfg", "one").httpHeader("X-Cfg2", "two").userAgent("CfgAgent/1").build(), false);
                HttpServer s2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); seen.clear();
                s2.createContext("/", ex -> { seen.add(new HashMap<>(ex.getRequestHeaders())); ex.sendResponseHeaders(404, -1); ex.close(); }); s2.start();
                y.p.load("http://127.0.0.1:" + s2.getAddress().getPort() + "/y.mp4"); await(() -> y.p.getState() == PlaybackState.ERROR, 8000, "ERROR"); s2.stop(0);
                Map<String, List<String>> h2 = lower(seen.get(0)); eq("one", h2.get("x-cfg").get(0), "config header 1"); eq("two", h2.get("x-cfg2").get(0), "config header 2"); eq("CfgAgent/1", h2.get("user-agent").get(0), "config UA"); stop(y);
            });
            test("recovery: server goes away for ~5 s while playing live HLS, playback resumes", () -> {
                Ctx x = start(cfg().reconnect(true, 20).retry(6, 500, 1.5).networkTimeoutSeconds(5).build(), false); MpvPlayer p = x.p; p.load(liveUrl());
                await(() -> p.getState() == PlaybackState.PLAYING, 15000, "PLAYING"); Thread.sleep(3000);
                double d0 = p.getDurationSeconds(); server.stop(0); System.out.println("          [info] server DOWN"); Thread.sleep(5000);
                server = startServer(port); System.out.println("          [info] server UP again; states so far: " + x.rec.states + " retries: " + x.rec.retries + " errors: " + x.rec.errors.size());
                boolean ok = waitFor(() -> p.getState() == PlaybackState.PLAYING && p.getDurationSeconds() > d0 + 6, 40000);
                System.out.println("          [info] final state " + p.getState() + ", duration " + d0 + " -> " + p.getDurationSeconds() + ", states " + x.rec.states + ", retries " + x.rec.retries);
                check(ok, "playback did not recover (state " + p.getState() + ")"); stop(x);
            });

            group = "integration: playlists";
            test("M3U: load, auto-advance on EOF, previous/next, failed entry skipped", () -> {
                Ctx x = start(cfg().retry(0, 100, 1).build(), true); MpvPlayer p = x.p; p.setSkipFailedEntries(true);
                String m3u = "#EXTM3U\n#EXTINF:2,One\n/tmp/it/c1.mp4\n#EXTINF:2,Broken\n/tmp/it/missing.mp4\n#EXTINF:2,Three\n/tmp/it/c2.mp4\n";
                M3uParser.Result r = p.getPlaylist().loadM3u(m3u, new M3uParser.Options().allowLocal(true), true); eq(3, r.getEntries().size(), "entries");
                await(() -> x.rec.playlistPos.contains(2) && x.rec.completed >= 1, 20000, "reached entry 2 after skipping the broken one: " + x.rec.playlistPos);
                eq(Arrays.asList(0, 1, 2), x.rec.playlistPos.subList(Math.max(0, x.rec.playlistPos.indexOf(0)), x.rec.playlistPos.size()).subList(0, 3), "order 0,1,2");
                await(() -> p.getState() == PlaybackState.ENDED, 15000, "ENDED at end of playlist"); check(x.rec.errors.size() == 1, "exactly the broken entry errored: " + x.rec.errors);
                // previous()/next() on a healthy list
                p.getPlaylist().setEntries(Arrays.asList(PlaylistEntry.of("/tmp/it/c1.mp4"), PlaylistEntry.of("/tmp/it/c2.mp4")), 1);
                await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING entry 1"); check(p.previous(), "previous"); await(() -> p.getPlaylist().getPosition() == 0 && p.getState() == PlaybackState.PLAYING, 8000, "PLAYING entry 0");
                check(!p.previous(), "no previous at start"); check(p.next(), "next"); eq(1, p.getPlaylist().getPosition(), "position"); stop(x);
            });
            test("failed entry does not auto-skip unless asked (IPTV zapping must not jump channels)", () -> {
                Ctx x = start(cfg().retry(0, 100, 1).build(), true); MpvPlayer p = x.p;
                p.getPlaylist().setEntries(Arrays.asList(PlaylistEntry.of("/tmp/it/missing.mp4"), PlaylistEntry.of("/tmp/it/c1.mp4")), 0);
                await(() -> p.getState() == PlaybackState.ERROR, 8000, "ERROR"); Thread.sleep(1500); eq(PlaybackState.ERROR, p.getState(), "stays on the failed entry"); eq(0, p.getPlaylist().getPosition(), "position unchanged"); stop(x);
            });

            group = "integration: screenshot";
            test("screenshot-to-file with a null video output (desktop behaviour; Android needs a real surface)", () -> {
                Ctx x = start(cfg().build(), true); MpvPlayer p = x.p; p.load("/tmp/t_vod.mp4"); await(() -> p.getState() == PlaybackState.PLAYING, 8000, "PLAYING");
                File f = new File("/tmp/it/shot.jpg"); f.delete(); p.takeScreenshot(f.getPath(), false);
                boolean ok = waitFor(f::exists, 4000);
                if (!ok) throw new Skip("no file written with vo=null (" + x.b.errors + ") — real surface needed; not verified");
                check(f.length() > 500, "non-trivial image"); stop(x);
            });
        } finally { stopLiveSource(); }
    }

    private static void errorFlow(boolean exactEnd) throws Exception {
        Ctx x = start(cfg().retry(2, 200, 2.0).networkTimeoutSeconds(3).build(), exactEnd); MpvPlayer p = x.p;
        p.load("http://127.0.0.1:9/none.mp4");                                            // port 9: connection refused
        await(() -> p.getState() == PlaybackState.ERROR, 20000, "ERROR after retries");
        eq(3, x.rec.errors.size(), "3 errors (1 + 2 retries)"); eq(2, x.rec.retries.size(), "2 retries"); check(x.rec.retries.get(1).endsWith("@400"), "backoff " + x.rec.retries);
        check(!x.rec.errors.get(2).willRetry(), "last error not retried"); MpvError e = p.getLastError();
        System.out.println("          [info] exactEnd=" + exactEnd + " -> kind=" + e.getKind() + " code=" + e.getMpvErrorCode() + " msg='" + e.getMessage() + "' log=" + e.getLogLines());
        if (exactEnd) eq(MpvError.Kind.LOAD_FAILED, e.getKind(), "kind from mpv end-file error"); else check(e.getMpvErrorCode() == 0, "no code without native patch");
        check(!e.getLogLines().isEmpty(), "mpv error log lines captured for diagnostics"); stop(x);
    }

    // ---------------------------------------------------------------- assets / servers
    static String liveUrl() { return "http://127.0.0.1:" + port + "/live/live.m3u8"; }
    static Map<String, List<String>> lower(Map<String, List<String>> m) { Map<String, List<String>> o = new HashMap<>(); for (Map.Entry<String, List<String>> e : m.entrySet()) o.put(e.getKey().toLowerCase(), e.getValue()); return o; }
    static boolean haveTool(String n) { try { return new ProcessBuilder(n, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0; } catch (Exception e) { return false; } }
    static void sh(String... cmd) throws Exception { Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() != 0) throw new IOException("failed: " + String.join(" ", cmd)); }

    static void makeAssets() throws Exception {
        if (!Files.exists(Path.of("/tmp/t_vod.mp4")))
            sh("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=6:size=320x240:rate=25", "-f", "lavfi", "-i", "sine=duration=6", "-shortest", "-c:v", "libx264", "-c:a", "aac", "-y", "/tmp/t_vod.mp4");
        Files.writeString(DIR.resolve("ext.srt"), "1\n00:00:00,500 --> 00:00:02,000\nHello\n\n2\n00:00:02,500 --> 00:00:04,000\nWorld\n");
        Files.writeString(DIR.resolve("embedded.srt"), "1\n00:00:00,500 --> 00:00:02,000\nEmbedded\n");
        if (!Files.exists(DIR.resolve("multi.mkv")))
            sh("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=8:size=320x240:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:duration=8", "-f", "lavfi", "-i", "sine=frequency=880:duration=8",
                    "-i", DIR + "/embedded.srt", "-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3:s", "-c:v", "libx264", "-c:a", "aac", "-ac", "2", "-c:s", "srt",
                    "-metadata:s:a:0", "language=eng", "-metadata:s:a:0", "title=English", "-metadata:s:a:1", "language=spa", "-metadata:s:a:1", "title=Spanish", "-metadata:s:s:0", "language=eng", "-y", DIR + "/multi.mkv");
        if (!Files.exists(DIR.resolve("extra.mka"))) sh("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=660:duration=8", "-c:a", "aac", "-y", DIR + "/extra.mka");
        for (String n : new String[]{"c1", "c2"}) if (!Files.exists(DIR.resolve(n + ".mp4")))
            sh("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=25", "-f", "lavfi", "-i", "sine=duration=2", "-shortest", "-c:v", "libx264", "-c:a", "aac", "-y", DIR + "/" + n + ".mp4");
        Files.createDirectories(DIR.resolve("vod"));
        if (!Files.exists(DIR.resolve("vod/vod.m3u8")))
            sh("ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=20", "-f", "lavfi", "-i", "sine=duration=20", "-c:v", "libx264", "-preset", "ultrafast", "-g", "25", "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", DIR + "/vod/vod.m3u8");
    }

    static void startLiveSource() throws Exception {
        Path live = DIR.resolve("live"); Files.createDirectories(live);
        try (var s = Files.list(live)) { for (Path p : (Iterable<Path>) s::iterator) Files.deleteIfExists(p); }
        liveFfmpeg = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-f", "lavfi", "-i", "sine=frequency=440", "-c:v", "libx264", "-preset", "ultrafast", "-g", "25",
                "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_list_size", "6", "-hls_flags", "delete_segments+omit_endlist", live + "/live.m3u8").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        for (int i = 0; i < 100 && !Files.exists(live.resolve("live.m3u8")); i++) Thread.sleep(100);
        server = startServer(0); port = server.getAddress().getPort(); Thread.sleep(6000); // let a few segments accumulate
    }
    static void stopLiveSource() { if (liveFfmpeg != null) liveFfmpeg.destroyForcibly(); if (server != null) server.stop(0); }

    static HttpServer startServer(int p) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
        s.createContext("/", (HttpExchange ex) -> {
            Path f = DIR.resolve(ex.getRequestURI().getPath().substring(1)).normalize();
            if (!f.startsWith(DIR) || !Files.isRegularFile(f)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] b; try { b = Files.readAllBytes(f); } catch (IOException e) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.getResponseHeaders().add("Content-Type", f.toString().endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t");
            ex.sendResponseHeaders(200, b.length); try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        s.start(); return s;
    }
}
