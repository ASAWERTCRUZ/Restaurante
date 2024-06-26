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
        // Mapbox Maven repository
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            // Do not change the username below. It should always be "mapbox" (not your username).
            credentials {
                // Do not change the username below.
                // This should always be `mapbox` (not your username).
                username = "mapbox"
                // Use the secret token directly here
                password = "sk.eyJ1IjoiZ2VkZTEwIiwiYSI6ImNsbTc4b2NqejFza3QzanBlNjA3N3llc2kifQ.1tkvpla2xuRwrtPig94n8A"
            }
            authentication.create<BasicAuthentication>("basic")
        }
    }
}

rootProject.name = "RestauranteGg"
include(":app")
