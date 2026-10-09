@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * 不依赖 SPW 宿主，直接用假宿主驱动扩展点，验证计数规则与落盘。
 * 假宿主的签名严格对齐 SPW 1.18.5 的旧版 API。
 */
class ListenStatsExtensionTest {

    @Before
    fun setUp() {
        StatsStore.resetForTesting()
    }

    @Test
    fun `听满 30 秒才计数且同一遍只计一次`() {
        val dir = newDir("a")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\a.mp3", "三十秒的歌"))
        extension.onIsPlayingChanged(true)

        repeat(30) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        assertEquals("听满 29 秒不该计数", 0L, countOf(pathOf("C:\\Music\\a.mp3")))

        extension.onPositionUpdated(31_000L)
        assertEquals("听满 30 秒应计 1 次", 1L, countOf(pathOf("C:\\Music\\a.mp3")))

        repeat(100) { index -> extension.onPositionUpdated(31_000L + (index + 1) * 1000L) }
        assertEquals("同一遍不能重复计数", 1L, countOf(pathOf("C:\\Music\\a.mp3")))

        // 自然播放结束 → 结算这一遍的收听时长
        extension.onStateChanged(PlaybackExtensionPoint.State.Ended)
        assertEquals("累计时长应落盘", 130_000L, msOf(pathOf("C:\\Music\\a.mp3")))
    }

    @Test
    fun `暂停期间不计入时长`() {
        val dir = newDir("b")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\b.mp3", "暂停测试"))
        extension.onIsPlayingChanged(true)

        repeat(20) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        extension.onIsPlayingChanged(false)

        // 暂停后宿主仍每秒回调，但播放位置并不前进，不应该被累计
        repeat(50) { index -> extension.onPositionUpdated(20_000L + (index + 1) * 1000L) }
        assertEquals("暂停期间的进度回调不该计数", 0L, countOf(pathOf("C:\\Music\\b.mp3")))
    }

    @Test
    fun `跳转不产生虚假的收听时长`() {
        val dir = newDir("c")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\c.mp3", "跳转测试"))
        extension.onIsPlayingChanged(true)

        extension.onPositionUpdated(1_000L)
        extension.onPositionUpdated(2_000L)
        // 用户拖到 10 分钟处：这 598 秒不是"听过"的时长
        extension.onSeekTo(600_000L)
        extension.onPositionUpdated(600_000L)
        extension.onPositionUpdated(601_000L)

        assertEquals("跳转不该让这首歌计数", 0L, countOf(pathOf("C:\\Music\\c.mp3")))
    }

    @Test
    fun `位置大幅回退视为换曲并重新开始计时`() {
        val dir = newDir("d")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\d1.mp3", "第一首"))
        extension.onIsPlayingChanged(true)
        repeat(20) { index -> extension.onPositionUpdated((index + 1) * 1000L) }

        // 位置从 20 秒跳回 1 秒：插件应先结算并关闭会话，等待新的歌词回调
        extension.onPositionUpdated(1_000L)
        extension.onPositionUpdated(2_000L)
        assertEquals(
            "回退期间不该给旧歌计数",
            0L,
            countOf(pathOf("C:\\Music\\d1.mp3"))
        )

        // 新的歌词回调到达，重新开始计时
        extension.onBeforeLoadLyrics(track("C:\\Music\\d2.mp3", "第二首"))
        repeat(31) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        assertEquals("新歌听满 30 秒应计数", 1L, countOf(pathOf("C:\\Music\\d2.mp3")))
    }

