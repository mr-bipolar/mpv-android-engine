package com.maxplayer.mpv;

/** Source and display geometry. NaN / -1 mean "not available". */
public final class VideoInfo {
    private final int sourceWidth, sourceHeight, displayWidth, displayHeight, containerRotation;
    private final double sourceAspect, fps;
    private final String aspectOverride; private final VideoScaleMode scaleMode;
    private final double zoomLog2, panX, panY; private final int userRotation;

    VideoInfo(int sw, int sh, double sourceAspect, int dw, int dh, int containerRotation, double fps,
              String aspectOverride, VideoScaleMode scaleMode, double zoomLog2, double panX, double panY,
              int userRotation) {
        this.sourceWidth = sw; this.sourceHeight = sh; this.sourceAspect = sourceAspect;
        this.displayWidth = dw; this.displayHeight = dh; this.containerRotation = containerRotation;
        this.fps = fps; this.aspectOverride = aspectOverride; this.scaleMode = scaleMode;
        this.zoomLog2 = zoomLog2; this.panX = panX; this.panY = panY; this.userRotation = userRotation;
    }
    /** Decoded source size ({@code video-params/w,h}). */
    public int getSourceWidth() { return sourceWidth; }
    public int getSourceHeight() { return sourceHeight; }
    /** Display aspect ratio of the source ({@code video-params/aspect}), NaN if unknown. */
    public double getSourceAspect() { return sourceAspect; }
    /** Size mpv scales the video to for display ({@code dwidth/dheight}) – not the Android view size. */
    public int getDisplayWidth() { return displayWidth; }
    public int getDisplayHeight() { return displayHeight; }
    /** Rotation signalled by the container, degrees ({@code video-params/rotate}). */
    public int getContainerRotation() { return containerRotation; }
    public double getFps() { return fps; }
    /** Active aspect override as mpv reports it ("no" when none). */
    public String getAspectOverride() { return aspectOverride; }
    public VideoScaleMode getScaleMode() { return scaleMode; }
    /** {@code video-zoom}, log2 scale (0 = none, 1 = 2x). */
    public double getZoomLog2() { return zoomLog2; }
    public double getPanX() { return panX; }
    public double getPanY() { return panY; }
    /** Additional rotation applied by the app via {@code video-rotate}. */
    public int getUserRotation() { return userRotation; }
    @Override public String toString() {
        return "VideoInfo{src=" + sourceWidth + "x" + sourceHeight + ", aspect=" + sourceAspect
                + ", disp=" + displayWidth + "x" + displayHeight + ", mode=" + scaleMode + "}";
    }
}
