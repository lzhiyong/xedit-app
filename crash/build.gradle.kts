import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "x.github.module.crash"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_APP_PLATFORM=android-21", "-DANDROID_STL=c++_static")
                abiFilters("arm64-v8a")
            }
        }
    }
    
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
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
        prefab = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    
    // Use the kotlin reflect
    implementation(libs.kotlin.reflect)

    // Use the Kotlin JUnit 5 integration.
    testImplementation(libs.kotlin.test)
}
