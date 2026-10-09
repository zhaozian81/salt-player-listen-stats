package com.spwmods.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

/**
 * 播放扩展：负责判断"这一遍算不算听过一次"
 *
 * 目标宿主是 SPW 1.18.5，它提供的是旧版 API：
 * - MediaItem 只有 title/artist/album/albumArtist/path（**没有 id、没有时长**）
 * - Playback 没有 getCurrentMediaItem，插件**无法主动查询当前歌曲**
 * - 没有曲库查询接口，也没有权限机制
 * 所以只能用文件路径作为歌曲身份，并用固定的 30 秒门槛判断"听过"。
 *
 * 宿主没有"换曲 / 播放结束"的专用回调，因此组合这些信号：
 * 1. onBeforeLoadLyrics / onAfterLoadLyrics —— 换曲的主要信号（带 MediaItem）
 * 2. onPositionUpdated —— 每秒一次，累计有效播放时长
 * 3. onSeekTo 与位置大幅回退 —— 单曲循环 / 手动重播时开始新的一遍
 * 4. onStateChanged —— Idle / Ended 时结算
 *
 * 一遍（pass）的边界：
 * - 换曲（新的 MediaItem）
 * - 播放结束 / 停止
 * - 已经计过数的一遍之后，播放位置回到开头（循环或手动重播）
 * 每一遍累计听满 [COUNT_THRESHOLD_MS] 才计 1 次，避免"点开就切歌"被算成听过。
 */
@Extension
class ListenStatsExtension : PlaybackExtensionPoint {

    /**
     * 一遍播放的会话
     */
    private class Session(
        val key: String,
        var meta: TrackMeta
    ) {
        var listenedMs = 0L
        var lastPosition = -1L
        var counted = false

        /** 已经计过数的一遍结束了：结算时长并开始新的一遍（例如单曲循环） */
        fun beginNewPass(position: Long) {
            listenedMs = 0L
            counted = false
            lastPosition = position
        }

        /** 取出这段时间的收听时长；取出后清零，避免重复结算 */
        fun flushListening(): Effect? {
            if (listenedMs <= 0L) return null
            val effect = Effect(Effect.TIME, key, meta, listenedMs)
            listenedMs = 0L
            return effect
        }
    }

    /** 在锁外执行的落盘动作 */
    private class Effect(
        val type: Int,
        val key: String,
        val meta: TrackMeta,
        val ms: Long
    ) {
        companion object {
            const val TIME = 0
            const val PLAY = 1
        }
    }

    private val lock = Any()
    private var session: Session? = null

    /**
     * 宿主没有"当前是否在播放"的查询接口，默认按正在播放处理。
     * 暂停时播放位置不会前进，位置增量本来就是 0，不会误加时长；
     * 这样插件在"正在播放时被启用"也能正常统计。
     */
    private var playing = true

    init {
        INSTANCE = this
    }

