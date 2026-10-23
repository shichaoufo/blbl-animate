@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.engine

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import blbl.cat3399.core.log.AppLog
import kotlin.math.abs

/**
 * 把「会过期的 FLV 长连接」变成对播放器而言**永不结束的一条流**（移植自 PureLive
 * `lib/domains/live/data/stream/flv_splice_relay.dart`，MIT License）。
 *
 * 背景：虎牙 web 签名（`ctype=huya_webh5`）的 FLV 连接建立约 120 秒后被 CDN 主动 EOF。
 * 之前所有「断 → 重新 setSource」的做法都会让播放器清空缓冲、重建解码器，观感就是跳变 /
 * 卡一下，怎么调参数都只是把间隔拉长，属于折中。
 *
 * 这里换一种做法：在连接到期**之前**就签好下一条地址并连上，然后在 FLV 标签层把两路流
 * 接起来——
 * 1. 新连接先读到比「已播位置」更靠后的视频关键帧（若新连接落后于旧流，就继续读直到追上）；
 * 2. 旧连接继续播到同一个时间点；
 * 3. 丢弃新连接重复的 `onMetaData`，AVC/AAC 配置帧仅在与旧的不同时才重发；
 * 4. 两条连接时间轴不同时按 `offset` 平移，保证时间戳单调递增。
 *
 * 播放器自始至终只看到一个 [DataSource]，不会感知到任何一次换线：无 EOF、无 seek、
 * 缓冲不清空、解码器不重启。
 */
class FlvSpliceDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val renew: () -> String?,
    private val rotateAfterMs: Long,
) : BaseDataSource(/* isNetwork= */ true) {

    private var upstream: DataSource? = null
    private var currentUrl: String = ""

    /** 已输出但还没被 [read] 取走的字节块（每个元素是一个完整 FLV 单元）。 */
    private val queue = ArrayDeque<ByteArray>()
    private var queuePos = 0

    private var headerSent = false
    private var ended = false

    /** 时间戳平移量：新连接与旧流不在同一时间轴上时才非 0。 */
    private var offsetMs = 0L
    private var lastVideoTs = -1L
    private var lastAudioTs = -1L
    private var videoConfig: ByteArray? = null
    private var audioConfig: ByteArray? = null
    private var switches = 0
    private var rotateAtMs = 0L

    // ------------------------------------------------------------------ open/read/close

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        resetState()
        currentUrl = dataSpec.uri.toString()
        val ds = upstreamFactory.createDataSource()
        try {
            ds.open(dataSpec)
        } catch (t: Throwable) {
            runCatching { ds.close() }
            throw t
        }
        upstream = ds
        rotateAtMs = SystemClock.elapsedRealtime() + rotateAfterMs
        transferStarted(dataSpec)
        AppLog.i("FlvSplice", "open rotate=${rotateAfterMs}ms host=${dataSpec.uri.host}")
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val n = drainQueue(buffer, offset, length)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (ended) return C.RESULT_END_OF_INPUT
            step()
        }
    }

    override fun getUri(): Uri? = currentUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    override fun close() {
        resetState()
        closeUpstream()
    }

    // ------------------------------------------------------------------ 主循环

    /**
     * 产生「下一个要输出的单元」：FLV 头、一个普通标签，或一次换线。
     *
     * 正常情况每次只读一个 FLV 标签；换线由 [handover] 在这里同步完成（在播放器的加载
     * 线程上阻塞一两秒，被缓冲区完全吸收）。
     */
    private fun step() {
        val ds = upstream
        if (ds == null) {
            ended = true
            return
        }
        if (!headerSent) {
            // 9 字节 FLV 头 + 4 字节 PreviousTagSize0。
            val header = readExact(ds, FLV_HEADER_SIZE) ?: run {
                ended = true
                return
            }
            queue.addLast(header)
            headerSent = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now >= rotateAtMs) {
            if (handover(oldEnded = false)) return
            // 换线失败就退回「等它自己断」，10 秒后再试一次。
            rotateAtMs = SystemClock.elapsedRealtime() + ROTATE_RETRY_MS
        }
        val tag = readTag(ds)
        if (tag == null) {
            // 旧连接被 CDN 掐断:尝试续一条,成功就继续,失败才让播放器看到 EOF。
            if (handover(oldEnded = true)) return
            ended = true
            AppLog.w("FlvSplice", "upstream ended and renew failed, reported EOF")
            return
        }
        forward(tag, offsetMs)
    }

    /**
     * 换线：签新地址 → 连上 → 找到可用关键帧 → 旧流补播到该时间点 → 切换。
     *
     * @param oldEnded 旧连接是否已经断了（断了就不用再补播）。
     * @return 是否成功切换。
     */
    private fun handover(oldEnded: Boolean): Boolean {
        val url = runCatching { renew() }.getOrNull()
        if (url.isNullOrBlank()) {
            AppLog.w("FlvSplice", "renew url unavailable")
            return false
        }
        val next =
            runCatching {
                upstreamFactory.createDataSource().also { it.open(DataSpec(Uri.parse(url))) }
            }.getOrNull() ?: run {
            AppLog.w("FlvSplice", "new upstream open failed host=${hostOf(url)}")
            return false
        }
        var vCfg: ByteArray? = null
        var aCfg: ByteArray? = null
        var newOffset: Long? = null
        var keyframe: ByteArray? = null
        try {
            if (readExact(next, FLV_HEADER_SIZE) == null) {
                AppLog.w("FlvSplice", "new upstream empty")
                runCatching { next.close() }
                return false
            }
            val deadline = SystemClock.elapsedRealtime() + KEYFRAME_SEARCH_MS
            while (keyframe == null) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    AppLog.w("FlvSplice", "no keyframe on new upstream in ${KEYFRAME_SEARCH_MS}ms")
                    runCatching { next.close() }
                    return false
                }
                val tag = readTag(next)
                if (tag == null) {
                    AppLog.w("FlvSplice", "new upstream ended before a keyframe")
                    runCatching { next.close() }
                    return false
                }
                if (isVideoConfig(tag)) {
                    vCfg = tag
                    continue
                }
                if (isAudioConfig(tag)) {
                    aCfg = tag
                    continue
                }
                if (tagType(tag) != TYPE_VIDEO) continue
                val raw = timestamp(tag)
                if (newOffset == null) {
                    val last = lastVideoTs
                    // 两条连接通常共享同一条流时间轴;差得远说明是另一条时间轴,平移到接续位置。
                    newOffset = if (last < 0 || abs(raw - last) <= ALIGN_WINDOW_MS) 0L else last + 1 - raw
                }
                val off = newOffset ?: 0L
                // 只接受「比已播位置更靠后」的关键帧,否则会倒退。
                if (isKeyframe(tag) && (lastVideoTs < 0 || raw + off > lastVideoTs)) keyframe = tag
            }
        } catch (t: Throwable) {
            AppLog.w("FlvSplice", "new upstream unusable: ${t::class.java.simpleName}: ${t.message}")
            runCatching { next.close() }
            return false
        }
        val kf = keyframe
        if (kf == null) {
            runCatching { next.close() }
            return false
        }
        val off = newOffset ?: 0L
        val switchAt = timestamp(kf) + off
        if (!oldEnded) {
            // 旧连接继续补播到 switchAt,让画面在切换点之前保持完整。
            val until = SystemClock.elapsedRealtime() + HANDOVER_DRAIN_MS
            while (SystemClock.elapsedRealtime() < until) {
                val old = upstream ?: break
                val tag = readTag(old) ?: break
                val ts = timestamp(tag) + offsetMs
                if (tagType(tag) == TYPE_VIDEO && ts >= switchAt) break
                if (tagType(tag) == TYPE_AUDIO && ts >= switchAt) continue
                forward(tag, offsetMs)
            }
        }
        closeUpstream()
        upstream = next
        currentUrl = url
        offsetMs = off
        switches++
        rotateAtMs = SystemClock.elapsedRealtime() + rotateAfterMs
        // 配置帧只在变了的时候重发,避免播放器重建解码器。
        vCfg?.let { cfg -> if (videoConfig == null || !samePayload(cfg, videoConfig!!)) forward(withTimestamp(cfg, switchAt - off), off) }
        aCfg?.let { cfg -> if (audioConfig == null || !samePayload(cfg, audioConfig!!)) forward(withTimestamp(cfg, switchAt - off), off) }
        forward(kf, off)
        AppLog.i(
            "FlvSplice",
            "splice #$switches ok host=${hostOf(url)} offset=${off}ms switchAt=${switchAt}ms " +
                "oldEnded=$oldEnded",
        )
        return true
    }

    /** 输出一个标签:必要时平移时间戳、丢弃重复/倒退的帧。 */
    private fun forward(tag: ByteArray, offset: Long) {
        val type = tagType(tag)
        // 换线之后新连接的 onMetaData 一律丢弃,播放器只需要第一份。
        if (type == TYPE_SCRIPT && switches > 0) return
        val ts = timestamp(tag) + offset
        when {
            isVideoConfig(tag) -> videoConfig = tag
            isAudioConfig(tag) -> audioConfig = tag
            type == TYPE_VIDEO -> {
                if (lastVideoTs >= 0 && ts < lastVideoTs) return
                lastVideoTs = ts
            }
            type == TYPE_AUDIO -> {
                if (lastAudioTs >= 0 && ts < lastAudioTs) return
                lastAudioTs = ts
            }
        }
        queue.addLast(if (offset == 0L) tag else withTimestamp(tag, ts))
    }

    // ------------------------------------------------------------------ IO

    private fun drainQueue(buffer: ByteArray, offset: Int, length: Int): Int {
        val head = queue.firstOrNull() ?: return 0
        val remain = head.size - queuePos
        if (remain <= 0) {
            queue.removeFirst()
            queuePos = 0
            return drainQueue(buffer, offset, length)
        }
        val n = minOf(remain, length)
        System.arraycopy(head, queuePos, buffer, offset, n)
        queuePos += n
        if (queuePos >= head.size) {
            queue.removeFirst()
            queuePos = 0
        }
        return n
    }

    /** 读满 [len] 字节;流结束返回 null。 */
    private fun readExact(ds: DataSource, len: Int): ByteArray? {
        val out = ByteArray(len)
        return if (readExactInto(ds, out, 0, len)) out else null
    }

    private fun readExactInto(ds: DataSource, target: ByteArray, offset: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n =
                try {
                    ds.read(target, offset + read, len - read)
                } catch (t: Throwable) {
                    return false
                }
            if (n <= 0) return false
            read += n
        }
        return true
    }

    /** 读一个完整 FLV 标签:11 字节标签头 + 数据 + 4 字节 PreviousTagSize。 */
    private fun readTag(ds: DataSource): ByteArray? {
        val head = readExact(ds, TAG_HEADER_SIZE) ?: return null
        val size =
            ((head[1].toInt() and 0xFF) shl 16) or
                ((head[2].toInt() and 0xFF) shl 8) or
                (head[3].toInt() and 0xFF)
        if (size < 0 || size > MAX_TAG_SIZE) return null
        val tag = ByteArray(TAG_HEADER_SIZE + size + 4)
        System.arraycopy(head, 0, tag, 0, TAG_HEADER_SIZE)
        return if (readExactInto(ds, tag, TAG_HEADER_SIZE, size + 4)) tag else null
    }

    private fun closeUpstream() {
        val ds = upstream
        upstream = null
        if (ds != null) runCatching { ds.close() }
    }

    private fun resetState() {
        queue.clear()
        queuePos = 0
        headerSent = false
        ended = false
        offsetMs = 0L
        lastVideoTs = -1L
        lastAudioTs = -1L
        videoConfig = null
        audioConfig = null
        switches = 0
        rotateAtMs = 0L
    }

    private fun hostOf(url: String): String = url.substringAfter("://", "").substringBefore('/')

    companion object {
        private const val TYPE_AUDIO = 8
        private const val TYPE_VIDEO = 9
        private const val TYPE_SCRIPT = 18
        private const val TAG_HEADER_SIZE = 11

        /** FLV 文件头 9 字节 + PreviousTagSize0 4 字节。 */
        private const val FLV_HEADER_SIZE = 13
        private const val MAX_TAG_SIZE = 4 * 1024 * 1024

        /** 两条连接时间轴相差超过这个值就认为是不同时间轴,需要平移。 */
        private const val ALIGN_WINDOW_MS = 60_000L
        private const val KEYFRAME_SEARCH_MS = 20_000L
        private const val HANDOVER_DRAIN_MS = 10_000L
        private const val ROTATE_RETRY_MS = 10_000L

        internal fun tagType(tag: ByteArray): Int = tag[0].toInt() and 0x1F

        internal fun timestamp(tag: ByteArray): Long =
            ((tag[7].toLong() and 0xFF) shl 24) or
                ((tag[4].toLong() and 0xFF) shl 16) or
                ((tag[5].toLong() and 0xFF) shl 8) or
                (tag[6].toLong() and 0xFF)

        private fun withTimestamp(tag: ByteArray, ts: Long): ByteArray {
            val copy = tag.copyOf()
            val v = ts.toInt()
            copy[4] = ((v ushr 16) and 0xFF).toByte()
            copy[5] = ((v ushr 8) and 0xFF).toByte()
            copy[6] = (v and 0xFF).toByte()
            copy[7] = ((v ushr 24) and 0xFF).toByte()
            return copy
        }

        /** AVC/HEVC 解码配置帧(旧版 FLV 或 Enhanced FLV SequenceStart)。 */
        private fun isVideoConfig(tag: ByteArray): Boolean {
            if (tagType(tag) != TYPE_VIDEO || tag.size < 17) return false
            if ((tag[11].toInt() and 0x80) != 0) return (tag[11].toInt() and 0x0F) == 0
            val codec = tag[11].toInt() and 0x0F
            return (codec == 7 || codec == 12) && tag[12].toInt() == 0
        }

        private fun isKeyframe(tag: ByteArray): Boolean =
            tagType(tag) == TYPE_VIDEO && tag.size > 12 &&
                ((tag[11].toInt() ushr 4) and 7) == 1 && !isVideoConfig(tag)

        /** AAC AudioSpecificConfig。 */
        private fun isAudioConfig(tag: ByteArray): Boolean =
            tagType(tag) == TYPE_AUDIO && tag.size > 16 &&
                (tag[11].toInt() ushr 4) == 10 && tag[12].toInt() == 0

        /** 比较标签负载(跳过 11 字节标签头与末尾 4 字节)。 */
        private fun samePayload(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != b.size) return false
            for (i in 11 until a.size - 4) if (a[i] != b[i]) return false
            return true
        }
    }
}

/**
 * [FlvSpliceDataSource] 的工厂。
 *
 * @param renew 换线时调用:返回一条**新签名**的同一路 FLV 地址,取不到返回 null。
 */
class FlvSpliceDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val renew: () -> String?,
    private val rotateAfterMs: Long,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        FlvSpliceDataSource(upstreamFactory, renew, rotateAfterMs)
}
