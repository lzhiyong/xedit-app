plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    namespace = "x.code.app"

    defaultConfig {
        applicationId = "x.code.app"
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
}

dependencies {
    // Dependency on a local library module editor
    implementation(project(":alerter"))
    implementation(project(":crash"))
    implementation(project(":editor"))
    implementation(project(":document"))
    implementation(project(":piecetable"))
    implementation(project(":treesitter"))
    implementation(project(":treeview"))
    
    // Dependency on local binaries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.annotation)
    // To use the Java-compatible @Experimental API annotation
    implementation(libs.annotation.experimental)
    implementation(libs.constraintlayout)
    implementation(libs.core)
    implementation(libs.fragment)
    
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    // Use the kotlin reflect
    implementation(libs.kotlin.reflect)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.ui)
    implementation(libs.navigation.fragment)
    implementation(libs.material)
    
    // Use okhttp3 and retrofit2
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.preference)
    implementation(libs.recyclerview)
    
    // SQLite database components
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    
    // To use Kotlin Symbol Processing (KSP)
    ksp(libs.room.compiler)
}
