pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            setUrl("https://jitpack.io")
        }
    }
    
    versionCatalogs {
        create("libs") {
            from(files("libs.versions.toml"))
        }
    }
}

rootProject.name = "xedit-app"
include(
    ":app",
    ":bypass",
    ":crash", 
    ":editor", 
    ":lsp",
    ":document", 
    ":piecetable", 
    ":treesitter",
    ":treeview"
)
