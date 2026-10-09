@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 验证配置界面上的两个按钮真正能跑通：
 * 摘要吐司 + HTML 报告。同时把报告留在 build/sample-report 供人工预览。
 */
class StatsReportTest {

    private val sampleDir = File("build/sample-report").absoluteFile

    @Before
    fun setUp() {
        StatsStore.resetForTesting()
        StatsReport.clearResetConfirmationForTesting()
        sampleDir.deleteRecursively()
        sampleDir.mkdirs()
    }

    @Test
    fun `摘要按钮会显示总览与最常听的歌`() {
        val api = seed()

        StatsReport.showSummary()

        val message = api.fakeUi.messages.lastOrNull().orEmpty()
        assertTrue("应提示总播放次数，实际: $message", message.contains("次播放"))
        assertTrue("应包含最常听的歌，实际: $message", message.contains("夜曲"))
    }

    @Test
    fun `没有记录时摘要给出友好提示`() {
        val api = FakeApi(sampleDir.toPath())
        WorkshopApi.instance = api
        StatsStore.attach(testContext(sampleDir.toPath()))

        StatsReport.showSummary()

        val message = api.fakeUi.messages.lastOrNull().orEmpty()
        assertTrue("应提示还没有记录，实际: $message", message.contains("还没有"))
    }

    @Test
    fun `报告按钮会生成可打开的 HTML`() {
        seed()

        StatsReport.exportHtml()

        val report = File(sampleDir, "listen-stats-report.html")
        assertTrue("报告文件应存在: $report", report.isFile)

        val html = report.readText(Charsets.UTF_8)
        assertTrue("应包含标题", html.contains("听歌统计报告"))
        assertTrue("应包含曲目", html.contains("夜曲"))
        assertTrue("应包含艺术家", html.contains("周杰伦"))
        assertTrue("应包含总播放次数", html.contains("总播放次数"))
        assertTrue("应包含 UTF-8 声明", html.contains("charset=\"utf-8\""))
        assertEquals("不应出现未转义的模板残留", false, html.contains("\$rows"))
        // 文件路径不再占版面：正文里没有路径行，只在悬停提示里保留
        assertEquals("不应再有可见的路径行", false, html.contains("class=\"path\""))
        assertTrue(
            "路径应改为悬停提示",
            html.contains("title=\"C:\\Music\\夜曲.mp3\"")
        )
        assertTrue("应给出计数口径说明", html.contains("听满 30 秒"))
    }

    @Test
    fun `清空统计需要连点两次`() {
        val api = seed()
        WorkshopApi.instance = api
        StatsStore.attach(testContext(sampleDir.toPath()))

        StatsReport.resetStats()
        assertTrue(
            "第一次点击只应要求确认，实际: ${api.fakeUi.messages.lastOrNull()}",
            api.fakeUi.messages.last().contains("再点一次")
        )
        assertTrue("第一次点击不该清空", StatsStore.snapshot().isNotEmpty())

        StatsReport.resetStats()
        assertTrue("第二次点击应清空记录", StatsStore.snapshot().isEmpty())
        assertEquals(0L, StatsStore.totals().first)
        assertEquals(0L, StatsStore.totals().second)
    }

    private fun seed(): FakeApi {
        val api = FakeApi(sampleDir.toPath())
        WorkshopApi.instance = api
        StatsStore.attach(testContext(sampleDir.toPath()))

        addTrack("s1", "夜曲", "周杰伦", "十一月的萧邦", count = 12, minutes = 47)
        addTrack("s2", "晴天", "周杰伦", "叶惠美", count = 8, minutes = 33)
        addTrack("s3", "富士山下", "陈奕迅", "What's Going On...?", count = 5, minutes = 21)
        return api
    }

    private fun addTrack(
        id: String,
        title: String,
        artist: String,
        album: String,
        count: Int,
        minutes: Long
    ) {
        val key = StatsStore.keyOf("id:$id")
        val meta = TrackMeta(
            id = id,
            title = title,
            artist = artist,
            album = album,
            path = "C:\\Music\\$title.mp3"
        )
        repeat(count) { StatsStore.recordPlay(key, meta) }
        StatsStore.addListeningTime(key, minutes * 60_000L, meta)
    }
}
