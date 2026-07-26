pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "bess-sales-trainer"

include(":app")

include(":core:common")
include(":core:model")
include(":core:database")
include(":core:data")
include(":core:network")
include(":core:audio")
include(":core:corpus")
include(":core:designsystem")

include(":feature:home")
include(":feature:vocabulary")
include(":feature:scenario")
include(":feature:settings")

include(":tools:corpus-packager")
