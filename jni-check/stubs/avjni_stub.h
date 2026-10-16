#pragma once
// desktop FFmpeg headers lack the Android-only declaration used by main.cpp
extern "C" int av_jni_set_android_app_ctx(void *app_ctx, void *log_ctx);
