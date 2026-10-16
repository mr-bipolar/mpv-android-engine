package com.maxplayer.mpv;

/** Thrown for API misuse or when the native engine cannot be created/initialised. */
public class MpvException extends RuntimeException {
    public MpvException(String message) { super(message); }
    public MpvException(String message, Throwable cause) { super(message, cause); }
}
