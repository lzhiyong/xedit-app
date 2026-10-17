# bypass

Lifts the restrictions on non-SDK (hidden) interfaces of Android 9 and later, so that an
app can reach them by reflection.

中文说明见 [README_cn.md](README_cn.md).

**X Editor does not use it**: the app does not depend on this module.

## How it works

`Reflection.bypass()` calls `VMRuntime.setHiddenApiExemptions(["L"])`: every class is
exempt (`L` is the start of every class descriptor). `VMRuntime.getRuntime` and
`setHiddenApiExemptions` are hidden themselves, so they are looked up in a way the check
lets through:

- **Android 9**: by meta-reflection, `Class.getDeclaredMethod` itself called through
  reflection;
- **Android 10 and later**: through JNI (`bypass.cpp`), on a native thread attached to the
  VM. That thread has no Java frames, so the check, which looks at who is calling, finds no
  app to restrict.

## Use

- **Automatically**: `BypassProvider`, declared in the module's manifest, runs the bypass
  when the app starts, before `Application.onCreate`. Never create it or call it yourself.
- **By hand**: `Reflection.bypass { /* hidden APIs can be reached now */ }`.
- `Reflection.getDeclaredMethod` and `Reflection.getDeclaredField` look up members the same
  way.

## Caveats

- It relies on how ART checks hidden APIs, and may stop working on a later Android.
- Google Play restricts the use of non-SDK interfaces: check its policies before you ship
  this.
- Native code is built for `arm64-v8a` only.

## Tests

`ReflectionTest`, on the JVM (`./gradlew :bypass:testDebugUnitTest`). `Build.VERSION.SDK_INT`
is 0 there, so it tests the meta-reflection path: a private method and a private field by
name, and the error for a member that is not there. It also checks that `bypass()` fails on
the JVM, which has no `dalvik.system.VMRuntime`. The bypass itself, and the JNI path, only
work on a device.
