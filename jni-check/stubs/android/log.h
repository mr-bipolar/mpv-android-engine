#pragma once
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_ERROR 6
extern "C" int __android_log_print(int prio, const char *tag, const char *fmt, ...);
