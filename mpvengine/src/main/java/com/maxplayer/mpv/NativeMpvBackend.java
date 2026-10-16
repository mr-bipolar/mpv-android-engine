package com.maxplayer.mpv;

import android.content.Context;
import android.view.Surface;

import is.xyz.mpv.MPVLib;

/**
 * {@link MpvBackend} backed by the real native library. Loading is deferred until the first
 * instance is created so that a missing/incompatible {@code .so} produces a clear
 * {@link MpvException} instead of crashing at class-load time.
 */
public final class NativeMpvBackend implements MpvBackend {

    private final Context appContext;
    private MPVLib lib;
    private EventSink sink;
    private boolean endFilePatched;

    private final MPVLib.EventObserver eventObserver = new MPVLib.EventObserver() {
        @Override public void eventProperty(String property) { EventSink s = sink; if (s != null) s.onPropertyChange(property); }
        @Override public void eventProperty(String property, long value) { EventSink s = sink; if (s != null) s.onPropertyChange(property, value); }
        @Override public void eventProperty(String property, boolean value) { EventSink s = sink; if (s != null) s.onPropertyChange(property, value); }
        @Override public void eventProperty(String property, String value) { EventSink s = sink; if (s != null) s.onPropertyChange(property, value); }
        @Override public void eventProperty(String property, double value) { EventSink s = sink; if (s != null) s.onPropertyChange(property, value); }
        @Override public void event(int eventId) { EventSink s = sink; if (s != null) s.onEvent(eventId); }
        @Override public void eventEndFile(int reason, int error) {
            endFilePatched = true;
            EventSink s = sink; if (s != null) s.onEndFile(reason, error);
        }
    };

    private final MPVLib.LogObserver logObserver = (prefix, level, text) -> {
        EventSink s = sink; if (s != null) s.onLogMessage(prefix, level, text);
    };

    /** @param context any context; the application context is retained (FFmpeg's JNI layer needs it). */
    public NativeMpvBackend(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override public void create() {
        try {
            lib = MPVLib.getInstance();
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
            throw new MpvException("Native libraries libmpv.so / libplayer.so could not be loaded. "
                    + "See docs/BUILDING_NATIVE.md.", e);
        }
        MPVLib.addObserver(eventObserver);
        MPVLib.addLogObserver(logObserver);
        lib.create(appContext);
    }

    @Override public void initialize() { lib.init(); }

    @Override public void destroy() {
        if (lib == null) return;
        lib.destroy();
        MPVLib.removeObserver(eventObserver);
        MPVLib.removeLogObserver(logObserver);
        lib = null;
    }

    @Override public int setOptionString(String name, String value) { return lib.setOptionString(name, value); }
    @Override public void command(String... args) { lib.command(args); }
    @Override public Integer getPropertyInt(String n) { return lib.getPropertyInt(n); }
    @Override public Double getPropertyDouble(String n) { return lib.getPropertyDouble(n); }
    @Override public Boolean getPropertyBoolean(String n) { return lib.getPropertyBoolean(n); }
    @Override public String getPropertyString(String n) { return lib.getPropertyString(n); }
    @Override public void setPropertyInt(String n, int v) { lib.setPropertyInt(n, v); }
    @Override public void setPropertyDouble(String n, double v) { lib.setPropertyDouble(n, v); }
    @Override public void setPropertyBoolean(String n, boolean v) { lib.setPropertyBoolean(n, v); }
    @Override public void setPropertyString(String n, String v) { lib.setPropertyString(n, v); }
    @Override public void observeProperty(String n, int format) { lib.observeProperty(n, format); }
    @Override public void attachSurface(Surface s) { lib.attachSurface(s); }
    @Override public void detachSurface() { lib.detachSurface(); }
    @Override public void setEventSink(EventSink sink) { this.sink = sink; }

    /** Becomes true only after the first {@code eventEndFile} callback (patched native library). */
    @Override public boolean providesEndFileReason() { return endFilePatched; }
}
