# lsp4j reads and writes its messages with Gson, by reflection over the protocol
# classes, and calls the services through java.lang.reflect.Proxy: keep them as
# they are when the app is minified.
-keep class org.eclipse.lsp4j.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn org.eclipse.lsp4j.**
-dontwarn com.google.gson.**

# the client of this module is called by lsp4j the same way
-keep class x.github.module.lsp.** { *; }
