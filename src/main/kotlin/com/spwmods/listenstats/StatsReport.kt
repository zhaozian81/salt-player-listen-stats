package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统计展示入口
 *
 * preference_config.json 里的按钮通过反射调用这里的静态方法，
 * 所以必须是 companion object + @JvmStatic + @JvmName 的静态无参方法。
 */
class StatsReport {

    companion object {

        /** 在吐司里显示总览 + 最常听的 5 首 */
        @JvmStatic
        @JvmName("showSummary")
        fun showSummary() {
            try {
                val (plays, totalMs) = StatsStore.totals()
                if (plays <= 0L) {
                    toast("听歌统计：还没有记录到播放，先听一首歌吧", WorkshopApi.Ui.ToastType.Warning)
                    return
                }

                val text = StringBuilder()
                    .append("已记录 ").append(plays).append(" 次播放")
                    .append(" · 累计收听 ").append(formatDuration(totalMs))

                StatsStore.snapshot().take(5).forEachIndexed { index, stat ->
                    text.append('\n')
                        .append(index + 1).append(". ")
                        .append(stat.title.ifBlank { "未知曲目" })
                    if (stat.artist.isNotBlank()) text.append(" - ").append(stat.artist)
                    text.append("（").append(stat.count).append(" 次）")
                }

                toast(text.toString(), WorkshopApi.Ui.ToastType.Success)
            } catch (t: Throwable) {
                toast("听歌统计：读取失败（${t.message}）", WorkshopApi.Ui.ToastType.Error)
            }
        }

        /** 生成 HTML 报告并尝试用浏览器打开 */
        @JvmStatic
        @JvmName("exportHtml")
        fun exportHtml() {
            try {
                val stats = StatsStore.snapshot()
                val (plays, totalMs) = StatsStore.totals()
                val file = File(StatsStore.dataDir(), "listen-stats-report.html")
                file.writeText(buildHtml(stats, plays, totalMs), Charsets.UTF_8)

                val opened = tryOpenInBrowser(file)
                toast(
                    if (opened) "统计报告已生成并在浏览器中打开"
                    else "统计报告已生成：${file.absolutePath}",
                    if (opened) WorkshopApi.Ui.ToastType.Success
                    else WorkshopApi.Ui.ToastType.Warning
                )
            } catch (t: Throwable) {
                toast("听歌统计：生成报告失败（${t.message}）", WorkshopApi.Ui.ToastType.Error)
            }
        }

        /** 清空统计：需要点两次确认，避免误触丢掉记录 */
        @JvmStatic
        @JvmName("resetStats")
        fun resetStats() {
            try {
                val now = System.currentTimeMillis()
                if (now - lastResetRequestAt <= RESET_CONFIRM_WINDOW_MS) {
                    lastResetRequestAt = 0L
                    val removed = StatsStore.reset()
                    toast("听歌统计已清空（移除 $removed 首曲目的记录）", WorkshopApi.Ui.ToastType.Success)
                } else {
                    lastResetRequestAt = now
                    toast("确认要清空统计吗？请在 15 秒内再点一次「清空统计」", WorkshopApi.Ui.ToastType.Warning)
                }
            } catch (t: Throwable) {
                toast("听歌统计：清空失败（${t.message}）", WorkshopApi.Ui.ToastType.Error)
            }
        }

        /** 打开统计数据所在目录 */
        @JvmStatic
        @JvmName("openDataDir")
        fun openDataDir() {
            try {
                val dir = StatsStore.dataDir()
                dir.mkdirs()
                val opened = tryOpenDirectory(dir)
                toast(
                    if (opened) "已打开统计数据目录" else "统计数据目录：${dir.absolutePath}",
                    if (opened) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning
                )
            } catch (t: Throwable) {
                toast("听歌统计：打开目录失败（${t.message}）", WorkshopApi.Ui.ToastType.Error)
            }
        }

        // ------------------------------------------------------------

        /** 两次点击的间隔上限：超过就重新要求确认 */
        private const val RESET_CONFIRM_WINDOW_MS = 15_000L

        @Volatile
        private var lastResetRequestAt = 0L

        /** 仅供单元测试：清掉"等待二次确认"的状态 */
        internal fun clearResetConfirmationForTesting() {
            lastResetRequestAt = 0L
        }

        private fun toast(text: String, type: WorkshopApi.Ui.ToastType) {
            try {
                WorkshopApi.ui.toast(text, type)
            } catch (t: Throwable) {
                System.err.println("[listen-stats] $text")
            }
        }

        private fun tryOpenInBrowser(file: File): Boolean = try {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(file.toURI())
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }

        private fun tryOpenDirectory(dir: File): Boolean = try {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(dir)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "0 秒"
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L && minutes > 0L -> "$hours 小时 $minutes 分"
                hours > 0L -> "$hours 小时"
                minutes > 0L && seconds > 0L -> "$minutes 分 $seconds 秒"
                minutes > 0L -> "$minutes 分钟"
                else -> "$seconds 秒"
            }
        }

