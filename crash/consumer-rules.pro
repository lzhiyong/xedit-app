# Rules for the app that uses this module, applied when it is minified with R8.
#
# The native side (src/main/cpp/crash_handler.cpp, anr_monitor.cpp) finds CrashHandler
# by its name, registers its native methods by name (RegisterNatives fails, and the
# library with it, if one is missing), and calls the static callbacks callback,
# onAnrSignal and onAnrConfirm, which no Kotlin code calls: keep all of it as it is.
-keep class x.github.module.crash.CrashHandler { *; }
