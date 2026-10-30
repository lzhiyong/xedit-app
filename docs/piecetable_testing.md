# piecetable: Testing

> Part of the documentation of the [piecetable](../piecetable/README.md) module · [中文](piecetable_testing_cn.md)

All test cases live in **`src/sharedTest`** and run twice: once on the JVM
(`testDebugUnitTest`) and once on a device (`connectedDebugAndroidTest`). Only tests that need a
real device stay in `androidTest`.

```
src/
├── main/java/x/github/module/piecetable/        # implementation
├── sharedTest/java/x/github/module/piecetable/  # runs on both JVM and device
│   ├── TestSupport.kt                   # shared assertions, red-black tree invariants
│   ├── PieceTreeBaseTest.kt             # the VSCode suite (86 cases)
│   ├── PieceTreeBuilderTest.kt          # chunking, BOM, EOL sniffing, surrogate pairs
│   ├── PieceTreeEditingTest.kt          # edits, positions, CRLF, snapshots
│   ├── PieceTreeTextBufferApiTest.kt    # applyEdits, undo data, ranges, CharSequence
│   ├── PieceTreeSearchTest.kt           # word / regex / multiline search
│   ├── PieceTreeEditOperationTest.kt    # getInverseEditRanges, toSingleEditOperation
│   ├── PieceTreeUndoRedoTest.kt         # undo / redo, no coroutines and no wall clock
│   ├── PieceTreeStressTest.kt           # seeded random tests, large documents
│   ├── PieceTreeMaintenanceTest.kt      # compact, storage stats, shared state recovery
│   ├── PieceTreeSnapshotTest.kt         # snapshots across edits / compact / setEOL, reads during edits
│   ├── PieceTreeUndoRoundTripTest.kt    # random applyEdits + undo / redo on CRLF and mixed breaks
│   ├── PieceTreeSearchOracleTest.kt     # random find() against naive references
│   ├── PieceTreeSliceTest.kt            # slice undo / redo: random round trips, compact / setEOL, memory
│   ├── RandomEditing.kt                 # String model, random edits and positions for the random tests
│   └── RegressionTest.kt                # one case per fixed bug (49), do not delete
└── androidTest/java/x/github/module/piecetable/
    └── AndroidDeviceTest.kt             # API level sensitive paths, on device timings
```

Wire the shared source set into both test variants:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // java + kotlin: with AGP's built-in Kotlin (no org.jetbrains.kotlin.android plugin)
        // Kotlin files are only picked up through kotlin.srcDir
        getByName("test") {
            java.srcDir("src/sharedTest/java")
            kotlin.srcDir("src/sharedTest/java")
        }
        getByName("androidTest") {
            java.srcDir("src/sharedTest/java")
            kotlin.srcDir("src/sharedTest/java")
        }
    }
    testOptions {
        unitTests.all {
            // buffers share no mutable state, so test classes may also run in parallel
            // (e.g. maxParallelForks = 2); nothing has to be configured here
            it.forkEvery = 0
        }
    }
}

dependencies {
    // JVM: JUnit 5 (tasks.withType<Test> { useJUnitPlatform() })
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // device: AndroidJUnitRunner is JUnit 4, so kotlin.test must be the JUnit 4 flavour
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
```

The tests touch `internal` members (`PieceTreeBase`, `TreeNode`, the sentinel). AGP compiles both
`test` and `androidTest` as friends of the main variant, so no extra configuration is needed.

## `Unresolved reference 'TestSupport'` in androidTest

`TestSupport` lives in `src/sharedTest`. The androidTest compilation only sees it when that
directory is attached to the `androidTest` source set, so the error means it is not (an
`internal` visibility problem would read "Cannot access", not "Unresolved reference"):

1. Make sure the module's `build.gradle.kts` has the `sourceSets` block above for **both** `test`
   and `androidTest`, each with `java.srcDir` **and** `kotlin.srcDir`. A module without the
   `org.jetbrains.kotlin.android` plugin uses AGP's built-in Kotlin, which only picks Kotlin files
   up through `kotlin.srcDir`; with `java.srcDir` alone the JVM tests may pass while
   `AndroidDeviceTest`, the only file in androidTest, cannot find `TestSupport`.
2. `./gradlew :piecetable:sourceSets` prints the directories of every source set; the
   `androidTest` entry must list `src/sharedTest/java`.
3. If the directory is listed but Kotlin files from it are still not compiled, check that the
   `kotlin.srcDir` line is there for `androidTest` too.
4. Android Studio may mark code in a directory shared by two source sets as unresolved while
   the Gradle build is fine; the command line is the reference.
5. A build that does not go through AGP ignores `sourceSets`: copy (or symlink) the files of
   `src/sharedTest/java` into `src/androidTest/java`.

## The device run compiles but runs almost nothing

`AndroidJUnitRunner` is a JUnit 4 runner. With `kotlin-test-junit5` on the androidTest classpath,
`kotlin.test.Test` is the JUnit 5 annotation: every shared test is skipped without an error, only
`AndroidDeviceTest` (which uses `org.junit.Test`) runs, and `@BeforeTest` is never called. Use
`kotlin-test-junit` for androidTest and keep `kotlin-test-junit5` for the JVM tests; the shared
sources only use `kotlin.test.*`, so they compile against either.

## `Space characters in SimpleName ... are not allowed prior to DEX version 040`

Test names in backticks may contain spaces on the JVM, but DEX only accepts them from API 30 on.
If the module's `minSdk` is below 30 (an unset `minSdk` means 1), dexing the androidTest APK
fails on names like `` `random insert and delete bug 2` ``. Replace the spaces with underscores
in all shared tests (116 names in PieceTreeBaseTest, PieceTreeEditOperationTest and
PieceTreeUndoRedoTest; the names stay unique):

```bash
perl -pi -e 's/fun `([^`]*)`/"fun `" . ($1 =~ tr| |_|r) . "`"/ge' \
    src/sharedTest/java/x/github/module/piecetable/*.kt
```

Also set `minSdk` in `defaultConfig` to the app's value, so the device tests are built for the
API levels the library really supports.

```bash
# JVM, the whole shared suite
./gradlew :piecetable:testDebugUnitTest

# on a device or emulator, the shared suite plus AndroidDeviceTest
./gradlew :piecetable:connectedDebugAndroidTest

# a single class on the JVM
./gradlew :piecetable:testDebugUnitTest --tests "*RegressionTest"

# a single class on a device
./gradlew :piecetable:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=x.github.module.piecetable.RegressionTest
```

Every random test uses a fixed seed (`PieceTreeStressTest.SEEDS`, `PieceTreeBaseTest.SEED`,
`PieceTreeUndoRoundTripTest.SEEDS`, `PieceTreeSearchOracleTest.SEEDS`) so a failure reproduces
exactly; their messages name the seed and the step. Add seeds to widen coverage; do not go back to `Math.random()`.

A regression test for a former endless loop should wrap the call in
`TestSupport.runWithTimeout { ... }`: a reintroduced bug then fails that case after 10 seconds
instead of hanging the whole run.

Two things to keep in mind when adding tests:

- `sharedTest` must stay free of `android.*` and `androidx.*`, otherwise the JVM variant will not
  compile. Device specific cases belong in `androidTest`.
- Parallel test execution is fine: buffers share no mutable state. `TestSupport.resetGlobalState()`
  only remains as a cheap safety net for the existing `@BeforeTest` hooks.
