plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    namespace = "io.github.code.app"

    defaultConfig {
        applicationId = "io.github.code.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
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
    
    kapt {
        correctErrorTypes = true 
    } 
}

dependencies {
    // Dependency on a local library module editor
    implementation(project(":alerter"))
    implementation(project(":crash"))
    implementation(project(":editor"))
    implementation(project(":document"))
    implementation(project(":piecetable"))
    implementation(project(":treesitter"))
    
    // Dependency on local binaries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    
    implementation(libs.material)
    
    implementation(libs.core)
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    
    implementation(libs.annotation)
    // To use the Java-compatible @Experimental API annotation
    implementation(libs.annotation.experimental)
    
    implementation(libs.navigation.ui)
    implementation(libs.navigation.fragment)
    implementation(libs.fragment)
    implementation(libs.preference)
    
    implementation(libs.kotlinx.serialization.json)
    // Use the kotlin reflect
    implementation(libs.kotlin.reflect)
    
    // Use okhttp3 and retrofit2
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    
    // SQLite database components
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    // To use Kotlin annotation processing tool (kapt)
    kapt(libs.room.compiler)
    // To use Kotlin Symbol Processing (KSP)
    //ksp(libs.room.compiler)
}
