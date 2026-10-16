# The native library looks up this class and its static callbacks by name (JNI).
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVLib$EventObserver { *; }
-keep class is.xyz.mpv.MPVLib$LogObserver { *; }
