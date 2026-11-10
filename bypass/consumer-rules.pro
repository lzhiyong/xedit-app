# Rules for the app that uses this module, applied when it is minified with R8.
#
# The native side (src/main/cpp/bypass.cpp) registers its methods on JNI by name, and
# the module calls hidden APIs by reflection: keep it as it is.
-keep class x.github.module.bypass.** { *; }
