package com.maxplayer.mpv;

import static com.maxplayer.mpv.T.*;

import java.util.*;

/** JVM-only tests: no native code, no mpv. Deterministic. */
final class UnitTests {

    static MpvPlayer player(FakeBackend fb, MpvPlayerConfig cfg, T.Rec rec) {
        MpvPlayer p = new MpvPlayer(fb, cfg, DIRECT, null);
        if (rec != null) p.addListener(rec);
        fb.props.put("mpv-version", "mpv 0.37.0"); fb.props.put("option-info/volume-max/max", "130.000000");
        fb.props.put("option-info/sub-pos/max", "150.000000");
        p.initialize();
        return p;
    }
    static MpvPlayerConfig nullVo() {
        return MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).retry(0, 100, 1.0).build();
    }
    /** Simulates a successful load of a VOD with 1 video + 1 audio track. */
    static void loadVod(FakeBackend fb, double dur) {
        fb.startFile();
        fb.props.put("path", "x"); fb.props.put("media-title", "t"); fb.props.put("file-format", "mov,mp4");
        fb.props.put("track-list/count", "2");
        fb.props.put("track-list/0/type", "video"); fb.props.put("track-list/0/id", "1"); fb.props.put("track-list/0/selected", "yes");
        fb.props.put("track-list/1/type", "audio"); fb.props.put("track-list/1/id", "1"); fb.props.put("track-list/1/lang", "eng"); fb.props.put("track-list/1/selected", "yes");
        fb.flag("seekable", true); fb.dbl("duration", dur);
        fb.fileLoaded();
    }

    static void run() {
        group = "config";
        test("defaults validate and produce sane mpv options", () -> {
            MpvPlayerConfig c = MpvPlayerConfig.defaults();
            Map<String, String> o = new LinkedHashMap<>();
            for (MpvPlayerConfig.Opt x : c.toMpvOptions()) o.put(x.name, x.value);
            eq("yes", o.get("keep-open"), "keep-open"); eq("auto", o.get("cache"), "cache");
            eq(Long.toString(48L * 1024 * 1024), o.get("demuxer-max-bytes"), "demuxer-max-bytes");
            eq("20", o.get("cache-secs"), "cache-secs"); eq("15", o.get("network-timeout"), "network-timeout");
            eq("gpu", o.get("vo"), "vo"); eq("android", o.get("gpu-context"), "gpu-context");
            eq("mediacodec,mediacodec-copy", o.get("hwdec"), "hwdec"); eq("max", o.get("hls-bitrate"), "hls-bitrate");
            check(o.get("stream-lavf-o").contains("reconnect=1"), "reconnect option present");
            check(!o.containsKey("demuxer-lavf-o"), "no lavf-o by default (HLS default start index)");
            for (String k : o.keySet()) check(!k.endsWith("-add"), "libmpv does not accept -add suffix: " + k);
        });
        test("validation reports every bad value", () -> {
            MpvPlayerConfig.Builder b = MpvPlayerConfig.builder().demuxerMaxBytes(2048L * 1024 * 1024).cacheSeconds(0)
                    .networkTimeoutSeconds(-1).defaultAspectRatio(0.0).subtitlePosition(200).defaultPlaybackSpeed(0)
                    .httpHeader("X-A", "bad\r\nInjected: 1").userAgent(" ").hlsLiveStartSegmentsFromEnd(0).retry(99, 5, 9);
            List<String> p = b.problems();
            check(p.size() >= 10, "expected >=10 problems, got " + p.size() + ": " + p);
            try { b.build(); throw new AssertionError("build must throw"); } catch (IllegalArgumentException e) { check(e.getMessage().contains("demuxerMaxBytes"), e.getMessage()); }
        });
        test("profiles: LOW_LATENCY vs STABLE differ as documented", () -> {
            Map<String, String> ll = opts(MpvPlayerConfig.builder(MpvPlayerConfig.BufferingProfile.LOW_LATENCY).build());
            Map<String, String> st = opts(MpvPlayerConfig.builder(MpvPlayerConfig.BufferingProfile.STABLE).build());
            eq("low-latency", ll.get("profile"), "profile"); check(!st.containsKey("profile"), "STABLE has no profile");
            eq("no", ll.get("cache-pause"), "ll cache-pause"); eq("yes", st.get("cache-pause-initial"), "stable initial buffering");
            eq("fflags=+nobuffer,live_start_index=-1", ll.get("demuxer-lavf-o"), "ll lavf-o composite");
            check(Long.parseLong(st.get("demuxer-max-bytes")) > Long.parseLong(ll.get("demuxer-max-bytes")), "stable buffers more");
        });
        test("builder rejects LOW_LATENCY with huge cacheSeconds", () -> {
            throwsIae("ll+60s", () -> MpvPlayerConfig.builder(MpvPlayerConfig.BufferingProfile.LOW_LATENCY).cacheSeconds(60).build());
        });
        test("headers: list format; commas rejected (mpv cannot carry them); ytdl disabled", () -> {
            Map<String, String> o = opts(MpvPlayerConfig.builder().httpHeader("X-A", "a").httpHeader("X-B", "c").build());
            eq("X-A: a,X-B: c", o.get("http-header-fields"), "header list"); eq("no", o.get("ytdl"), "ytdl off");
            check(MpvPlayerConfig.builder().httpHeader("X-A", "a,b").problems().toString().contains("contains ','"), "comma problem reported");
            throwsIae("load header comma", () -> LoadOptions.builder().header("X-A", "a,b").build());
        });

        group = "m3u";
        test("parses attributes, group, vlcopt, relative URLs, BOM and CRLF", () -> {
            String t = "\uFEFF#EXTM3U url-tvg=\"http://epg\"\r\n#EXTINF:-1 tvg-id=\"bbc1\" tvg-logo=\"http://l/x.png\" group-title=\"News, UK\",BBC One, HD\r\n"
                    + "#EXTVLCOPT:http-user-agent=Foo/1\r\n#EXTVLCOPT:http-referrer=http://ref/\r\nhttp://s/bbc1.m3u8\r\n"
                    + "#EXTINF:120,Movie\r\nhttps://s/m.mp4\r\n#EXTINF:-1,Rel\r\nrel/x.ts\r\n";
            M3uParser.Result r = M3uParser.parse(t, new M3uParser.Options().baseUri("http://host/dir/list.m3u"));
            eq(3, r.getEntries().size(), "entries"); check(r.hasExtm3uHeader(), "header");
            PlaylistEntry e = r.getEntries().get(0);
            eq("BBC One, HD", e.getTitle(), "title with comma"); eq("News, UK", e.getGroup(), "group"); eq("bbc1", e.getTvgId(), "tvg-id");
            eq("Foo/1", e.getUserAgent(), "ua"); eq("http://ref/", e.getReferrer(), "referrer"); eq(-1.0, e.getDurationSeconds(), "live dur");
            eq(120.0, r.getEntries().get(1).getDurationSeconds(), "vod dur"); eq("http://host/dir/rel/x.ts", r.getEntries().get(2).getUrl(), "relative resolved");
        });
        test("untrusted schemes are rejected, warnings explain why", () -> {
            M3uParser.Result r = M3uParser.parse("#EXTM3U\nedl://x\nfile:///etc/passwd\nlavf://x\nmemory://x\nhttp://ok/1\n/sdcard/a.mp4\n");
            eq(1, r.getEntries().size(), "only http accepted"); eq(5, r.getWarnings().size(), "warnings");
            M3uParser.Result l = M3uParser.parse("file:///sdcard/a.mp4\ncontent://x/y\n/sdcard/b.mp4\nedl://z\n", new M3uParser.Options().allowLocal(true));
            eq(3, l.getEntries().size(), "local allowed but edl:// still rejected");
        });
        test("HLS manifest is not mistaken for a channel list", () -> {
            check(M3uParser.looksLikeHlsManifest("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n"), "media playlist");
            check(M3uParser.looksLikeHlsManifest("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhi.m3u8\n"), "master playlist");
            check(!M3uParser.looksLikeHlsManifest("#EXTM3U\n#EXTINF:-1,a\nhttp://x/a.m3u8\n"), "channel list");
        });
        test("garbage input never throws", () -> {
            M3uParser.parse(null); M3uParser.parse(""); M3uParser.parse("\0\0\n###\n#EXTINF:abc\n#EXTINF:\n\n");
        });

        group = "playlist";
        test("navigation, loop, insert/remove keep position consistent", () -> {
            List<Integer> played = new ArrayList<>();
            PlaylistManager pm = new PlaylistManager(new PlaylistManager.Host() {
                public void playEntry(PlaylistEntry e, int i) { played.add(i); }
                public void playlistStopped() { played.add(-99); }
                public void onPlaylistModelChanged(int p, int c) { }
            });
            pm.setEntries(Arrays.asList(PlaylistEntry.of("http://a"), PlaylistEntry.of("http://b"), PlaylistEntry.of("http://c")), 0);
            check(pm.next(), "next"); check(pm.next(), "next2"); check(!pm.next(), "end, no loop"); pm.setLoop(true); check(pm.next(), "loop wraps");
            eq(0, pm.getPosition(), "wrapped"); pm.setLoop(false); check(!pm.previous(), "at start");
            pm.playIndex(2); pm.insert(0, PlaylistEntry.of("http://z")); eq(3, pm.getPosition(), "position shifted by insert");
            pm.remove(1); eq(2, pm.getPosition(), "position shifted by remove"); eq("http://c", pm.getCurrent().getUrl(), "still on c");
            played.clear(); pm.remove(2); check(played.size() == 1 && played.get(0) == 1, "removing playing entry plays neighbour: " + played);
            played.clear(); pm.clear(); eq(Collections.singletonList(-99), played, "clear stops");
            throwsIae("bad index", () -> { try { pm.playIndex(5); } catch (IndexOutOfBoundsException e) { throw new IllegalArgumentException(e); } });
        });
        test("loadM3u refuses HLS manifests and empty lists", () -> {
            PlaylistManager pm = new PlaylistManager(new PlaylistManager.Host() {
                public void playEntry(PlaylistEntry e, int i) { } public void playlistStopped() { } public void onPlaylistModelChanged(int p, int c) { } });
            try { pm.loadM3u("#EXTM3U\n#EXT-X-TARGETDURATION:2\n", new M3uParser.Options(), false); throw new AssertionError("no throw"); } catch (MpvException e) { check(e.getMessage().contains("HLS"), e.getMessage()); }
            try { pm.loadM3u("#EXTM3U\nedl://x\n", new M3uParser.Options(), false); throw new AssertionError("no throw"); } catch (MpvException e) { check(e.getMessage().contains("no playable"), e.getMessage()); }
        });

        group = "live detection";
        test("rules: hint wins, VOD, growth => LIVE, unknown otherwise", () -> {
            LiveDetector d = new LiveDetector();
            eq(LiveStatus.LIVE, d.evaluate(LiveStatus.LIVE, false, Double.NaN, null, null, null, 0).status, "hint without media");
            eq(LiveStatus.UNKNOWN, d.evaluate(LiveStatus.UNKNOWN, false, 5, true, false, "hls", 0).status, "no media");
            eq(LiveStatus.VOD, d.evaluate(LiveStatus.UNKNOWN, true, 20, true, false, "hls", 0).status, "finite+seekable");
            eq(LiveStatus.VOD, d.evaluate(null, true, 6, false, false, "mov,mp4", 0).status, "mp4 w/o range");
            eq(LiveStatus.UNKNOWN, d.evaluate(null, true, 6, false, false, "hls", 0).status, "hls not seekable before growth");
            eq(LiveStatus.UNKNOWN, d.evaluate(null, true, Double.NaN, false, false, "mpegts", 0).status, "no duration, not seekable");
            d.onDuration(6, 0); d.onDuration(8, 2100); d.onDuration(10, 4100);
            LiveDetector.Result r = d.evaluate(null, true, 10, false, false, "hls", 4200);
            eq(LiveStatus.LIVE, r.status, "growing HLS"); check(r.reason.contains("growing"), r.reason);
            LiveDetector d2 = new LiveDetector(); d2.onDuration(20, 0); d2.onDuration(20, 3000); d2.onDuration(20, 6000);
            eq(LiveStatus.VOD, d2.evaluate(null, true, 20, true, false, "hls", 6000).status, "constant duration stays VOD");
            eq(LiveStatus.VOD, d2.evaluate(LiveStatus.VOD, true, 20, false, false, "hls", 6000).status, "VOD hint");
        });

        group = "state machine";
        test("derive() truth table", () -> {
            eq(PlaybackState.RELEASED, MpvPlayer.derive(true, false, false, false, true, false, false, false, false), "released");
            eq(PlaybackState.LOADING, MpvPlayer.derive(false, false, true, false, true, false, false, false, false), "loading");
            eq(PlaybackState.LOADING, MpvPlayer.derive(false, true, false, true, true, false, false, false, false), "retry pending");
            eq(PlaybackState.ERROR, MpvPlayer.derive(false, false, false, true, true, false, false, false, false), "error");
            eq(PlaybackState.IDLE, MpvPlayer.derive(false, false, false, false, true, false, false, false, false), "idle");
            eq(PlaybackState.ENDED, MpvPlayer.derive(false, false, false, false, false, true, false, true, true), "ended");
            eq(PlaybackState.BUFFERING, MpvPlayer.derive(false, false, false, false, false, false, true, true, true), "buffering");
            eq(PlaybackState.PAUSED, MpvPlayer.derive(false, false, false, false, false, false, false, true, true), "paused");
            eq(PlaybackState.PLAYING, MpvPlayer.derive(false, false, false, false, false, false, false, false, true), "playing");
        });
        test("looksNetwork is only used for retry eligibility", () -> {
            check(MpvPlayer.looksNetwork("http://a/b.m3u8") && MpvPlayer.looksNetwork("rtmp://x/y"), "network");
            check(!MpvPlayer.looksNetwork("/sdcard/a.mp4") && !MpvPlayer.looksNetwork("content://x") && !MpvPlayer.looksNetwork("file:///a"), "local");
        });

        group = "player (fake backend)";
        test("initialize: applies options in order, observes properties, reports rejected options", () -> {
            FakeBackend fb = new FakeBackend(); fb.optionResult.put("cache-pause-wait", Mpv.Err.OPTION_NOT_FOUND);
            T.Rec rec = new T.Rec(); MpvPlayer p = player(fb, nullVo(), rec);
            check(fb.created && fb.initialized, "created+initialized"); check(fb.optionsSet.get(0).startsWith("msg-level="), "msg-level first");
            eq(1, p.getOptionReport().size(), "one rejected"); check(p.getOptionReport().get(0).toString().contains("not supported"), p.getOptionReport().get(0).toString());
            eq(1, rec.rejected.size(), "listener told"); check(fb.observed.containsKey("eof-reached") && fb.observed.get("time-pos") == Mpv.Format.INT64, "observers");
            eq("0.37.0", p.getMpvVersion().substring(4), "version"); eq(PlaybackState.IDLE, p.getState(), "idle");
            check(fb.propSets.contains("keepaspect=yes"), "default scale mode applied: " + fb.propSets);
        });
        test("loadfile layout follows mpv version (0.37 legacy vs newer)", () -> {
            FakeBackend old = new FakeBackend(); MpvPlayer p = player(old, nullVo(), null);
            p.load("http://h/a.m3u8", LoadOptions.builder().userAgent("UA,1").header("X-H", "v").header("X-I", "w").startPositionSeconds(30).build());
            String c = old.lastCommand(); check(c.startsWith("loadfile http://h/a.m3u8 replace start=30,user-agent=%4%UA,1,http-header-fields=%") && c.endsWith("%X-H: v,X-I: w"), c);
            check(old.commands.get(0).size() == 4, "legacy layout has 4 args: " + old.commands.get(0));
            FakeBackend mg = new FakeBackend(); MpvPlayer pm = player(mg, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).httpHeader("X-Cfg", "1").build(), null);
            pm.load("http://h/m", LoadOptions.builder().header("X-Call", "2").header("X-Cfg", "override").build());
            check(mg.lastCommand().endsWith("%X-Cfg: override,X-Call: 2"), "config + per-load merged, per-load wins: " + mg.lastCommand());
            FakeBackend nw = new FakeBackend(); MpvPlayer q = new MpvPlayer(nw, nullVo(), DIRECT, null); nw.props.put("mpv-version", "mpv v0.41.0-12-gabc"); q.initialize();
            q.load("http://h/a", LoadOptions.builder().audioOnly(true).build());
            eq(Arrays.asList("loadfile", "http://h/a", "replace", "-1", "vid=no"), nw.commands.get(0), "new layout adds index arg");
            FakeBackend plain = new FakeBackend(); player(plain, nullVo(), null).load("/sdcard/a.mp4");
            eq(Arrays.asList("loadfile", "/sdcard/a.mp4", "replace"), plain.commands.get(0), "no options => no extra args");
        });
        test("GPU output queues the load until a surface exists; audio-only does not", () -> {
            FakeBackend fb = new FakeBackend();
            MpvPlayer p = new MpvPlayer(fb, MpvPlayerConfig.builder().retry(0, 100, 1).build(), DIRECT, null); fb.props.put("mpv-version", "mpv 0.37.0"); p.initialize();
            p.load("http://h/live"); eq(0L, fb.count("loadfile"), "queued"); eq(PlaybackState.LOADING, p.getState(), "loading while queued");
            p.attachSurface(null); eq(1L, fb.count("loadfile"), "issued on attach"); check(fb.optionsSet.contains("force-window=yes"), "force-window yes");
            p.load("http://h/radio", LoadOptions.builder().audioOnly(true).build()); eq(2L, fb.count("loadfile"), "audio-only loads immediately");
            p.detachSurface(); check(fb.propSets.contains("vo=null") && fb.propSets.contains("force-window=no"), "vo=null before detach: " + fb.propSets);
        });
        test("media loaded: tracks, live status VOD, state PLAYING, info snapshot", () -> {
            FakeBackend fb = new FakeBackend(); T.Rec rec = new T.Rec(); MpvPlayer p = player(fb, nullVo(), rec);
            p.load("/x/a.mp4"); loadVod(fb, 6.0); fb.flag("idle-active", false);
            eq(PlaybackState.PLAYING, p.getState(), "playing"); check(rec.loaded != null, "onMediaLoaded");
            eq(LiveStatus.VOD, p.getLiveStatus(), "VOD"); check(!p.isLive(), "not live");
            eq(1, p.getAudioTracks().size(), "audio tracks"); eq("eng", p.getAudioTracks().get(0).getLanguage(), "lang"); check(p.getAudioTracks().get(0).isSelected(), "selected");
            check(rec.loaded.hasVideo() && rec.loaded.hasAudio(), "has av"); near(6.0, p.getDurationSeconds(), 1e-9, "duration");
            fb.flag("pause", true); eq(PlaybackState.PAUSED, p.getState(), "paused"); fb.flag("pause", false);
            fb.flag("paused-for-cache", true); eq(PlaybackState.BUFFERING, p.getState(), "buffering"); fb.flag("paused-for-cache", false);
        });
        test("EOF via eof-reached: ENDED, completed callback, auto-advance to next entry", () -> {
            FakeBackend fb = new FakeBackend(); T.Rec rec = new T.Rec(); MpvPlayer p = player(fb, nullVo(), rec);
            p.getPlaylist().setEntries(Arrays.asList(PlaylistEntry.of("/a.mp4"), PlaylistEntry.of("/b.mp4")), 0);
            loadVod(fb, 5); fb.flag("idle-active", false);
            fb.flag("eof-reached", true);
            eq(1, rec.completed, "completed once"); eq(1, p.getPlaylist().getPosition(), "advanced"); eq("loadfile /b.mp4 replace", fb.lastCommand(), "next loaded");
            // a repeated eof=true for the SAME entry (last one: nothing to advance to) must not complete twice
            fb.startFile(); fb.flag("idle-active", false); fb.flag("eof-reached", true); eq(2, rec.completed, "second entry completes once");
            fb.flag("eof-reached", true); eq(2, rec.completed, "no double completion");
        });
        test("last entry EOF: state ENDED and play() restarts from 0", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            p.load("/a.mp4"); loadVod(fb, 5); fb.flag("idle-active", false); fb.flag("eof-reached", true);
            eq(PlaybackState.ENDED, p.getState(), "ended"); p.play(); check(fb.commands.get(fb.commands.size() - 1).contains("absolute"), "seek 0 absolute: " + fb.lastCommand());
        });
        test("user stop / load-over-load never produce errors or retries", () -> {
            FakeBackend fb = new FakeBackend(); T.Rec rec = new T.Rec();
            MpvPlayer p = player(fb, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).retry(3, 100, 1).build(), rec);
            p.load("http://h/a"); loadVod(fb, 5); fb.flag("idle-active", false);
            p.load("http://h/b"); fb.endFile(Mpv.EndFileReason.STOP, 0); fb.startFile(); // old entry replaced
            p.stop(); fb.endFile(Mpv.EndFileReason.STOP, 0); fb.flag("idle-active", true);
            check(rec.errors.isEmpty() && rec.retries.isEmpty(), "no errors: " + rec.errors); eq(PlaybackState.IDLE, p.getState(), "idle after stop");
        });
        test("playback error on a network stream retries with backoff, then ERROR (inferred end reason)", () -> {
            FakeBackend fb = new FakeBackend(); T.Rec rec = new T.Rec();
            MpvPlayer p = player(fb, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).retry(2, 80, 2.0).build(), rec);
            p.load("http://h/live.m3u8"); fb.startFile(); fb.sink.onLogMessage("ffmpeg", Mpv.Log.ERROR, "Connection refused\n");
            fb.endFile(Mpv.EndFileReason.ERROR, 0);                       // no native reason: engine infers ERROR
            eq(1, rec.retries.size(), "retry 1 scheduled"); check(rec.errors.get(0).willRetry(), "will retry"); eq(PlaybackState.LOADING, p.getState(), "LOADING while waiting");
            await(() -> fb.count("loadfile") == 2, 2000, "retry loadfile #2");
            fb.startFile(); fb.sink.onLogMessage("ffmpeg", Mpv.Log.ERROR, "Connection refused\n"); fb.endFile(Mpv.EndFileReason.ERROR, 0);
            eq(2, rec.retries.size(), "retry 2"); check(rec.retries.get(1).endsWith("@160"), "backoff doubled: " + rec.retries);
            await(() -> fb.count("loadfile") == 3, 2000, "retry loadfile #3");
            fb.startFile(); fb.sink.onLogMessage("ffmpeg", Mpv.Log.ERROR, "Connection refused\n"); fb.endFile(Mpv.EndFileReason.ERROR, 0);
            eq(3, rec.errors.size(), "3 errors"); check(!rec.errors.get(2).willRetry(), "final not retried"); eq(PlaybackState.ERROR, p.getState(), "ERROR");
            check(p.getLastError().getLogLines().get(0).contains("Connection refused"), "log line kept: " + p.getLastError().getLogLines());
            eq(MpvError.Kind.LOAD_FAILED, p.getLastError().getKind(), "kind");
        });
        test("local file error is not retried; native end reason + code are used when available", () -> {
            FakeBackend fb = new FakeBackend(); fb.sendNativeEndReason = true; T.Rec rec = new T.Rec();
            MpvPlayer p = player(fb, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).retry(3, 50, 1).build(), rec);
            p.load("/sdcard/missing.mp4"); fb.startFile(); fb.endFile(Mpv.EndFileReason.ERROR, Mpv.Err.LOADING_FAILED);
            check(rec.retries.isEmpty(), "no retry for local"); eq(PlaybackState.ERROR, p.getState(), "ERROR"); eq(-13, p.getLastError().getMpvErrorCode(), "code");
            // native reason EOF with no eof-reached property is still EOF
            p.load("/sdcard/a.mp4"); fb.startFile(); fb.endFile(Mpv.EndFileReason.EOF, 0); eq(1, rec.completed, "EOF from native reason");
        });
        test("retry resumes VOD at last position but never seeks live", () -> {
            FakeBackend fb = new FakeBackend();
            MpvPlayer p = player(fb, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).retry(1, 50, 1).build(), null);
            p.load("http://h/movie.mp4"); loadVod(fb, 100); fb.sink.onPropertyChange("time-pos", 42L);
            fb.endFile(Mpv.EndFileReason.ERROR, 0); await(() -> fb.count("loadfile") == 2, 2000, "retry");
            check(fb.lastCommand().contains("start=42"), "resume position: " + fb.lastCommand());
            FakeBackend fl = new FakeBackend();
            MpvPlayer q = player(fl, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).liveHint(LiveStatus.LIVE).retry(1, 50, 1).build(), null);
            q.load("http://h/live"); loadVod(fl, 100); fl.sink.onPropertyChange("time-pos", 42L); fl.endFile(Mpv.EndFileReason.ERROR, 0);
            await(() -> fl.count("loadfile") == 2, 2000, "live retry");
            check(!fl.lastCommand().contains("start="), "live is never resumed at an old position: " + fl.lastCommand());
        });
        test("live hint is honoured immediately; growth detection flips UNKNOWN->LIVE for HLS", () -> {
            FakeBackend fb = new FakeBackend(); T.Rec rec = new T.Rec(); MpvPlayer p = player(fb, nullVo(), rec);
            p.load("http://h/ch.m3u8", LoadOptions.builder().liveHint(LiveStatus.LIVE).build()); loadVod(fb, 6); fb.flag("seekable", false);
            eq(LiveStatus.LIVE, p.getLiveStatus(), "hint"); check(p.isLive(), "isLive");
            FakeBackend fb2 = new FakeBackend(); T.Rec rec2 = new T.Rec(); MpvPlayer q = player(fb2, nullVo(), rec2);
            q.load("http://h/ch.m3u8"); fb2.props.put("file-format", "hls"); fb2.startFile(); fb2.props.put("track-list/count", "0");
            fb2.flag("seekable", false); fb2.dbl("duration", 6.0); fb2.fileLoaded();
            eq(LiveStatus.UNKNOWN, q.getLiveStatus(), "unknown at first"); check(q.getLiveStatusReason().contains("waiting"), q.getLiveStatusReason());
            Thread.sleep(2200); fb2.dbl("duration", 8.0); fb2.dbl("duration", 10.0);
            eq(LiveStatus.LIVE, q.getLiveStatus(), "LIVE after growth"); eq(Collections.singletonList(LiveStatus.LIVE), rec2.live, "single UNKNOWN->LIVE transition reported");
            check(!q.isSeekable() && !q.seekTo(5), "live: seek refused");
            fb2.props.put("demuxer-cache-time", "12.0"); fb2.props.put("time-pos", "9.0");
            near(12.0, q.getLiveEdgePositionSeconds(), 1e-9, "live edge = cache end"); near(3.0, q.getLiveLatencySeconds(), 1e-9, "latency");
            long before = fb2.count("loadfile"); check(q.jumpToLive(), "jumpToLive"); eq(before + 1, fb2.count("loadfile"), "non-seekable live jump = reload");
        });
        test("seeking is gated by mpv's seekable flag", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            p.load("/a.mp4"); loadVod(fb, 60);
            check(p.seekTo(10) && p.seekBy(-5) && p.seekToPercent(50), "seekable"); eq("seek 50 absolute-percent", fb.lastCommand(), "percent");
            check(fb.commands.get(0).equals(Arrays.asList("loadfile", "/a.mp4", "replace")), "first is load");
            eq(Arrays.asList("seek", "10", "absolute"), fb.commands.get(1), "abs"); eq(Arrays.asList("seek", "-5", "relative"), fb.commands.get(2), "rel");
            fb.flag("seekable", false); check(!p.seekTo(1), "refused when not seekable");
            throwsIae("percent", () -> p.seekToPercent(101));
        });
        test("subtitle & audio APIs use the verified mpv names and validate ranges", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            p.load("/a.mkv"); loadVod(fb, 60);
            p.selectSubtitleTrack(3); check(fb.propSets.contains("sid=3") && fb.propSets.contains("sub-visibility=yes"), "select: " + fb.propSets);
            p.disableSubtitles(); check(fb.propSets.contains("sid=no"), "disable");
            p.loadExternalSubtitle("/x/a.srt"); eq("sub-add /x/a.srt select", fb.lastCommand(), "sub-add");
            p.setSubtitleDelay(-0.5); p.setSubtitleSize(40); p.setSubtitlePosition(90); p.setSubtitleScale(1.2);
            check(fb.propSets.contains("sub-delay=-0.5") && fb.propSets.contains("sub-font-size=40.0") && fb.propSets.contains("sub-pos=90") && fb.propSets.contains("sub-scale=1.2"), "sets: " + fb.propSets);
            throwsIae("size", () -> p.setSubtitleSize(0)); throwsIae("pos", () -> p.setSubtitlePosition(151)); throwsIae("delay", () -> p.setSubtitleDelay(Double.NaN));
            p.selectAudioTrack(2); p.disableAudio(); p.addExternalAudio("/x/a.mka"); p.setAudioDelay(0.25);
            check(fb.propSets.contains("aid=2") && fb.propSets.contains("aid=no") && fb.propSets.contains("audio-delay=0.25"), "audio: " + fb.propSets);
            eq("audio-add /x/a.mka select", fb.commands.stream().filter(c -> c.get(0).equals("audio-add")).map(c -> String.join(" ", c)).findFirst().get(), "audio-add");
            throwsIae("volume", () -> p.setVolume(200)); throwsIae("speed", () -> p.setSpeed(0)); p.setSpeed(1.5); p.setVolume(80); p.setMuted(true);
        });
        test("video geometry API", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            fb.propSets.clear();
            p.setAspectRatio("16:9"); eq("1.777778", fb.props.get("video-aspect-override"), "16:9");
            p.setAspectRatio("2.35"); eq("2.350000", fb.props.get("video-aspect-override"), "decimal");
            fb.props.put("option-info/video-aspect-override/default-value", "-1"); p.resetAspectRatio(); eq("-1", fb.props.get("video-aspect-override"), "reset to build default");
            p.setVideoScaleMode(VideoScaleMode.FILL); eq("1.0", fb.props.get("panscan"), "fill panscan"); eq("yes", fb.props.get("keepaspect"), "fill keeps aspect");
            p.setVideoScaleMode(VideoScaleMode.STRETCH); eq("no", fb.props.get("keepaspect"), "stretch");
            p.setVideoScaleMode(VideoScaleMode.ORIGINAL); eq("yes", fb.props.get("video-unscaled"), "original");
            p.setZoom(2); near(1.0, Double.parseDouble(fb.props.get("video-zoom")), 1e-9, "zoom 2x = log2 1"); p.setPan(0.1, -0.2);
            p.setCrop(10, 20, 300, 200); eq("300x200+10+20", fb.props.get("video-crop"), "crop"); p.clearCrop(); eq("", fb.props.get("video-crop"), "clear crop");
            p.setRotation(90); eq("90", fb.props.get("video-rotate"), "rotate");
            p.resetVideoScaling(); eq("0", fb.props.get("video-rotate"), "rot reset"); eq("0.0", fb.props.get("video-zoom"), "zoom reset"); eq("no", fb.props.get("video-unscaled"), "unscaled reset");
            throwsIae("rot", () -> p.setRotation(45)); throwsIae("zoom", () -> p.setZoom(100)); throwsIae("aspect", () -> p.setAspectRatio("abc")); throwsIae("crop", () -> p.setCrop(0, 0, 0, 5));
        });
        test("config-level defaults applied after init", () -> {
            FakeBackend fb = new FakeBackend();
            MpvPlayer p = player(fb, MpvPlayerConfig.builder().videoOutput(MpvPlayerConfig.VideoOutput.NULL).audioOutput(null).defaultAspectRatio(16.0 / 9)
                    .defaultScaleMode(VideoScaleMode.FILL).defaultPlaybackSpeed(1.25).initialVolume(70).subtitlesEnabled(false).subtitleDelaySeconds(0.3).build(), null);
            check(fb.propSets.contains("speed=1.25") && fb.propSets.contains("volume=70.0") && fb.propSets.contains("sub-visibility=no") && fb.propSets.contains("sub-delay=0.3"), "" + fb.propSets);
            eq("1.777778", fb.props.get("video-aspect-override"), "aspect"); eq("1.0", fb.props.get("panscan"), "fill");
        });
        test("playlist entries carry per-entry user agent/referrer into the load", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            p.getPlaylist().loadM3u("#EXTM3U\n#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=Agent/9\nhttp://h/a\n#EXTINF:-1,B\nhttp://h/b\n", new M3uParser.Options(), true);
            check(fb.lastCommand().contains("user-agent=%7%Agent/9"), fb.lastCommand()); p.next(); eq("loadfile http://h/b replace", fb.lastCommand(), "second has none");
            p.load("http://h/other"); eq(-1, p.getPlaylist().getPosition(), "direct load detaches from playlist");
        });
        test("reopen resumes VOD at the last position; togglePause flips state", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            p.load("/a.mp4"); loadVod(fb, 100); fb.flag("idle-active", false); fb.sink.onPropertyChange("time-pos", 33L);
            p.reopen(); check(fb.lastCommand().contains("start=33"), "reopen resumes: " + fb.lastCommand());
            fb.startFile(); fb.flag("pause", false); p.togglePause(); eq("pause=yes", fb.propSets.get(fb.propSets.size() - 1), "toggle -> pause");
            fb.flag("pause", true); loadVod(fb, 100); fb.flag("pause", true); p.togglePause(); eq("pause=no", fb.propSets.get(fb.propSets.size() - 1), "toggle -> play");
            MpvPlayer q = player(new FakeBackend(), nullVo(), null);
            try { q.reopen(); throw new AssertionError("no throw"); } catch (MpvException e) { check(e.getMessage().contains("nothing to reopen"), e.getMessage()); }
        });
        test("release is final; calls afterwards fail cleanly", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null); p.release(); p.release();
            check(fb.destroyed, "destroyed"); eq(PlaybackState.RELEASED, p.getState(), "released");
            try { p.play(); throw new AssertionError("no throw"); } catch (MpvException e) { check(e.getMessage().contains("released"), e.getMessage()); }
            check(Double.isNaN(p.getPositionSeconds()), "NaN position after release");
        });
        test("API before initialize() fails with a clear message", () -> {
            MpvPlayer p = new MpvPlayer(new FakeBackend(), nullVo(), DIRECT, null);
            try { p.load("x"); throw new AssertionError("no throw"); } catch (MpvException e) { check(e.getMessage().contains("initialize"), e.getMessage()); }
        });
        test("concurrent events and listener churn do not throw or deadlock", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null); p.load("/a.mp4"); loadVod(fb, 100);
            List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());
            Thread[] ts = new Thread[4];
            for (int i = 0; i < ts.length; i++) { final int n = i; ts[i] = new Thread(() -> { try { for (int k = 0; k < 2000; k++) {
                if (n == 0) fb.sink.onPropertyChange("time-pos", (long) k); else if (n == 1) fb.sink.onPropertyChange("pause", k % 2 == 0);
                else if (n == 2) { T.Rec r = new T.Rec(); p.addListener(r); p.removeListener(r); p.getState(); p.getAudioTracks(); }
                else { fb.sink.onPropertyChange("duration", 100.0 + k); p.getLiveStatus(); p.getBufferingInfo(); } } } catch (Throwable e) { errs.add(e); } }); ts[i].start(); }
            for (Thread t : ts) t.join(10000);
            for (Thread t : ts) check(!t.isAlive(), "thread finished (no deadlock)"); check(errs.isEmpty(), "errors: " + errs);
        });
        test("Mpv helpers: %len% quoting counts UTF-8 bytes; version parsing", () -> {
            eq("%4%é,x", Mpv.kvValue("é,x"), "utf8 length"); eq("%0%", Mpv.kvValue(""), "empty");
            check(Arrays.equals(new int[]{0, 37}, Mpv.parseVersion("mpv 0.37.0")), "0.37"); check(Arrays.equals(new int[]{0, 41}, Mpv.parseVersion("mpv v0.41.0-123-gabc")), "0.41");
            check(Arrays.equals(new int[]{-1, -1}, Mpv.parseVersion(null)), "null");
        });
        test("MiniJson parses libmpv node strings", () -> {
            Map<String, Object> m = MiniJson.parseObject("{\"cache-end\":1.85,\"eof\":false,\"seekable-ranges\":[{\"start\":0,\"end\":4.9}],\"s\":\"a\\\"b\\u00e9\"}");
            near(1.85, (Double) m.get("cache-end"), 1e-9, "num"); eq(Boolean.FALSE, m.get("eof"), "bool"); eq("a\"bé", m.get("s"), "escapes");
            eq(null, MiniJson.parse("{broken"), "invalid => null");
        });
        test("track list reading maps every field", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            Map<String, String> m = fb.props; m.put("track-list/count", "4");
            m.put("track-list/0/type", "video"); m.put("track-list/0/id", "1"); m.put("track-list/0/codec", "h264"); m.put("track-list/0/demux-w", "1920"); m.put("track-list/0/demux-h", "1080"); m.put("track-list/0/demux-fps", "25");
            m.put("track-list/1/type", "audio"); m.put("track-list/1/id", "1"); m.put("track-list/1/lang", "eng"); m.put("track-list/1/title", "Main"); m.put("track-list/1/codec", "aac"); m.put("track-list/1/demux-channel-count", "2"); m.put("track-list/1/demux-samplerate", "48000"); m.put("track-list/1/selected", "yes"); m.put("track-list/1/default", "yes");
            m.put("track-list/2/type", "sub"); m.put("track-list/2/id", "1"); m.put("track-list/2/lang", "spa"); m.put("track-list/2/codec", "subrip"); m.put("track-list/2/forced", "yes");
            m.put("track-list/3/type", "sub"); m.put("track-list/3/id", "2"); m.put("track-list/3/external", "yes"); m.put("track-list/3/external-filename", "/x/y.srt"); m.put("track-list/3/selected", "yes");
            fb.sink.onPropertyChange("track-list");
            AudioTrackInfo a = p.getAudioTracks().get(0); eq("Main", a.getTitle(), "title"); eq(2, a.getChannels(), "channels"); eq(48000, a.getSampleRate(), "rate"); check(a.isSelected() && a.isDefault(), "flags");
            eq(2, p.getSubtitleTracks().size(), "subs"); check(p.getSubtitleTracks().get(0).isForced(), "forced"); check(p.getSubtitleTracks().get(1).isExternal() && p.getSubtitleTracks().get(1).isSelected(), "external");
            eq("/x/y.srt", p.getSubtitleTracks().get(1).getExternalFilename(), "ext file");
        });
        test("buffering info parses demuxer-cache-state JSON", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            fb.props.put("demuxer-cache-time", "12.5"); fb.props.put("time-pos", "10.0"); fb.props.put("demuxer-cache-duration", "2.0");
            fb.props.put("demuxer-cache-state", "{\"fw-bytes\":54496,\"raw-input-rate\":120000.5,\"eof\":false}");
            BufferingInfo b = p.getBufferingInfo(); near(2.5, b.getBufferedAheadSeconds(), 1e-9, "ahead"); eq(54496L, b.getForwardBytes(), "fw"); near(120000.5, b.getInputRateBytesPerSecond(), 1e-6, "rate");
        });
        test("option support probing uses option-info", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null);
            fb.props.put("option-info/video-crop/name", "video-crop"); check(p.isOptionSupported("video-crop"), "known"); check(!p.isOptionSupported("nonsense"), "unknown");
        });
        test("screenshot, frame step, deinterlace, hwdec go to the right commands", () -> {
            FakeBackend fb = new FakeBackend(); MpvPlayer p = player(fb, nullVo(), null); p.load("/a.mp4"); loadVod(fb, 30);
            p.takeScreenshot("/x/s.jpg", true); eq("screenshot-to-file /x/s.jpg subtitles", fb.lastCommand(), "shot"); p.takeScreenshot("/x/s.png", false); eq("screenshot-to-file /x/s.png video", fb.lastCommand(), "shot video");
            p.frameStep(); eq("frame-step", fb.lastCommand(), "step"); check(p.frameBackStep(), "back step seekable"); eq("frame-back-step", fb.lastCommand(), "back");
            p.setDeinterlace(true); p.setHardwareDecoding(MpvPlayerConfig.HardwareDecoding.SOFTWARE); check(fb.propSets.contains("deinterlace=yes") && fb.propSets.contains("hwdec=no"), "" + fb.propSets);
        });
    }

    private static Map<String, String> opts(MpvPlayerConfig c) {
        Map<String, String> o = new LinkedHashMap<>(); for (MpvPlayerConfig.Opt x : c.toMpvOptions()) o.put(x.name, x.value); return o;
    }
}
