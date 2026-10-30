# R8 rules of the app (release builds, see build.gradle.kts). The library modules bring
# their own (consumer-rules.pro: the JNI of treesitter, crash and bypass, the lsp4j
# protocol classes); AndroidX, Room, WorkManager, kotlinx.serialization, OkHttp and
# Retrofit ship theirs inside their libraries.

# Readable stack traces: the crash reports (the :crash process, the console) keep the
# line numbers; mapping.txt turns the obfuscated names back (retrace).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The font list of the editor settings reads the fonts of res/font from the
# fields of R.font (see EditorFonts.bundledFonts): keep them. The fonts themselves
# are kept from the resource shrinker by res/raw/keep.xml.
-keepclassmembers class x.editor.app.R$font {
    public static <fields>;
}

# Preferences named by their class in res/xml (pref_app.xml, pref_editor.xml) are
# created by reflection, with the constructor of a view inflated from XML.
-keep public class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Fragments are created again by their class name when the screen is restored
# (SettingsActivity's screens, the dialogs): the constructor without arguments stays.
-keepclassmembers public class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# lsp4j's JSON-RPC layer refers to classes of optional libraries that are not part of
# the app; they are never loaded.
-dontwarn org.eclipse.xtext.xbase.lib.**
-dontwarn org.eclipse.xtend.lib.**
