package com.maxplayer.mpv;

/**
 * Video display modes realised with mpv options inside the surface mpv renders into.
 * <ul>
 *   <li>{@link #FIT}: whole picture visible, aspect preserved (letterbox) — {@code keepaspect=yes, panscan=0}.</li>
 *   <li>{@link #FILL}: aspect preserved, picture covers the surface, edges cropped — {@code keepaspect=yes, panscan=1}.</li>
 *   <li>{@link #STRETCH}: picture distorted to the surface — {@code keepaspect=no}.</li>
 *   <li>{@link #ORIGINAL}: no scaling (1 video pixel = 1 surface pixel) — {@code video-unscaled=yes}.</li>
 * </ul>
 */
public enum VideoScaleMode { FIT, FILL, STRETCH, ORIGINAL }
