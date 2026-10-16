package com.maxplayer.mpv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authoritative playlist model for the player (thread-safe).
 *
 * <p><b>Design:</b> mpv's own playlist only ever holds the entry being played; this manager owns the
 * full list. Reasons: (1) a failing channel must not make mpv silently skip to the next channel,
 * (2) per-entry HTTP options (user-agent/referrer) can be attached to each load, (3) a 5000-entry IPTV
 * list is not copied into mpv. Auto-advance at end of file is performed by {@link MpvPlayer} using this
 * model. Obtain the instance with {@link MpvPlayer#getPlaylist()}.
 */
public final class PlaylistManager {

    /** Implemented by the player; separates the model from playback. */
    interface Host {
        void playEntry(PlaylistEntry entry, int index);
        void playlistStopped();
        void onPlaylistModelChanged(int position, int count);
    }

    private final Host host;
    private final List<PlaylistEntry> entries = new ArrayList<>();
    private int position = -1;
    private boolean loop;

    PlaylistManager(Host host) { this.host = host; }

    /** Replace the list. {@code startIndex} entry starts playing immediately unless it is -1. */
    public void setEntries(List<PlaylistEntry> list, int startIndex) {
        synchronized (this) {
            entries.clear(); entries.addAll(list);
            position = -1;
            if (startIndex >= entries.size()) throw new IllegalArgumentException("startIndex " + startIndex + " >= size " + entries.size());
        }
        if (startIndex >= 0) playIndex(startIndex); else notifyModel();
    }

    /** Parse M3U text and set it as the playlist. Throws if the text is an HLS manifest or has no valid entries. */
    public M3uParser.Result loadM3u(String text, M3uParser.Options options, boolean startPlaying) {
        if (M3uParser.looksLikeHlsManifest(text))
            throw new MpvException("This is an HLS stream manifest, not a channel playlist. Play it with MpvPlayer.load(url).");
        M3uParser.Result r = M3uParser.parse(text, options);
        if (r.getEntries().isEmpty())
            throw new MpvException("Playlist has no playable entries" + (r.getWarnings().isEmpty() ? "" : " (" + r.getWarnings().get(0) + ")"));
        setEntries(r.getEntries(), startPlaying ? 0 : -1);
        return r;
    }

    public synchronized List<PlaylistEntry> getEntries() { return Collections.unmodifiableList(new ArrayList<>(entries)); }
    public synchronized int size() { return entries.size(); }
    /** Index of the entry being played, -1 if none. */
    public synchronized int getPosition() { return position; }
    public synchronized PlaylistEntry getCurrent() { return position >= 0 && position < entries.size() ? entries.get(position) : null; }
    public synchronized boolean hasNext() { return position + 1 < entries.size() || (loop && !entries.isEmpty()); }
    public synchronized boolean hasPrevious() { return position > 0 || (loop && !entries.isEmpty()); }
    /** Wrap around at the ends when using next()/previous()/auto-advance. */
    public synchronized void setLoop(boolean loop) { this.loop = loop; }
    public synchronized boolean isLoop() { return loop; }

    public void add(PlaylistEntry e) {
        synchronized (this) { entries.add(e); }
        notifyModel();
    }
    public void insert(int index, PlaylistEntry e) {
        synchronized (this) {
            if (index < 0 || index > entries.size()) throw new IndexOutOfBoundsException("index " + index);
            entries.add(index, e);
            if (position >= index) position++;
        }
        notifyModel();
    }

    /** Remove an entry. Removing the playing entry stops playback if it was the last, else plays the next. */
    public void remove(int index) {
        boolean removedCurrent; int playNext = -1; boolean empty;
        synchronized (this) {
            if (index < 0 || index >= entries.size()) throw new IndexOutOfBoundsException("index " + index);
            entries.remove(index);
            removedCurrent = index == position;
            if (index < position) position--;
            empty = entries.isEmpty();
            if (removedCurrent) {
                position = -1;
                if (!empty) playNext = Math.min(index, entries.size() - 1);
            }
        }
        if (removedCurrent) { if (playNext >= 0) playIndex(playNext); else host.playlistStopped(); }
        else notifyModel();
    }

    public void clear() {
        boolean was;
        synchronized (this) { was = position >= 0; entries.clear(); position = -1; }
        if (was) host.playlistStopped(); else notifyModel();
    }

    public void playIndex(int index) {
        PlaylistEntry e;
        synchronized (this) {
            if (index < 0 || index >= entries.size()) throw new IndexOutOfBoundsException("index " + index + ", size " + entries.size());
            position = index; e = entries.get(index);
        }
        notifyModel();
        host.playEntry(e, index);
    }

    /** @return false if there is no next entry (and looping is off). */
    public boolean next() {
        int n;
        synchronized (this) {
            if (entries.isEmpty()) return false;
            if (position + 1 < entries.size()) n = position + 1; else if (loop) n = 0; else return false;
        }
        playIndex(n); return true;
    }

    public boolean previous() {
        int n;
        synchronized (this) {
            if (entries.isEmpty()) return false;
            if (position > 0) n = position - 1; else if (loop) n = entries.size() - 1; else return false;
        }
        playIndex(n); return true;
    }

    /** Find first entry whose URL equals {@code url}; -1 if none. */
    public synchronized int indexOfUrl(String url) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).getUrl().equals(url)) return i;
        return -1;
    }

    /** Called when something outside the playlist (e.g. MpvPlayer.load(url)) takes over playback. */
    void detachPosition() {
        boolean changed;
        synchronized (this) { changed = position != -1; position = -1; }
        if (changed) notifyModel();
    }

    private void notifyModel() {
        int p, c;
        synchronized (this) { p = position; c = entries.size(); }
        host.onPlaylistModelChanged(p, c);
    }
}
