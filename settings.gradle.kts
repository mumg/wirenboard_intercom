pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
        maven("https://download.linphone.org/releases/android/maven_repository")
        maven("https://download.linphone.org/releases/maven_repository")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
        maven("https://download.linphone.org/releases/android/maven_repository")
        maven("https://download.linphone.org/releases/maven_repository")
    }
}

rootProject.name = "Intercom"
include(":app")
