import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "x.github.module.lsp"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // the same minSdk as the app (see the piecetable module)
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all {
            it.forkEvery = 0
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// The main code uses no Android API: every test is a local JVM test (src/test),
// with a fake language server on in-memory pipes. ./gradlew :lsp:testDebugUnitTest
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(PASSED, FAILED, SKIPPED)
        showExceptions = true
        showCauses = true
        showStandardStreams = true
    }
}

dependencies {
    // the protocol: its types and the JSON-RPC over streams
    api(libs.lsp4j)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
