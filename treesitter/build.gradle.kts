import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "x.github.module.treesitter"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static")
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

// Clone the tree-sitter sources (src/main/cpp/tree-sitter) before the build. The branch
// or tag is treeSitterBranch of gradle.properties (default master); for one build:
// ./gradlew assembleDebug -PtreeSitterBranch=v0.25.x. A checkout already of that branch
// is left as it is, without going to the network (see get_treesitter_source.py). The
// interpreter can be changed with -Ppython=python3.
val treeSitterBranch = providers.gradleProperty("treeSitterBranch").orElse("master")
val python = providers.gradleProperty("python").orElse("python")
val clone = tasks.register<Exec>("clone") {
    group = "build setup"
    description = "Clones the tree-sitter sources into src/main/cpp/tree-sitter"
    workingDir(projectDir)
    commandLine(
        python.get(),
        file("get_treesitter_source.py").absolutePath,
        "--branch", treeSitterBranch.get()
    )
}
// (matched rather than looked up: there is no preBuild until AGP makes it)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(clone) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    // Use the kotlin reflect
    implementation(libs.kotlin.reflect)
    // Use the Kotlin JUnit 5 integration.
    testImplementation(libs.kotlin.test.junit5)
}

// unit tests run on JUnit 5 (kotlin.test's junit5 flavour): without this Gradle finds none
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
