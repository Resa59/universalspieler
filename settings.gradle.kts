pluginManagement {
    val localRepositoryProxy = System.getenv("WEEKLYDJ_REPO_PROXY")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        if (localRepositoryProxy != null) {
            maven("$localRepositoryProxy/google") { isAllowInsecureProtocol = true }
            maven("$localRepositoryProxy/central") { isAllowInsecureProtocol = true }
            maven("$localRepositoryProxy/plugins") { isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    val localRepositoryProxy = System.getenv("WEEKLYDJ_REPO_PROXY")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localRepositoryProxy != null) {
            maven("$localRepositoryProxy/google") { isAllowInsecureProtocol = true }
            maven("$localRepositoryProxy/central") { isAllowInsecureProtocol = true }
            maven("$localRepositoryProxy/jitpack") { isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
            maven("https://jitpack.io")
        }
    }
}

rootProject.name = "WeeklyDJShows"
include(
    ":app",
    ":core-model",
    ":data-database",
    ":data-feeds",
    ":playback",
    ":resolver-api",
    ":resolver-direct",
    ":resolver-newpipe",
    ":show-discovery",
    ":ui-components",
)
