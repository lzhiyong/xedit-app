# bypass

English | [中文](README_CN.md)

Lifts the restrictions of Android 9 and later on non-SDK interfaces (hidden APIs), so the app can reach them through reflection.

**X Editor does not use this module yet**; the app does not depend on it.

## Use

- **Automatic**: the module's manifest declares `BypassProvider`, which lifts the restrictions when the app starts (before `Application.onCreate`). Adding the module is enough; do not create or call it yourself.
- **By hand**: `Reflection.bypass { /* hidden APIs are reachable from here */ }`.
- `Reflection.getDeclaredMethod` and `Reflection.getDeclaredField` look members up the same way, past the checks.

## How it works

It calls `VMRuntime.setHiddenApiExemptions(["L"])`, exempting every class (`L` starts every class descriptor). `getRuntime` and `setHiddenApiExemptions` are hidden APIs themselves, so they are reached in ways that pass the checks:

- **Android 9**: double reflection, calling `Class.getDeclaredMethod` itself through reflection.
- **Android 10+**: from JNI, on a native thread attached to the VM. That thread has no Java frames, and the hidden API check goes by the caller, so it finds no app to restrict.

## Limits

- It relies on how ART checks hidden APIs and may stop working on later Android versions.
- Google Play restricts non-SDK interfaces; check its policy before publishing.
- Native code is built for `arm64-v8a` only.

## Tests

`./gradlew :bypass:testDebugUnitTest`: `ReflectionTest` tests the double reflection path on the JVM (private methods and fields by name, the error for a missing member), and that `bypass()` fails on the JVM (it has no `VMRuntime`). The bypass itself and the JNI path can only be checked on a device.
