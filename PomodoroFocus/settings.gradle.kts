pluginManagement { repositories { google(); maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }; mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { maven { url = uri("https://maven.aliyun.com/repository/google") }; maven { url = uri("https://maven.aliyun.com/repository/public") }; google(); mavenCentral(); maven { url = uri("https://jitpack.io") } } }
rootProject.name = "PomodoroFocus"
include(":app")