    // ---------------------------------------------------------------- 回调

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        observe(mediaItem)
        return null // 返回 null，继续使用宿主的默认歌词加载逻辑
    }

    override fun onAfterLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        observe(mediaItem)
        return null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        synchronized(lock) { playing = isPlaying }
    }

    override fun onSeekTo(position: Long) {
        val effects = ArrayList<Effect>(1)
        synchronized(lock) {
            val current = session ?: return
            if (current.counted && position <= RESTART_POSITION_MS) {
                // 已经计过数的一遍之后又拖回开头：算作新的一遍
                current.flushListening()?.let { effects += it }
                current.beginNewPass(position)
            } else {
                // 普通跳转：只更新基准点，跳过去的这段不算"听过"，也不白送一次
                current.lastPosition = position
            }
        }
        apply(effects)
    }

    override fun onPositionUpdated(position: Long) {
        val effects = ArrayList<Effect>(1)
        synchronized(lock) {
            val current = session
            if (current != null) {
                val last = current.lastPosition
                if (last >= 0L && position < last - BACK_JUMP_TOLERANCE_MS) {
                    // 位置大幅回退却没有收到 onSeekTo：多半是单曲循环或换曲了。
                    if (current.counted) {
                        // 上一遍已经计数，这里开始新的一遍（旧版 API 无法确认当前歌曲，
                        // 真正的换曲会在随后的歌词回调里把会话换成新歌）
                        current.flushListening()?.let { effects += it }
                        current.beginNewPass(position)
                    } else {
                        // 还没听满一遍：只重置基准点，不把回退当成"听过"
                        current.lastPosition = position
                    }
                } else {
                    current.lastPosition = position
                    if (playing && last >= 0L) {
                        val delta = position - last
                        if (delta in 1L..MAX_STEP_MS) {
                            current.listenedMs += delta
                            if (!current.counted && current.listenedMs >= COUNT_THRESHOLD_MS) {
                                current.counted = true
                                effects += Effect(Effect.PLAY, current.key, current.meta, 0L)
                            }
                        }
                    }
                }
            }
        }
        apply(effects)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if (state == PlaybackExtensionPoint.State.Idle || state == PlaybackExtensionPoint.State.Ended) {
            close()
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun observe(mediaItem: PlaybackExtensionPoint.MediaItem) {
        val key = keyOf(mediaItem)
        val effects = ArrayList<Effect>(1)
        synchronized(lock) {
            val current = session
            if (current != null && current.key == key) {
                current.meta = mergeMeta(current.meta, mediaItem)
            } else {
                // 换曲：先结算上一首
                current?.flushListening()?.let { effects += it }
                session = Session(key, toMeta(mediaItem))
            }
        }
        apply(effects)
    }

    private fun close() {
        val effects = ArrayList<Effect>(1)
        synchronized(lock) {
            val current = session ?: return
            session = null
            current.flushListening()?.let { effects += it }
        }
        apply(effects)
    }

    /** 把锁内收集的动作落到 StatsStore（StatsStore 自己带锁，这里不再持有本对象的锁） */
    private fun apply(effects: List<Effect>) {
        for (effect in effects) {
            when (effect.type) {
                Effect.PLAY -> StatsStore.recordPlay(effect.key, effect.meta)
                Effect.TIME -> StatsStore.addListeningTime(effect.key, effect.ms, effect.meta)
            }
        }
    }

    /**
     * 歌曲身份：优先用文件路径（Windows 路径大小写不敏感，统一转小写避免重复计条目），
     * 路径为空时退化为 标题|艺术家|专辑
     */
    private fun keyOf(item: PlaybackExtensionPoint.MediaItem): String {
        val identity = item.path.ifBlank { "${item.title}|${item.artist}|${item.album}" }
        return StatsStore.keyOf("path:" + identity.lowercase())
    }

    private fun toMeta(item: PlaybackExtensionPoint.MediaItem) = TrackMeta(
        title = item.title,
        artist = item.artist,
        album = item.album,
        path = item.path
    )

    private fun mergeMeta(old: TrackMeta, item: PlaybackExtensionPoint.MediaItem) = TrackMeta(
        title = item.title.ifBlank { old.title },
        artist = item.artist.ifBlank { old.artist },
        album = item.album.ifBlank { old.album },
        path = item.path.ifBlank { old.path }
    )

    companion object {
        /** 听满 30 秒算一次（旧版 API 拿不到歌曲时长，无法按比例判断） */
        const val COUNT_THRESHOLD_MS = 30_000L

        /** 单次位置回调最多认可 5 秒，防止宿主一次性跳进带来虚假时长 */
        const val MAX_STEP_MS = 5_000L

        /** 位置回退超过 3 秒视为"回到开头/换曲" */
        const val BACK_JUMP_TOLERANCE_MS = 3_000L

        /** 跳到 3 秒以内视为"从头重放" */
        const val RESTART_POSITION_MS = 3_000L

        @Volatile
        private var INSTANCE: ListenStatsExtension? = null

        /** 插件停用时把当前这一遍的收听时长落盘 */
        fun flush() {
            INSTANCE?.close()
        }
    }
}