        private fun esc(raw: String): String = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        private fun buildHtml(stats: List<TrackStat>, plays: Long, totalMs: Long): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val rows = StringBuilder()

            stats.forEachIndexed { index, stat ->
                val title = stat.title.ifBlank { "未知曲目" }
                val rankClass = when (index) {
                    0 -> " class=\"top\""
                    1, 2 -> " class=\"top3\""
                    else -> ""
                }
                // 路径不再占用版面，改为鼠标悬停提示，需要时仍然看得到
                val pathTooltip = if (stat.path.isNotBlank()) " title=\"${esc(stat.path)}\"" else ""

                rows.append("<tr>")
                    .append("<td class=\"rank\"><i").append(rankClass).append(">")
                    .append(index + 1)
                    .append("</i></td>")
                    .append("<td class=\"song\"$pathTooltip><b>").append(esc(title)).append("</b>")
                    .append("</td>")
                    .append("<td class=\"col-artist\">").append(esc(stat.artist.ifBlank { "-" })).append("</td>")
                    .append("<td class=\"col-album\">").append(esc(stat.album.ifBlank { "-" })).append("</td>")
                    .append("<td class=\"num count\">").append(stat.count).append("</td>")
                    .append("<td class=\"num time\">")
                    // 正在播放、还没结算的那一遍时长为 0，显示成 "-" 比 "0 秒" 更诚实
                    .append(if (stat.listenedMs > 0L) formatDuration(stat.listenedMs) else "-")
                    .append("</td>")
                    .append("<td class=\"num time\">")
                    .append(if (stat.lastPlayed > 0L) dateFormat.format(Date(stat.lastPlayed)) else "-")
                    .append("</td>")
                    .append("</tr>")
            }

            if (rows.isEmpty()) {
                rows.append("<tr><td colspan=\"6\" class=\"empty\">还没有统计数据，听几首歌后再来</td></tr>")
            }

            val generatedAt = dateFormat.format(Date())

            return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>听歌统计报告</title>
<style>
  :root {
    --bg: #f4f5f7;
    --panel: #ffffff;
    --text: #1f2329;
    --muted: #8b939c;
    --line: #ecedf0;
    --accent: #e8590c;
    --accent-soft: #fdefe6;
  }

  * { box-sizing: border-box; }

  body {
    margin: 0; padding: 36px 20px 44px; background: var(--bg); color: var(--text);
    font-family: "Microsoft YaHei", "PingFang SC", "Segoe UI", system-ui, sans-serif;
    -webkit-font-smoothing: antialiased;
  }

  .wrap { max-width: 1040px; margin: 0 auto; }

  .head { display: flex; align-items: flex-end; justify-content: space-between;
          gap: 16px; flex-wrap: wrap; margin-bottom: 22px; }
  .head h1 { font-size: 24px; font-weight: 600; margin: 0; letter-spacing: .5px; }
  .head .sub { color: var(--muted); font-size: 13px; margin-top: 7px; }
  .chip { font-size: 12px; color: var(--accent); background: var(--accent-soft);
          padding: 6px 13px; border-radius: 999px; white-space: nowrap; }

