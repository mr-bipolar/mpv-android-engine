package com.maxplayer.mpv;

import android.view.Surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory MpvBackend for deterministic unit tests: records everything, lets tests emit native events. */
final class FakeBackend implements MpvBackend {
    final List<String> optionsSet = new ArrayList<>();
    final Map<String, Integer> optionResult = new HashMap<>();
    final List<List<String>> commands = new ArrayList<>();
    final Map<String, String> props = new HashMap<>();
    final List<String> propSets = new ArrayList<>();
    final Map<String, Integer> observed = new HashMap<>();
    EventSink sink; boolean created, initialized, destroyed, attached;
    boolean sendNativeEndReason;

    @Override public void create() { created = true; }
    @Override public void initialize() { initialized = true; }
    @Override public void destroy() { destroyed = true; }
    @Override public int setOptionString(String n, String v) {
        optionsSet.add(n + "=" + v);
        Integer r = optionResult.get(n); return r == null ? 0 : r;
    }
    @Override public void command(String... a) { List<String> l = new ArrayList<>(); for (String s : a) l.add(s); commands.add(l); }
    @Override public Integer getPropertyInt(String n) { String v = props.get(n); return v == null ? null : (int) Double.parseDouble(v); }
    @Override public Double getPropertyDouble(String n) { String v = props.get(n); return v == null ? null : Double.valueOf(v); }
    @Override public Boolean getPropertyBoolean(String n) { String v = props.get(n); return v == null ? null : (v.equals("yes") || v.equals("true")); }
    @Override public String getPropertyString(String n) { return props.get(n); }
    @Override public void setPropertyInt(String n, int v) { propSets.add(n + "=" + v); props.put(n, Integer.toString(v)); }
    @Override public void setPropertyDouble(String n, double v) { propSets.add(n + "=" + v); props.put(n, Double.toString(v)); }
    @Override public void setPropertyBoolean(String n, boolean v) { propSets.add(n + "=" + (v ? "yes" : "no")); props.put(n, v ? "yes" : "no"); }
    @Override public void setPropertyString(String n, String v) { propSets.add(n + "=" + v); props.put(n, v); }
    @Override public void observeProperty(String n, int f) { observed.put(n, f); }
    @Override public void attachSurface(Surface s) { attached = true; }
    @Override public void detachSurface() { attached = false; }
    @Override public void setEventSink(EventSink s) { sink = s; }

    String lastCommand() { return commands.isEmpty() ? null : String.join(" ", commands.get(commands.size() - 1)); }
    long count(String firstArg) { long c = 0; for (List<String> l : commands) if (l.get(0).equals(firstArg)) c++; return c; }

    // helpers to simulate the native side
    void startFile() { sink.onEvent(Mpv.Event.START_FILE); }
    void fileLoaded() { sink.onEvent(Mpv.Event.FILE_LOADED); sink.onEvent(Mpv.Event.PLAYBACK_RESTART); }
    void endFile(int reason, int error) { if (sendNativeEndReason) sink.onEndFile(reason, error); sink.onEvent(Mpv.Event.END_FILE); }
    void flag(String n, boolean v) { props.put(n, v ? "yes" : "no"); sink.onPropertyChange(n, v); }
    void dbl(String n, double v) { props.put(n, Double.toString(v)); sink.onPropertyChange(n, v); }
}
