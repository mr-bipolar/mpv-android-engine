package com.maxplayer.mpv;

import android.view.Surface;

/**
 * Thin abstraction over the native libmpv binding ({@code is.xyz.mpv.MPVLib}).
 *
 * <p>It exists for two reasons only: (1) it lets the playback logic be unit-tested on a plain JVM
 * against a fake or a desktop-mpv backend, and (2) it documents precisely which native capabilities
 * the engine relies on. Application code normally never touches it.
 *
 * <h3>Facts about the real native layer this mirrors (verified in mpv-android sources)</h3>
 * <ul>
 *   <li>There is <b>one</b> global mpv instance per process; {@code create()} twice aborts the process.</li>
 *   <li>{@link #command(String...)} returns nothing: command failures are only visible via log
 *       messages / events. Likewise the {@code setProperty*} calls are {@code void}.</li>
 *   <li>{@link #setOptionString} <b>does</b> return an mpv error code (negative = failure).</li>
 *   <li>{@code getProperty*} return {@code null} when the property is unavailable or unknown.</li>
 *   <li>{@code getPropertyInt} narrows the 64-bit mpv integer to a 32-bit {@code int}.</li>
 *   <li>{@link EventSink} callbacks arrive on mpv's native event thread.</li>
 * </ul>
 */
public interface MpvBackend {

    void create();
    void initialize();
    void destroy();

    /** @return 0 on success, a negative {@code mpv_error} code otherwise (see {@link Mpv.Err}). */
    int setOptionString(String name, String value);

    void command(String... args);

    Integer getPropertyInt(String name);
    Double getPropertyDouble(String name);
    Boolean getPropertyBoolean(String name);
    String getPropertyString(String name);

    void setPropertyInt(String name, int value);
    void setPropertyDouble(String name, double value);
    void setPropertyBoolean(String name, boolean value);
    void setPropertyString(String name, String value);

    /** @param format one of {@link Mpv.Format} */
    void observeProperty(String name, int format);

    void attachSurface(Surface surface);
    void detachSurface();

    void setEventSink(EventSink sink);

    /**
     * True when this backend delivers {@link EventSink#onEndFile(int, int)} (needs the optional native
     * patch in docs/native-patches). When false the engine infers the end-of-file reason.
     */
    default boolean providesEndFileReason() { return false; }

    interface EventSink {
        void onPropertyChange(String name);
        void onPropertyChange(String name, boolean value);
        void onPropertyChange(String name, long value);
        void onPropertyChange(String name, double value);
        void onPropertyChange(String name, String value);
        void onEvent(int eventId);
        void onEndFile(int reason, int error);
        void onLogMessage(String prefix, int level, String text);
    }
}
