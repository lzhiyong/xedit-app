import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "x.github.module.treeview"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    // Every public declaration must state its visibility and type explicitly,
    // which keeps the library's public API deliberate and reviewable.
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Exposed as `api` because RecyclerView types are part of this library's public API.
    api(libs.recyclerview)
    
    // Use the Kotlin JUnit 5 integration.
    testImplementation(libs.kotlin.test.junit5)
    
    // For android test
    androidTestImplementation(libs.kotlin.test.junit5)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.runner)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// unit tests run on JUnit 5 (kotlin.test's junit5 flavour): without this Gradle finds none
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
