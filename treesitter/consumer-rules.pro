# Rules for the app that uses this module, applied when it is minified with R8.
#
# The native side (src/main/cpp, see jni_helper.cpp) registers its methods by name
# (RegisterNatives), and looks classes, fields, constructors and enum constants up
# by name (FindClass, GetFieldID, GetMethodID): none of them may be renamed, removed
# or merged, not even those Kotlin code never touches.
-keep class x.github.module.treesitter.** { *; }

# The Kotlin classes the native side builds or reads, by their names.
-keep class kotlin.Pair { <init>(java.lang.Object, java.lang.Object); }
-keep class kotlin.Triple { <init>(java.lang.Object, java.lang.Object, java.lang.Object); }
-keep class kotlin.UInt { int data; }
-keep class kotlin.UShort { short data; }
-keep interface kotlin.jvm.functions.Function1 { java.lang.Object invoke(java.lang.Object); }
-keep interface kotlin.jvm.functions.Function2 { java.lang.Object invoke(java.lang.Object, java.lang.Object); }
