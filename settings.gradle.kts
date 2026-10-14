pluginManagement {
    repositories {
        // 国内镜像优先（本机无 VPN，外网源不稳定）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "LotusWallet"

include(":app")

include(":core:common")
include(":core:crypto")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:designsystem")

include(":chain:bitcoin")
include(":chain:evm")
include(":chain:tron")
include(":chain:alt")
