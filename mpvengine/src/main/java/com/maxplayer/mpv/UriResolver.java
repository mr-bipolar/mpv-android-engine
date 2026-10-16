package com.maxplayer.mpv;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Turns app-level locations into something mpv can open, and reads playlist text.
 *
 * <h3>{@code content://} URIs</h3>
 * mpv-android's approach (verified in {@code MPVActivity.translateContentUri}): open the descriptor,
 * try to resolve it to a real readable file path via {@code /proc/self/fd/N}; if that fails pass the
 * {@code content://} URI itself to mpv, which FFmpeg's {@code content:} protocol (built with JNI support)
 * opens through the application context. Requirements for the host app:
 * <ul>
 *   <li>The app must hold read permission for the URI: the grant that comes with an
 *       {@code ACTION_OPEN_DOCUMENT}/{@code ACTION_GET_CONTENT} result or an intent, ideally persisted with
 *       {@code ContentResolver.takePersistableUriPermission()} for later sessions.</li>
 *   <li>Without a persisted grant, playback of a stored URI fails after the process restarts.</li>
 *   <li>Real-path resolution only works for URIs backed by readable files; otherwise FFmpeg reads via the provider.</li>
 * </ul>
 */
public final class UriResolver {
    private UriResolver() {}

    /** @return a string suitable for mpv's {@code loadfile}. Blocks briefly on I/O: call off the main thread. */
    public static String toMpvPath(Context context, String uri) {
        if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("uri is empty");
        if (uri.startsWith("/")) return uri;
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme();
        if (scheme == null) return uri;
        switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
            case "content": return translateContent(context, u);
            case "file": return u.getPath() != null ? u.getPath() : uri;
            case "data": return "data://" + u.getSchemeSpecificPart(); // as mpv-android does
            default: return uri;
        }
    }

    private static String translateContent(Context context, Uri uri) {
        if (context != null) {
            try (android.os.ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    String real = realPath(pfd.getFd());
                    if (real != null) return real;
                }
            } catch (Exception ignored) { /* fall through to passing the URI */ }
        }
        return uri.toString();
    }

    static String realPath(int fd) {
        try {
            String path = new File("/proc/self/fd/" + fd).getCanonicalPath();
            if (!path.startsWith("/proc") && new File(path).canRead()) {
                try (InputStream in = new FileInputStream(path)) { in.read(); }
                return path;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Result of reading a playlist: its text and the location to resolve relative entries against. */
    public static final class Text {
        public final String text, baseUri;
        Text(String t, String b) { text = t; baseUri = b; }
    }

    /**
     * Reads M3U text from http(s), {@code file:}/path or {@code content:} (max {@code maxBytes}).
     * Blocking; call on a worker thread.
     */
    public static Text readText(Context context, String uri, int maxBytes, int timeoutMs) throws IOException {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs); c.setInstanceFollowRedirects(true);
            try {
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                String finalUrl = c.getURL().toString();
                return new Text(readAll(c.getInputStream(), maxBytes), finalUrl);
            } finally { c.disconnect(); }
        }
        if (uri.startsWith("content://")) {
            if (context == null) throw new IOException("a Context is required to read content:// URIs");
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
                if (in == null) throw new IOException("cannot open " + uri);
                return new Text(readAll(in, maxBytes), uri);
            }
        }
        String path = uri.startsWith("file://") ? Uri.parse(uri).getPath() : uri;
        try (InputStream in = new FileInputStream(path)) {
            return new Text(readAll(in, maxBytes), new File(path).toURI().toString());
        }
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024]; int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("playlist larger than " + max + " bytes");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
