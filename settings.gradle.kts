pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // 本地离线构建时，可先把上游打包插件发布到 mavenLocal：
        //   cd spw-workshop-api && ./gradlew :gradle-plugin:publishToMavenLocal
        mavenLocal()
        maven("https://jitpack.io")
        // 国内镜像（可选，放在官方源之后作为加速/兜底）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
    }
    resolutionStrategy {
        eachPlugin {
            // 上游的打包插件只发布在 JitPack，坐标与插件 ID 不同名，需要显式映射
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule(
                    "com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        // 自带的上游 API（JitPack 上的 0.1.0-dev21 jar 是空包），见 local-repo/README.md
        maven { url = uri("${rootDir}/local-repo") }
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
    }
}

rootProject.name = "salt-player-listen-stats"
