import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    packaging {
        resources {
            // lsp4j and its JSON-RPC jar both carry these (see lsp/README.md): one copy of the
            // licences and notices is kept in the APK, Maven's dependency list is left out
            pickFirsts += setOf("about.html", "META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf("META-INF/DEPENDENCIES")
        }
    }

    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    namespace = "x.editor.app"

    defaultConfig {
        applicationId = "x.editor.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.1"
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
    
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // unit tests: kotlin.test on JUnit 5
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Dependency on a local library module editor
    implementation(project(":crash"))
    implementation(project(":editor"))
    implementation(project(":document"))
    implementation(project(":piecetable"))
    implementation(project(":treesitter"))
    implementation(project(":treeview"))
    // completion from language servers (the user's: the app ships none)
    implementation(project(":lsp"))
    
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
    implementation(libs.work.runtime)
    
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

// unit tests run on JUnit 5 (kotlin.test's junit5 flavour): without this Gradle finds none
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
