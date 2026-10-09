package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * 听歌统计插件主类
 */
class ListenStatsPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {

    init {
        StatsStore.attach(pluginContext)
    }

    override fun start() {
        StatsStore.attach(pluginContext)
        StatsStore.markStartup(pluginContext)
        val (plays, _) = StatsStore.totals()
        WorkshopApi.ui.toast(
            if (plays > 0L) "听歌统计已启用：已累计记录 $plays 次播放"
            else "听歌统计已启用，开始听歌吧",
            WorkshopApi.Ui.ToastType.Success
        )
    }

    override fun stop() {
        ListenStatsExtension.flush()
        StatsStore.save()
    }

    override fun delete() {
        StatsStore.save()
    }

    override fun update() {
        StatsStore.save()
    }
}
