pluginManagement {
    repositories {
        // ---- 阿里云镜像：国内优先命中，显著加快依赖拉取 ----
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // ---- 官方仓库兜底 ----
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
        // ---- 阿里云镜像：AndroidX / Material / 三方库 ----
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // ---- 官方仓库兜底 ----
        google()
        mavenCentral()
    }
}

rootProject.name = "CurrentPage"
include(":app")
