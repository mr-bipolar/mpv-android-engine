package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * JNI bridge to {@code libplayer.so} (the thin native glue from the official mpv-android project,
 * {@code app/src/main/jni}) which in turn links {@code libmpv.so}.
 *
 * <p><b>This class must keep the exact package and class name {@code is.xyz.mpv.MPVLib}</b>: the
 * native symbols are named {@code Java_is_xyz_mpv_MPVLib_*} and the native code looks the class up
 * by name to call the static callbacks below. The signatures here mirror mpv-android's
 * {@code MPVLib.kt} (which is a Kotlin {@code object}, i.e. natives are <i>instance</i> methods on a
 * singleton; we reproduce that shape in plain Java).
 *
 * <p>Do not use this class directly from application code. Use {@code com.maxplayer.mpv.MpvPlayer}.
 *
 * <p>Licensing: the native interface being bound here is MIT-licensed mpv-android code; see
 * docs/LICENSING.md.
 */
public final class MPVLib {

    static {
        // Same order as mpv-android: libmpv first, then the JNI glue that links against it.
        System.loadLibrary("mpv");
        System.loadLibrary("player");
    }

    private static final MPVLib INSTANCE = new MPVLib();

    private MPVLib() {}

    public static MPVLib getInstance() {
        return INSTANCE;
    }

    // ---- natives (instance methods, as in the Kotlin object) ----
    public native void create(Context appctx);
    public native void init();
    public native void destroy();
    public native void attachSurface(Surface surface);
    public native void detachSurface();

    public native void command(String[] cmd);

    public native int setOptionString(String name, String value);

    public native Bitmap grabThumbnail(int dimension);

    public native Integer getPropertyInt(String property);
    public native void setPropertyInt(String property, int value);
    public native Double getPropertyDouble(String property);
    public native void setPropertyDouble(String property, double value);
    public native Boolean getPropertyBoolean(String property);
    public native void setPropertyBoolean(String property, boolean value);
    public native String getPropertyString(String property);
    public native void setPropertyString(String property, String value);

    public native void observeProperty(String property, int format);

    // ---- observers (called from the native event thread) ----
    public interface EventObserver {
        void eventProperty(String property);
        void eventProperty(String property, long value);
        void eventProperty(String property, boolean value);
        void eventProperty(String property, String value);
        void eventProperty(String property, double value);
        void event(int eventId);
        /**
         * Only invoked by a native library that includes {@code docs/native-patches/end-file-reason.patch}.
         * Stock mpv-android native code never calls this.
         */
        default void eventEndFile(int reason, int error) {}
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    private static final List<EventObserver> observers = new ArrayList<>();
    private static final List<LogObserver> logObservers = new ArrayList<>();

    public static void addObserver(EventObserver o) {
        synchronized (observers) { observers.add(o); }
    }
    public static void removeObserver(EventObserver o) {
        synchronized (observers) { observers.remove(o); }
    }
    public static void addLogObserver(LogObserver o) {
        synchronized (logObservers) { logObservers.add(o); }
    }
    public static void removeLogObserver(LogObserver o) {
        synchronized (logObservers) { logObservers.remove(o); }
    }

    // ---- static callbacks: names and JNI signatures are looked up by native code (jni_utils.cpp) ----
    public static void eventProperty(String property, long value) {
        synchronized (observers) { for (EventObserver o : observers) o.eventProperty(property, value); }
    }
    public static void eventProperty(String property, boolean value) {
        synchronized (observers) { for (EventObserver o : observers) o.eventProperty(property, value); }
    }
    public static void eventProperty(String property, double value) {
        synchronized (observers) { for (EventObserver o : observers) o.eventProperty(property, value); }
    }
    public static void eventProperty(String property, String value) {
        synchronized (observers) { for (EventObserver o : observers) o.eventProperty(property, value); }
    }
    public static void eventProperty(String property) {
        synchronized (observers) { for (EventObserver o : observers) o.eventProperty(property); }
    }
    public static void event(int eventId) {
        synchronized (observers) { for (EventObserver o : observers) o.event(eventId); }
    }
    public static void eventEndFile(int reason, int error) {
        synchronized (observers) { for (EventObserver o : observers) o.eventEndFile(reason, error); }
    }
    public static void logMessage(String prefix, int level, String text) {
        synchronized (logObservers) { for (LogObserver o : logObservers) o.logMessage(prefix, level, text); }
    }
}
