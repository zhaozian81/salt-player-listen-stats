import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.kapt)
    id("com.xuncorp.spw.workshop") version "0.1.0-dev21"
}

group = "com.spwmods.listenstats"
version = "1.1.5"

val pluginVersion = version.toString()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    // 宿主自带 Kotlin 标准库，插件不能重复打包
    compileOnly(kotlin("stdlib"))

    // 上游 API 只用于编译（宿主在运行时会提供），且不需要它的传递依赖（Compose 等）
    compileOnly(libs.spw.workshop.api) { isTransitive = false }
    compileOnly(libs.pf4j)

    // pf4j 的注解处理器生成 META-INF/extensions.idx，插件缺了它扩展点不会被发现
    kapt(libs.pf4j)

    testImplementation(libs.junit)
    testImplementation(libs.spw.workshop.api) { isTransitive = false }
    testImplementation(libs.pf4j)
}

// 单元测试里不要真的去弹浏览器
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

spmod {
    PluginClass = "com.spwmods.listenstats.ListenStatsPlugin"
    PluginId = "com.spwmods.listenstats"
    PluginName = "听歌统计"
    PluginDescription = "统计每首歌曲的播放次数与累计收听时长（AI 生成 · 人类测试发布）"
    PluginVersion = pluginVersion
    PluginProvider = "spwmods"
    PluginHasConfig = true
    PluginOpenSourceUrl = "https://github.com/zhaozian81/salt-player-listen-stats"
}

// ---------------------------------------------------------------------------
// SPW 1.18.5 的加载器只扫描 .zip / .jar，不认识上游约定的 .spmod；
// 而 .spmod 留给以后支持该后缀的版本。这里额外产出一份内容完全相同的 .zip。
// 详见 docs/host-compat.md
// ---------------------------------------------------------------------------
val pluginZip by tasks.registering(Copy::class) {
    group = "build"
    description = "把 .spmod 分发包复制一份为 .zip（兼容 SPW 1.18.5 的加载器）"
    dependsOn("plugin")

    val spmod = tasks.named<Zip>("plugin").flatMap { it.archiveFile }
    from(spmod)
    into(layout.buildDirectory.dir("libs"))
    rename { name -> name.removeSuffix(".spmod") + ".zip" }
}
