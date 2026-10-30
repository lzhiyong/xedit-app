import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "x.github.module.piecetable"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    
    defaultConfig {
        // Set the same minSdk as the app. Unset, a library defaults to 1, and the androidTest APK
        // is then dexed for API 1: test names with spaces fail to dex below API 30 (see README).
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
    
	buildFeatures {
        viewBinding = true
    }
    
    // The shared tests compile into both test variants. This module uses AGP's built-in Kotlin
    // (no org.jetbrains.kotlin.android plugin), which picks Kotlin files up through kotlin.srcDir:
    // "test" had it, "androidTest" did not, hence "Unresolved reference 'TestSupport'".
    sourceSets {
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
            // buffers share no mutable state any more, test classes may also run in parallel
            it.forkEvery = 0
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()    
    testLogging {
        events(PASSED, FAILED, STANDARD_OUT, STANDARD_ERROR, SKIPPED)        
        showExceptions = true
        showCauses = true        
        showStandardStreams = true
    }
    
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, result ->
        if (suite.parent == null) {
            println(
                "\nresult ${result.resultType}  " +
                "total: ${result.testCount}，Passed: ${result.successfulTestCount}，" +
                "Failure: ${result.failedTestCount}，Skiped: ${result.skippedTestCount}"
            )
        }
    }))
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.kotlinx.serialization.json)
    // Use the Kotlin JUnit 5 integration.
    testImplementation(libs.kotlin.test.junit5)
    
    // For android test. AndroidJUnitRunner is a JUnit 4 runner, so kotlin.test has to map onto
    // JUnit 4 here: with kotlin-test-junit5, kotlin.test.Test becomes the JUnit 5 annotation and
    // every shared test is silently skipped on the device (and @BeforeTest never runs).
    // Catalog entry: kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "<same as kotlin-test-junit5>" }
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.runner)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
