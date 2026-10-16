package com.maxplayer.mpv;

import android.view.SurfaceHolder;

/**
 * {@link SurfaceHolder.Callback} that wires any host-owned {@code SurfaceView} (or other holder) to
 * the player. It contains no UI: the host creates and lays out the view.
 * <pre>{@code
 * surfaceView.getHolder().addCallback(player.getSurfaceCallback());
 * }</pre>
 * Lifecycle (mirrors mpv-android's {@code BaseMPVView}): on create the surface is attached and the
 * video output enabled; on destroy the video output is switched to {@code null} <i>before</i> the surface
 * is detached so mpv stops drawing into a dead surface.
 */
public final class MpvSurfaceCallback implements SurfaceHolder.Callback {
    private final MpvPlayer player;
    MpvSurfaceCallback(MpvPlayer player) { this.player = player; }

    @Override public void surfaceCreated(SurfaceHolder holder) { player.attachSurface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { player.setSurfaceSize(width, height); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { player.detachSurface(); }
}
