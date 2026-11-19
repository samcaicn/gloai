pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Maven Central 偶尔对 GitHub Actions 限流/超时，GCS 镜像兜底
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Maven Central 偶尔对 GitHub Actions 限流/超时，GCS 镜像兜底
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "Mika"
include(":core", ":app")
