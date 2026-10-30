# document: Testing

> Part of the documentation of the [document](../document/README.md) module · [中文](document_testing_cn.md)

```
src/
├── test/                          JVM tests (Robolectric), no device needed
│   ├── DocumentFlagsTest          readable / writable / virtual rules
│   ├── RawDocumentFileTest        file system implementation
│   ├── DocumentFileFactoryTest    Uri dispatch, behavior with unreachable providers
│   └── TempDirectory              temporary directory helper
└── androidTest/                   instrumented tests against a real DocumentsProvider
    ├── AndroidManifest.xml        registers FakeDocumentsProvider
    ├── FakeDocumentsProvider      provider backed by a cache directory; counts queries
    ├── FakeProviderFixture        fresh root directory per test
    ├── TreeDocumentFileTest
    └── SingleDocumentFileTest
```

The instrumented tests run the real framework code paths (`DocumentsContract`,
`DocumentsProvider`, permission checks), which Robolectric doesn't fully emulate. The fake
provider counts its queries, so the tests also verify the query counts in the table above.

The tests use `kotlin.test` for annotations and assertions. The only JUnit API they reference
directly is `@RunWith`, which selects the Robolectric or AndroidX runner; JUnit 4 itself comes
in through those libraries.

Add to the module's `build.gradle.kts` (use the latest versions available):

```kotlin
android {
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

If `kotlin.test.Test` or `BeforeTest` can't be resolved, the Kotlin Gradle plugin didn't pick
the JUnit 4 variant automatically; use `kotlin("test-junit")` instead of `kotlin("test")`.

Run them with:

```bash
./gradlew :document:testDebugUnitTest            # JVM tests
./gradlew :document:connectedDebugAndroidTest    # on a device or emulator
```