  .cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr));
           gap: 14px; margin-bottom: 22px; }
  .card { background: var(--panel); border-radius: 14px; padding: 18px 20px 16px;
          box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 6px 16px -10px rgba(16, 24, 40, .18); }
  .card .k { color: var(--muted); font-size: 12.5px; }
  .card .v { font-size: 25px; font-weight: 600; margin-top: 9px; line-height: 1.15; }
  .card.hl .v { color: var(--accent); }

  .panel { background: var(--panel); border-radius: 14px; overflow: hidden;
           box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 6px 16px -10px rgba(16, 24, 40, .18); }
  .panel-head { display: flex; align-items: center; justify-content: space-between;
                padding: 15px 20px; border-bottom: 1px solid var(--line); }
  .panel-head h2 { font-size: 15px; font-weight: 600; margin: 0; }
  .panel-head .meta { color: var(--muted); font-size: 12.5px; }

  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 12px 16px; text-align: left; font-size: 13.5px; vertical-align: middle; }
  th { background: #fafbfc; color: #7b838c; font-weight: 600; font-size: 12.5px;
       white-space: nowrap; border-bottom: 1px solid var(--line); }
  tbody tr { border-bottom: 1px solid var(--line); }
  tbody tr:last-child { border-bottom: none; }
  tbody tr:hover { background: #fbfcfd; }

  .rank { width: 58px; }
  .rank i { display: inline-flex; align-items: center; justify-content: center;
            width: 26px; height: 26px; border-radius: 999px; background: #f1f3f5;
            color: #6b7280; font-size: 12px; font-weight: 600; font-style: normal; }
  .rank i.top { background: var(--accent); color: #fff; }
  .rank i.top3 { background: var(--accent-soft); color: var(--accent); }

  .song { min-width: 200px; }
  .song b { font-weight: 600; font-size: 14px; }
  .song span { display: block; color: var(--muted); font-size: 12px; margin-top: 3px; }

  .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
  .count { font-weight: 600; color: var(--accent); }
  .time { color: #6b7280; font-size: 12.5px; }
  .empty { text-align: center; color: var(--muted); padding: 56px 0; }

  footer { margin-top: 16px; color: var(--muted); font-size: 12px; line-height: 1.8; }

  @media (max-width: 760px) {
    body { padding: 20px 12px 32px; }
    .cards { grid-template-columns: 1fr; }
    .col-album { display: none; }
  }
</style>
</head>
<body>
<div class="wrap">
  <div class="head">
    <div>
      <h1>听歌统计报告</h1>
      <div class="sub">生成时间：$generatedAt · 数据来自 SPW 插件「听歌统计」</div>
    </div>
    <div class="chip">按播放次数排序</div>
  </div>

  <div class="cards">
    <div class="card hl"><div class="k">总播放次数</div><div class="v">$plays</div></div>
    <div class="card"><div class="k">累计收听时长</div><div class="v">${formatDuration(totalMs)}</div></div>
    <div class="card"><div class="k">记录曲目数</div><div class="v">${stats.size}</div></div>
  </div>

  <div class="panel">
    <div class="panel-head">
      <h2>歌曲排行</h2>
      <div class="meta">共 ${stats.size} 首</div>
    </div>
    <table>
      <thead>
        <tr>
          <th class="rank">#</th>
          <th>歌曲</th>
          <th class="col-artist" style="width:170px">艺术家</th>
          <th class="col-album" style="width:170px">专辑</th>
          <th class="num" style="width:80px">次数</th>
          <th class="num" style="width:120px">累计时长</th>
          <th class="num" style="width:130px">最近播放</th>
        </tr>
      </thead>
      <tbody>
$rows
      </tbody>
    </table>
  </div>

  <footer>
    「次数」为单曲累计听满 30 秒记 1 次；SPW 1.18.5 的接口拿不到歌曲时长，因此无法按比例判断。<br>
    鼠标悬停在歌曲名上可以查看文件路径；统计文件与报告都在插件配置页的「打开数据文件夹」里。
  </footer>
</div>
</body>
</html>
"""
        }
    }
}
