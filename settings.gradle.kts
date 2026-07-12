pluginManagement {
    repositories {
        // 1. 阿里云 Gradle 插件镜像 (放在最前面)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 2. 阿里云 Google 镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // 3. 阿里云公共仓库 (包含 mavenCentral 和 jcenter)
        maven { url = uri("https://maven.aliyun.com/repository/public") }

        // 官方仓库作为后备
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 强制所有模块使用这里的仓库配置，覆盖模块级的 build.gradle
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 1. 阿里云 Google 镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // 2. 阿里云公共仓库 (包含 mavenCentral 和 jcenter)
        maven { url = uri("https://maven.aliyun.com/repository/public") }

        // 3. JitPack (很多第三方开源库在这里，阿里云没有代理 JitPack，必须保留)
        maven { url = uri("https://jitpack.io") }

        // 官方仓库作为后备
        google()
        mavenCentral()
    }
}

rootProject.name = "MyPhysicalDeadZone"
include(":app")