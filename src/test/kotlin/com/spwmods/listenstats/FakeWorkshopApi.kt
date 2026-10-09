@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.ActionShortcut
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.KeyBindingManager
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Consumer

/**
 * 测试用的假宿主
 *
 * 签名对齐 [WorkshopApi]（0.1.0-dev21）。插件本体只使用 1.18.5 也存在的成员，
 * 新版本多出来的成员在这里实现为空操作——既让测试能编译，
 * 也顺便保证插件本体不会越界调用只在 1.19.0+ 才有的方法。
 */
internal class FakeApi(private val dir: Path) : WorkshopApi {

    val fakeUi = FakeUi()

    override val playback: WorkshopApi.Playback = FakePlayback()
    override val ui: WorkshopApi.Ui get() = fakeUi
    override val manager: WorkshopApi.Manager = FakeManager(dir)
    override val library: WorkshopApi.Library = FakeLibrary()
}

internal class FakePlayback : WorkshopApi.Playback {
    override fun getCurrentMediaItem(): MediaItem? = null
    override fun changeExclusive(exclusive: Boolean) {}
    override fun pause() {}
    override fun play() {}
    override fun previous() {}
    override fun next() {}
    override fun seekTo(position: Long) {}
}

internal class FakeUi : WorkshopApi.Ui {
    val messages = ArrayList<String>()

    override fun toast(text: String, type: WorkshopApi.Ui.ToastType) {
        messages += text
    }
}

internal class FakeManager(private val dir: Path) : WorkshopApi.Manager {
    override fun createConfigManager(pluginId: String): ConfigManager = FakeConfigManager(dir)
    override fun createConfigManager(): ConfigManager = FakeConfigManager(dir)
    override val keyBindingManager: KeyBindingManager = FakeKeyBindingManager()
}

internal class FakeKeyBindingManager : KeyBindingManager {
    override fun register(
        actionId: String,
        title: String,
        defaultShortcut: ActionShortcut?,
        hasGlobal: Boolean,
        handler: Runnable
    ): AutoCloseable = AutoCloseable { }

    override fun unregister(actionId: String) {}
}

internal class FakeLibrary : WorkshopApi.Library {
    override fun getTrackById(id: String): CompletionStage<MediaItem?> =
        CompletableFuture.completedFuture(null)

    override fun getCoverById(id: String): CompletionStage<ByteArray?> =
        CompletableFuture.completedFuture(null)

    override fun getAllTracks(): CompletionStage<List<MediaItem>> =
        CompletableFuture.completedFuture(emptyList())

    override fun getTracks(afterId: String?, limit: Int): CompletionStage<List<MediaItem>> =
        CompletableFuture.completedFuture(emptyList())
}

internal class FakeConfigManager(private val dir: Path) : ConfigManager {
    override fun getConfig(): ConfigHelper = FakeConfigHelper(dir)
    override fun getConfig(fileName: String): ConfigHelper = FakeConfigHelper(dir)
    override fun addConfigChangeListener(listener: Consumer<ConfigHelper>) {}
    override fun addConfigChangeListener(fileName: String, listener: Consumer<ConfigHelper>) {}
    override fun removeConfigChangeListener(listener: Consumer<ConfigHelper>) {}
}

internal class FakeConfigHelper(private val dir: Path) : ConfigHelper {
    override fun <T> get(key: String, defaultValue: T): T = defaultValue
    override fun set(key: String, value: Any) {}
    override fun save(): Boolean = true
    override fun reload(): Boolean = true
    override fun getConfigPath(): Path = dir.resolve("config.json")
}

internal fun testContext(dir: Path) = PluginContext(
    pluginId = "com.spwmods.listenstats",
    pluginVersion = "1.1.2",
    pluginPath = dir.toString(),
    spwVersion = "1.18.5",
    spwChannel = Channel.Steam
)

internal fun track(path: String, title: String) = MediaItem(
    title = title,
    artist = "测试歌手",
    album = "测试专辑",
    albumArtist = "测试歌手",
    path = path
)
