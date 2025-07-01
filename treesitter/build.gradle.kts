plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "x.github.module.treesitter"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_APP_PLATFORM=android-21", "-DANDROID_STL=c++_static")
                abiFilters("arm64-v8a")
                cFlags("-fcolor-diagnostics")
            }
        }
    }
    
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")          
            version = libs.versions.cmake.get()
        }
    }
    
    packaging {
        jniLibs.excludes.add("**/libtree-sitter*.so")
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
	
	kotlinOptions {
        jvmTarget = "21"
    }
    
	buildFeatures {
        viewBinding = true
    }
    
    // clone the treesitter grammars repositories
    val clone = task<Exec>("clone") {
        workingDir(".")
        commandLine("python", "${project.rootDir}/treesitter/get_sources.py")
    }
    tasks.findByName("preBuild")?.dependsOn(clone)
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    // Use the kotlin reflect
    implementation(libs.kotlin.reflect)
    // Use the Kotlin JUnit 5 integration.
    testImplementation(libs.kotlin.test)
}
