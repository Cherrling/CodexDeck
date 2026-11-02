pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CodexDeck"
include(":app")
include(":protocol")
include(":terminal-emulator")
include(":terminal-view")
project(":terminal-emulator").projectDir = file("third-party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third-party/termux/terminal-view")
