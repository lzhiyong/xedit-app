plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.module.editor"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    
    defaultConfig {
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
	
	kotlinOptions {
        jvmTarget = "17"
    }
    
	buildFeatures {
	    buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(project(":bypass"))
    implementation(project(":piecetable"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    
    implementation(libs.core)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.material)
    
    // Use the Kotlin JUnit 5 integration.
    testImplementation(libs.kotlin.test)
}