    @Test
    fun `换曲时上一首的时长会结算并写入元数据`() {
        val dir = newDir("e")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\e1.mp3", "第一首"))
        extension.onIsPlayingChanged(true)
        repeat(40) { index -> extension.onPositionUpdated((index + 1) * 1000L) }

        // 换曲
        extension.onBeforeLoadLyrics(track("C:\\Music\\e2.mp3", "第二首"))
        repeat(40) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        extension.onStateChanged(PlaybackExtensionPoint.State.Ended)

        val properties = loadProps()
        val first = pathOf("C:\\Music\\e1.mp3")
        val second = pathOf("C:\\Music\\e2.mp3")
        assertEquals("第一首", properties.getProperty("t.$first.title"))
        assertEquals("第二首", properties.getProperty("t.$second.title"))
        assertTrue("上一首的收听时长应已结算", msOf(first) >= 39_000L)
        assertTrue("第二首的收听时长应已结算", msOf(second) >= 39_000L)
    }

    @Test
    fun `路径大小写不同视为同一首歌`() {        val dir = newDir("f")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\F.mp3", "大小写"))
        extension.onIsPlayingChanged(true)
        repeat(31) { index -> extension.onPositionUpdated((index + 1) * 1000L) }

        // 宿主下一次上报同样的文件但大小写不同，不应产生第二条记录
        extension.onBeforeLoadLyrics(track("c:\\music\\f.MP3", "大小写"))
        extension.onStateChanged(PlaybackExtensionPoint.State.Idle)

        val entries = loadProps().stringPropertyNames().filter { it.endsWith(".count") }
        assertEquals("同一文件只应有一条记录", 1, entries.size)
        assertEquals(1L, loadProps().getProperty(entries.first()).toLong())
    }

    @Test
    fun `单曲循环时每一遍都会计数`() {
        val dir = newDir("g")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\loop.mp3", "循环"))
        extension.onIsPlayingChanged(true)

        repeat(31) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        assertEquals("第一遍听满 30 秒应计 1 次", 1L, countOf(pathOf("C:\\Music\\loop.mp3")))

        // 单曲循环：宿主把播放位置拉回开头，但不一定重新加载歌词
        extension.onSeekTo(0L)
        repeat(31) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        assertEquals("第二遍也应计 1 次", 2L, countOf(pathOf("C:\\Music\\loop.mp3")))
    }

    @Test
    fun `位置大幅回退视为新的一遍而不丢次数`() {
        val dir = newDir("h")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\loop2.mp3", "循环二"))
        extension.onIsPlayingChanged(true)

        repeat(31) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        assertEquals(1L, countOf(pathOf("C:\\Music\\loop2.mp3")))

        // 没有 onSeekTo 的情况下位置直接回到开头
        repeat(31) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        assertEquals("回退后重听满 30 秒应再计 1 次", 2L, countOf(pathOf("C:\\Music\\loop2.mp3")))
    }

    @Test
    fun `未听满一遍时位置回退不会白送次数`() {
        val dir = newDir("i")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val extension = ListenStatsExtension()
        extension.onBeforeLoadLyrics(track("C:\\Music\\short.mp3", "短听"))
        extension.onIsPlayingChanged(true)

        repeat(10) { index -> extension.onPositionUpdated((index + 1) * 1000L) }
        // 从 10 秒跳回 1 秒：这一遍还没听满，不能计数
        extension.onPositionUpdated(1_000L)
        repeat(10) { index -> extension.onPositionUpdated((index + 2) * 1000L) }
        assertEquals(0L, countOf(pathOf("C:\\Music\\short.mp3")))
    }

    // ------------------------------------------------------------------

    private fun newDir(suffix: String) = Files.createTempDirectory("listen-stats-$suffix")

    private fun pathOf(path: String) = StatsStore.keyOf("path:" + path.lowercase())

    private fun loadProps(): Properties {
        val props = Properties()
        val file = File(StatsStore.dataDir(), "listen-stats.db.properties")
        if (file.isFile) file.inputStream().use { props.load(it) }
        return props
    }

    private fun countOf(key: String): Long =
        loadProps().getProperty("t.$key.count")?.toLongOrNull() ?: 0L

    private fun msOf(key: String): Long =
        loadProps().getProperty("t.$key.ms")?.toLongOrNull() ?: 0L
}
