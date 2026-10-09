@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 统计存储本身的测试：启动日志与清空
 */
class StatsStoreTest {

    @Before
    fun setUp() {
        StatsStore.resetForTesting()
    }

    @Test
    fun `启动日志会记录插件与宿主版本`() {
        val dir = Files.createTempDirectory("listen-stats-log")
        WorkshopApi.instance = FakeApi(dir)
        val context = testContext(dir)
        StatsStore.attach(context)

        StatsStore.markStartup(context)
        StatsStore.markStartup(context)

        val log = File(StatsStore.dataDir(), "listen-stats.log")
        assertTrue("启动日志应存在: $log", log.isFile)

        val lines = log.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals("每次启动追加一行", 2, lines.size)
        assertTrue("应记录插件 ID: ${lines.first()}", lines.first().contains("plugin=com.spwmods.listenstats"))
        assertTrue("应记录宿主版本: ${lines.first()}", lines.first().contains("spw=1.18.5"))
    }

    @Test
    fun `清空统计会移除所有曲目与总数`() {
        val dir = Files.createTempDirectory("listen-stats-reset")
        WorkshopApi.instance = FakeApi(dir)
        StatsStore.attach(testContext(dir))

        val key = StatsStore.keyOf("path:c:\\music\\x.mp3")
        val meta = TrackMeta(title = "曲目", artist = "歌手", path = "C:\\Music\\x.mp3")
        repeat(3) { StatsStore.recordPlay(key, meta) }
        StatsStore.addListeningTime(key, 90_000L, meta)
        assertEquals(3L to 90_000L, StatsStore.totals())

        val removed = StatsStore.reset()

        assertEquals("应报告被清空的曲目数", 1, removed)
        assertEquals(0L to 0L, StatsStore.totals())
        assertTrue("曲目列表应为空", StatsStore.snapshot().isEmpty())
    }
}
