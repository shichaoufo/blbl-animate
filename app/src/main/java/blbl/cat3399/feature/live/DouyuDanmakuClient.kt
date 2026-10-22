package blbl.cat3399.feature.live

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 斗鱼直播弹幕客户端（协议移植自 PureLive `live_danmaku/src/sites/douyu.dart`，MIT License）。
 *
 * 协议要点（2026-10 与 PureLive 核对）：
 * - 端点：`wss://danmuproxy.douyu.com:8506`，二进制帧。
 * - 报文：`长度(4,小端) 长度(4,小端) 类型(2,小端)=689 加密(1)=0 保留(1)=0 正文 \0`，
 *   长度不含自身前 4 字节（即 8 + 正文 + 1）。
 * - 连接后发送 `type@=loginreq/roomid@=<rid>/` 与 `type@=joingroup/rid@=<rid>/gid@=-9999/`。
 * - 心跳 `type@=mrkl/`，45 秒一次。
 * - 正文为 STT 文本：`key@=value/` 键值对，值内 `@`→`@A`、`/`→`@S`。
 * - `chatmsg` = 弹幕；`comm_chatmsg` = 醒目留言（SuperChat）；`rss ss@=0` = 直播结束。
 */
class DouyuDanmakuClient(
    private val roomId: Long,
    private val onDanmaku: (LiveMessageClient.LiveDanmakuEvent) -> Unit,
    private val onSuperChat: (LiveSuperChat) -> Unit,
    private val onSuperChatDelete: (Set<Long>) -> Unit,
    private val onStatus: (String) -> Unit,
) : LiveDanmakuClient {
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "douyu-live-ws").apply { isDaemon = true }
        }

    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var authed = false
    @Volatile private var reconnectAttempt = 0

    private var heartbeatTask: ScheduledFuture<*>? = null
    private var authTimeoutTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    private var superChatSeq = 0L

    override suspend fun connect() {
        closed = false
        reconnectAttempt = 0
        authed = false
        connectNow()
    }

    private fun connectNow() {
        if (closed) return
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        authTimeoutTask?.cancel(true)
        authTimeoutTask = null
        runCatching { ws?.close(1000, "reconnect") }
        ws = null

        onStatus("连接斗鱼弹幕…")
        val req =
            Request.Builder()
                .url(ENDPOINT)
                // 不能用 apiOkHttp：它的拦截器会注入 B 站 UA/Origin/Referer。
                .header("User-Agent", USER_AGENT)
                .header("Origin", WEB)
                .header("Referer", "$WEB/")
                .build()
        ws = BiliClient.liveDanmakuOkHttp.newWebSocket(req, Listener())
    }

    override fun close() {
        closed = true
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        authTimeoutTask?.cancel(true)
        authTimeoutTask = null
        reconnectTask?.cancel(true)
        reconnectTask = null
        runCatching { ws?.close(1000, "bye") }
        ws = null
        scheduler.shutdownNow()
    }

    private fun scheduleReconnect(reason: String) {
        if (closed) return
        if (reconnectTask?.isDone == false) return
        val delaySec = (1L shl reconnectAttempt.coerceIn(0, 4)).coerceAtMost(10L)
        reconnectAttempt++
        onStatus("斗鱼弹幕重连($reconnectAttempt)：${delaySec}s")
        AppLog.w("DouyuWs", "scheduleReconnect roomId=$roomId reason=$reason")
        reconnectTask =
            scheduler.schedule(
                { connectNow() },
                delaySec,
                TimeUnit.SECONDS,
            )
    }

    private fun startHeartbeat() {
        if (closed) return
        heartbeatTask?.cancel(true)
        heartbeatTask =
            scheduler.scheduleWithFixedDelay(
                {
                    val hb = packet("type@=mrkl/")
                    runCatching { ws?.send(ByteString.of(*hb)) }
                },
                10,
                45,
                TimeUnit.SECONDS,
            )
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            onStatus("斗鱼弹幕已连接")
            // 斗鱼：socket 打开即视为加入房间，随后发登录与进组包。
            webSocket.send(ByteString.of(*packet("type@=loginreq/roomid@=$roomId/")))
            webSocket.send(ByteString.of(*packet("type@=joingroup/rid@=$roomId/gid@=-9999/")))
            startHeartbeat()
            // 兜底：若长时间没有任何报文，重连一次。
            authTimeoutTask?.cancel(true)
            authTimeoutTask =
                scheduler.schedule(
                    {
                        if (!authed && !closed) scheduleReconnect("silence timeout")
                    },
                    135,
                    TimeUnit.SECONDS,
                )
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            authed = true
            runCatching { handleFrame(bytes.toByteArray()) }
                .onFailure { AppLog.w("DouyuWs", "handle frame failed", it) }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            onStatus("斗鱼弹幕连接失败：${t.message}")
            AppLog.w("DouyuWs", "onFailure roomId=$roomId", t)
            scheduleReconnect("onFailure ${t::class.java.simpleName}")
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            runCatching { webSocket.close(code, reason) }
            scheduleReconnect("onClosing $code")
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            scheduleReconnect("onClosed $code")
        }
    }

    // ---------------------------------------------------------------- 协议解析

    private fun handleFrame(data: ByteArray) {
        for (body in splitPackets(data)) {
            val fields = DouyuStt.map(body)
            when (fields["type"]) {
                "chatmsg" -> parseChat(fields)?.let(onDanmaku)
                "comm_chatmsg" -> parseSuperChat(fields)?.let(onSuperChat)
                "rss" -> if (fields["ss"] == "0") onStatus("主播已下播")
                else -> Unit
            }
        }
    }

    private fun parseChat(fields: Map<String, String>): LiveMessageClient.LiveDanmakuEvent? {
        val rid = fields["rid"]?.trim().orEmpty()
        if (rid.isNotEmpty() && roomId > 0 && rid != roomId.toString()) return null
        val text = fields["txt"].orEmpty()
        if (text.isBlank()) return null
        val color = colorOf(fields["col"]?.trim()?.toIntOrNull() ?: 0)
        val ts = fields["cst"]?.trim()?.toLongOrNull()
        val eventMs =
            when {
                ts == null -> System.currentTimeMillis()
                ts > 100_000_000_000 -> ts // 毫秒
                else -> ts * 1000 // 秒
            }
        return LiveMessageClient.LiveDanmakuEvent(
            text = text,
            color = color,
            eventTimeMs = eventMs,
            sendTimeMs = null,
            rndTimeMs = null,
            emotes = emptyList(),
        )
    }

    private fun parseSuperChat(fields: Map<String, String>): LiveSuperChat? {
        val nested = fields["chatmsg"] ?: return null
        if (!nested.contains("@=")) return null
        val now = fields["now"]?.trim()?.toLongOrNull() ?: return null
        val seconds = fields["cet"]?.trim()?.toLongOrNull() ?: return null
        val cents = fields["cprice"]?.trim()?.toLongOrNull() ?: return null
        if (cents <= 0 || seconds <= 0) return null
        val chat = DouyuStt.map(nested)
        val face = chat["ic"]?.trim().orEmpty()
        val id = synchronized(this) { ++superChatSeq } + now
        return LiveSuperChat(
            id = id,
            uid = 0,
            userName = chat["nn"].orEmpty(),
            userFaceUrl = if (face.isBlank()) null else "https://apic.douyucdn.cn/upload/${face}_small.jpg",
            userNameColor = "#4A4A4A",
            price = cents / 100,
            message = chat["txt"].orEmpty(),
            backgroundImageUrl = null,
            backgroundColor = "#C1C1FF",
            backgroundBottomColor = "#292A60",
            backgroundPriceColor = "#7497CD",
            messageFontColor = "#FFFFFF",
            startTimeSeconds = now / 1000,
            endTimeSeconds = now / 1000 + seconds,
            durationSeconds = seconds,
        )
    }

    private fun colorOf(col: Int): Int =
        when (col) {
            1 -> 0xFFFF0000.toInt()
            2 -> 0xFF1E87F0.toInt()
            3 -> 0xFF7AC84B.toInt()
            4 -> 0xFFFF7F00.toInt()
            5 -> 0xFF9B39F4.toInt()
            6 -> 0xFFFF69B4.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    companion object {
        private const val ENDPOINT = "wss://danmuproxy.douyu.com:8506"
        private const val WEB = "https://www.douyu.com"

        /** 移动端 UA：弹幕握手用（斗鱼网关对非浏览器身份较敏感）。 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/90.0.4430.91 Mobile Safari/537.36"
        private const val CLIENT_PACKET_TYPE = 689

        /** 构造一个斗鱼报文（小端长度 ×2 + 类型 + 加密 + 保留 + 正文 + \0）。 */
        fun packet(body: String): ByteArray {
            val text = body.toByteArray(Charsets.UTF_8)
            val length = 8 + text.size + 1
            val buf = ByteArray(length)
            fun putInt(offset: Int, v: Int) {
                buf[offset] = (v and 0xFF).toByte()
                buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
                buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
                buf[offset + 3] = ((v shr 24) and 0xFF).toByte()
            }
            putInt(0, length)
            putInt(4, length)
            buf[8] = (CLIENT_PACKET_TYPE and 0xFF).toByte()
            buf[9] = ((CLIENT_PACKET_TYPE shr 8) and 0xFF).toByte()
            buf[10] = 0 // encrypted
            buf[11] = 0 // reserved
            System.arraycopy(text, 0, buf, 12, text.size)
            buf[length - 1] = 0
            return buf
        }

        /** 拆分一帧中的多个报文，返回正文列表（去掉结尾 \0）。 */
        fun splitPackets(data: ByteArray): List<String> {
            val out = ArrayList<String>(4)
            var off = 0
            while (off + 12 <= data.size) {
                fun readInt(o: Int): Int =
                    (data[o].toInt() and 0xFF) or
                        ((data[o + 1].toInt() and 0xFF) shl 8) or
                        ((data[o + 2].toInt() and 0xFF) shl 16) or
                        ((data[o + 3].toInt() and 0xFF) shl 24)
                val length = readInt(off)
                if (length < 9 || off + 4 + length > data.size) break
                val bodyStart = off + 12
                val bodyEnd = off + 3 + length // 不含结尾 \0
                if (bodyEnd > data.size || bodyStart > bodyEnd) break
                out.add(String(data, bodyStart, bodyEnd - bodyStart, Charsets.UTF_8))
                off += 4 + length
            }
            return out
        }
    }
}

/** 斗鱼 STT 文本格式：`key@=value/` 键值对；列表值为其条目，各以 `/` 结尾。 */
internal object DouyuStt {
    fun escape(value: String): String = value.replace("@", "@A").replace("/", "@S")

    fun unescape(value: String): String = value.replace("@S", "/").replace("@A", "@")

    fun map(text: String): Map<String, String> {
        val pairs = HashMap<String, String>()
        for (part in text.split("/")) {
            val sep = part.indexOf("@=")
            if (sep <= 0) continue
            pairs[part.substring(0, sep)] = unescape(part.substring(sep + 2))
        }
        return pairs
    }
}
