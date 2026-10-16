package com.example.host;

import android.content.Context;
import android.view.SurfaceView;

import com.maxplayer.mpv.*;

import java.util.List;

/**
 * Minimal Java usage of the engine. This is a plain class (no Activity, no layout): the host app owns the
 * SurfaceView and decides where this code lives (Activity, ViewModel, Service...).
 */
public final class UsageExample {
    private MpvPlayer player;

    public void start(Context context, SurfaceView surfaceView) {
        // 1. Configure (validated; throws IllegalArgumentException listing every bad value)
        MpvPlayerConfig config = MpvPlayerConfig.builder(MpvPlayerConfig.BufferingProfile.BALANCED)
                .networkTimeoutSeconds(15)
                .retry(3, 1000, 2.0)                        // automatic reopen on network errors
                .hardwareDecoding(MpvPlayerConfig.HardwareDecoding.AUTO_ANDROID)
                .tlsCaFile(context.getFilesDir() + "/cacert.pem") // you ship/copy the CA bundle (see docs)
                .build();

        // 2. Create + initialise (once per process) and listen (callbacks arrive on the main thread)
        player = MpvPlayer.create(context, config);
        player.addListener(new MpvPlayerListener() {
            @Override public void onStateChanged(PlaybackState s, PlaybackState old) { /* update your UI */ }
            @Override public void onLiveStatusChanged(LiveStatus status, String why) { /* LIVE / VOD / UNKNOWN */ }
            @Override public void onTracksChanged(List<AudioTrackInfo> a, List<SubtitleTrackInfo> s) { }
            @Override public void onError(MpvError e) { /* e.getMessage(), e.willRetry() */ }
            @Override public void onOptionRejected(String n, String v, int code, String why) { /* log: unsupported by this build */ }
        });
        player.initialize();

        // 3. Attach your video surface (the engine never creates views)
        surfaceView.getHolder().addCallback(player.getSurfaceCallback());

        // 4a. A single stream; tell the engine when you KNOW it is a live channel
        player.load("https://example.com/channel/index.m3u8", LoadOptions.builder().liveHint(LiveStatus.LIVE).build());

        // 4b. ...or an M3U channel list
        // String text = UriResolver.readText(context, "https://example.com/list.m3u", 20_000_000, 15_000).text; // off the main thread
        // player.getPlaylist().loadM3u(text, new M3uParser.Options(), true);

        // 5. Controls
        player.setVideoScaleMode(VideoScaleMode.FIT);
        player.setAspectRatio("16:9");
        if (player.getLiveStatus() == LiveStatus.VOD) player.seekBy(10);
    }

    public void stop() {
        if (player != null) { player.release(); player = null; }
    }
}
