@file:OptIn(UnstableSpwWorkshopApi::class)

package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * 一首歌的标识与展示信息
 */
data class TrackMeta(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val path: String = ""
)

/**
 * 统计结果中的一行
 */
data class TrackStat(
    val key: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val count: Long,
    val listenedMs: Long,
    val lastPlayed: Long
)

/**
 * 听歌次数存储
 *
 * 存成 java.util.Properties（key=value 文本），放在宿主给插件的配置目录里。
 * 不用 ConfigHelper 的原因：它的 set() 只支持 String/Number/Boolean，而且没有枚举已有 key 的方法，
 * 而本插件需要"列出所有听过的歌"，所以自己管一个文件更简单可靠。
 */
object StatsStore {
    private const val FILE_NAME = "listen-stats.db.properties"
    private const val STARTUP_LOG_NAME = "listen-stats.log"
    private const val TRACK_PREFIX = "t."
    private const val HEX = "0123456789abcdef"

    private val lock = Any()
    private val props = Properties()
    private var context: PluginContext? = null
    private var storeFile: File? = null
    private var loaded = false

    fun attach(pluginContext: PluginContext) {
        synchronized(lock) {
            if (context == null) context = pluginContext
        }
    }

    /** 用 SHA-256 生成稳定的短 key，避免歌曲 ID/路径里的特殊字符污染配置键 */
    fun keyOf(identity: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 计一次播放 */
    fun recordPlay(key: String, meta: TrackMeta) {
        synchronized(lock) {
            ensureLoaded()
            val prefix = TRACK_PREFIX + key + "."
            putMeta(prefix, meta)
            props.setProperty(prefix + "count", (longOf(prefix + "count") + 1L).toString())
            props.setProperty(prefix + "last", System.currentTimeMillis().toString())
            props.setProperty("total.plays", (longOf("total.plays") + 1L).toString())
            saveLocked()
        }
    }

    /** 累加收听时长 */
    fun addListeningTime(key: String, ms: Long, meta: TrackMeta) {
        if (ms <= 0L) return
        synchronized(lock) {
            ensureLoaded()
            val prefix = TRACK_PREFIX + key + "."
            putMeta(prefix, meta)
            props.setProperty(prefix + "ms", (longOf(prefix + "ms") + ms).toString())
            props.setProperty("total.ms", (longOf("total.ms") + ms).toString())
            saveLocked()
        }
    }

    /** @return 总播放次数 to 总收听毫秒 */
    fun totals(): Pair<Long, Long> = synchronized(lock) {
        ensureLoaded()
        longOf("total.plays") to longOf("total.ms")
    }

    /** 全部歌曲，按播放次数降序 */
    fun snapshot(): List<TrackStat> = synchronized(lock) {
        ensureLoaded()
        val result = ArrayList<TrackStat>()
        for (name in props.stringPropertyNames()) {
            if (!name.startsWith(TRACK_PREFIX) || !name.endsWith(".count")) continue
            val key = name.substring(TRACK_PREFIX.length, name.length - ".count".length)
            val prefix = TRACK_PREFIX + key + "."
            result += TrackStat(
                key = key,
                id = props.getProperty(prefix + "id", ""),
                title = props.getProperty(prefix + "title", ""),
                artist = props.getProperty(prefix + "artist", ""),
                album = props.getProperty(prefix + "album", ""),
                path = props.getProperty(prefix + "path", ""),
                count = longOf(prefix + "count"),
                listenedMs = longOf(prefix + "ms"),
                lastPlayed = longOf(prefix + "last")
            )
        }
        result.sortWith(compareByDescending<TrackStat> { it.count }.thenByDescending { it.listenedMs })
        result
    }

    /** 统计数据所在目录 */
    fun dataDir(): File = synchronized(lock) {
        ensureLoaded()
        storeFile?.parentFile ?: File(".")
    }

    fun save() {
        synchronized(lock) {
            ensureLoaded()
            saveLocked()
        }
    }

    /**
     * 记录一次插件启动
     *
     * 宿主不会把插件加载情况写进自己的日志，这个文件可以确认"插件确实被宿主加载并启动了"，
     * 排查问题时也很有用（版本、宿主版本、渠道、数据目录）。
     */
    fun markStartup(pluginContext: PluginContext) {
        synchronized(lock) {
            ensureLoaded()
            try {
                val dir = dataDir()
                val file = File(dir, STARTUP_LOG_NAME)
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                val line = "$stamp  plugin=${pluginContext.pluginId} v${pluginContext.pluginVersion}" +
                    "  spw=${pluginContext.spwVersion}  channel=${pluginContext.spwChannel}" +
                    "  data=${dir.absolutePath}"
                Files.write(
                    file.toPath(),
                    listOf(line),
                    Charsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 写入启动日志失败: ${t.message}")
            }
        }
    }

    /**
     * 清空全部统计（保留文件，方便立即重新开始记录）
     *
     * @return 被清空的曲目条数
     */
    fun reset(): Int {
        synchronized(lock) {
            ensureLoaded()
            val removed = props.stringPropertyNames().count { it.startsWith(TRACK_PREFIX) && it.endsWith(".count") }
            props.clear()
            saveLocked()
            return removed
        }
    }

    /** 仅供单元测试使用：让单例可以重新绑定到新的假宿主 */
    internal fun resetForTesting() {
        synchronized(lock) {
            props.clear()
            context = null
            storeFile = null
            loaded = false
        }
    }

    // ------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val dir = resolveDataDir()
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        storeFile = file
        if (file.isFile) {
            try {
                FileInputStream(file).use { props.load(it) }
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 读取统计文件失败: ${t.message}")
            }
        }
    }

    private fun resolveDataDir(): File {
        // 1) 宿主交给插件的配置目录
        //    %APPDATA%\Salt Player for Windows\workshop\data\<pluginId>
        try {
            val helper = WorkshopApi.manager.createConfigManager().getConfig("config.json")
            val parent = helper.getConfigPath().parent
            if (parent != null) {
                val dir = parent.toFile()
                if (dir.isDirectory || dir.mkdirs()) return dir
            }
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 获取宿主配置目录失败，改用备用目录: ${t.message}")
        }

        // 2) 按宿主约定自己拼出来
        val appData = System.getenv("APPDATA")
        val id = context?.pluginId ?: "com.spwmods.listenstats"
        if (appData != null) {
            val dir = File(File(File(appData, "Salt Player for Windows"), "workshop"), "data/$id")
            if (dir.isDirectory || dir.mkdirs()) return dir
        }

        // 3) 插件安装目录
        val install = context?.pluginPath
        if (!install.isNullOrBlank()) {
            val f = File(install)
            val dir = if (f.isDirectory) f else f.parentFile
            if (dir != null && (dir.isDirectory || dir.mkdirs())) return dir
        }

        return File(System.getProperty("java.io.tmpdir") ?: ".")
    }

    private fun putMeta(prefix: String, meta: TrackMeta) {
        // 只覆盖非空值，避免用空字符串把已知信息抹掉
        if (meta.id.isNotBlank()) props.setProperty(prefix + "id", meta.id)
        if (meta.title.isNotBlank()) props.setProperty(prefix + "title", meta.title)
        if (meta.artist.isNotBlank()) props.setProperty(prefix + "artist", meta.artist)
        if (meta.album.isNotBlank()) props.setProperty(prefix + "album", meta.album)
        if (meta.path.isNotBlank()) props.setProperty(prefix + "path", meta.path)
    }

    private fun longOf(name: String): Long = props.getProperty(name)?.toLongOrNull() ?: 0L

    private fun saveLocked() {
        val target = storeFile ?: return
        try {
            val parent = target.parentFile ?: File(".")
            parent.mkdirs()
            val tmp = File(parent, target.name + ".tmp")
            FileOutputStream(tmp).use { props.store(it, "SPW listen-stats") }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 保存统计文件失败: ${t.message}")
        }
    }
}
