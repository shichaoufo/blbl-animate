package blbl.cat3399.feature.live

import blbl.cat3399.core.live.Tars
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 虎牙直播弹幕客户端（协议移植自 PureLive `live_danmaku/src/sites/huya.dart`，MIT License）。
 *
 * 协议要点（2026-10 与 PureLive 核对）：
 * - 端点：`wss://wsapi.huya.com`，二进制帧，整个帧是一个 Tars 结构：
 *   tag 0 = 命令（int），tag 1 = 负载（bytes）。
 * - 客户端命令：16 = 注册分组（负载 tag 0 = `["live:<uid>","chat:<uid>"]`，tag 1 = 空 token），
 *   20 = 心跳（空负载）。
 * - 服务端命令：7 = 单条推送（负载 tag 1 = uri，tag 2 = body，tag 5 = 消息 id）；
 *   22 = 批量推送（负载 tag 1 = 条目列表，条目 tag 0 = uri，tag 1 = body，tag 2 = 消息 id）。
 * - uri：1400 = 弹幕（body tag 0 = 发送者（tag 0 uid / tag 2 昵称），tag 3 = 文本，
 *   tag 6 = 子弹格式（tag 0 颜色））；8006 = 热度；6501 = 礼物；8001 = 直播结束。
 *
 * 说明：SuperChat（虎牙「留言板」，uri 2001314）依赖 WUP/Tars 请求，首版不解析；
 * 礼物消息不在弹幕区展示（与 B 站一致）。
 */
class HuyaDanmakuClient(
    private val roomId: Long,
    private val presenterUid: Long,
    private val onDanmaku: (LiveMessageClient.LiveDanmakuEvent) -> Unit,
    private val onSuperChat: (LiveSuperChat) -> Unit,
    private val onSuperChatDelete: (Set<Long>) -> Unit,
    private val onStatus: (String) -> Unit,
) : LiveDanmakuClient {
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "huya-live-ws").apply { isDaemon = true }
        }

    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var reconnectAttempt = 0

    /** 诊断：收到多少帧 / 解出多少条弹幕，用来区分「没推」和「解不出来」。 */
    private val frameCount = AtomicInteger(0)
    private val chatCount = AtomicInteger(0)

    private var heartbeatTask: ScheduledFuture<*>? = null
    private var silenceTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    override suspend fun connect() {
        closed = false
        reconnectAttempt = 0
        connectNow()
    }

    private fun connectNow() {
        if (closed) return
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        silenceTask?.cancel(true)
        silenceTask = null
        runCatching { ws?.close(1000, "reconnect") }
        ws = null

        onStatus("连接虎牙弹幕…")
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
        silenceTask?.cancel(true)
        silenceTask = null
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
        onStatus("虎牙弹幕重连($reconnectAttempt)：${delaySec}s")
        AppLog.w("HuyaWs", "scheduleReconnect roomId=$roomId reason=$reason")
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
                    runCatching { ws?.send(ByteString.of(*heartbeat())) }
                },
                10,
                60,
                TimeUnit.SECONDS,
            )
        // 兜底：长时间静默后重连（PureLive：max(3×60s, 90s) = 180s）。
        silenceTask?.cancel(true)
        silenceTask =
            scheduler.schedule(
                {
                    if (!closed) scheduleReconnect("silence timeout")
                },
                180,
                TimeUnit.SECONDS,
            )
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            onStatus("虎牙弹幕已连接")
            AppLog.i("HuyaWs", "open room=$roomId register uid=$presenterUid")
            if (presenterUid <= 0L) {
                AppLog.w("HuyaWs", "presenterUid=0 → 分组注册成 live:0/chat:0，服务端不会推这个房间")
            }
            webSocket.send(ByteString.of(*register(presenterUid)))
            runCatching { ws?.send(ByteString.of(*heartbeat())) }
            startHeartbeat()
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            val n = frameCount.incrementAndGet()
            if (n <= 5 || n % 50 == 0) {
                AppLog.i("HuyaWs", "frame #$n len=${bytes.size} chats=${chatCount.get()}")
            }
            runCatching { handleFrame(bytes.toByteArray()) }
                .onFailure { AppLog.w("HuyaWs", "handle frame failed", it) }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            onStatus("虎牙弹幕连接失败：${t.message}")
            AppLog.w("HuyaWs", "onFailure roomId=$roomId", t)
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
        val outer = Tars.decode(data)
        when (Tars.int(outer, 0)) {
            CMD_PUSH -> {
                // 单条推送：负载 tag 1 = uri，tag 2 = body，tag 5 = 消息 id。
                val payload = Tars.decode(Tars.bytes(outer, 1) ?: return)
                handleItem(Tars.int(payload, 1), Tars.bytes(payload, 2))
            }
            CMD_BATCH -> {
                // 批量推送：负载 tag 1 = 条目列表（tag 0 = uri，tag 1 = body，tag 2 = 消息 id）。
                val payload = Tars.decode(Tars.bytes(outer, 1) ?: return)
                val items = Tars.list(payload, 1) ?: return
                for (item in items) {
                    @Suppress("UNCHECKED_CAST")
                    val entry = item as? Map<Int, Any?> ?: continue
                    handleItem(Tars.int(entry, 0), Tars.bytes(entry, 1))
                }
            }
            else -> Unit
        }
    }

    private fun handleItem(
        uri: Long?,
        body: ByteArray?,
    ) {
        when (uri) {
            URI_CHAT ->
                parseChat(body)?.let {
                    chatCount.incrementAndGet()
                    onDanmaku(it)
                }
            URI_END -> onStatus("主播已下播")
            else -> Unit
        }
    }

    private fun parseChat(body: ByteArray?): LiveMessageClient.LiveDanmakuEvent? {
        val notice = Tars.decode(body ?: return null)
        val sender = Tars.struct(notice, 0)
        val text = Tars.string(notice, 3) ?: return null
        if (text.isBlank()) return null
        val fontColor = Tars.int(Tars.struct(notice, 6), 0) ?: 0
        return LiveMessageClient.LiveDanmakuEvent(
            text = text,
            color = if (fontColor <= 0) 0xFFFFFFFF.toInt() else fontColor.toInt(),
            eventTimeMs = System.currentTimeMillis(),
            sendTimeMs = null,
            rndTimeMs = null,
            emotes = emptyList(),
        )
    }

    companion object {
        private const val ENDPOINT = "wss://wsapi.huya.com"
        private const val WEB = "https://www.huya.com"

        /** 与 HuyaApi 一致的移动端 UA：弹幕握手用。 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/90.0.4430.91 Mobile Safari/537.36"
        private const val CMD_REGISTER = 16L
        private const val CMD_HEARTBEAT = 20L
        private const val CMD_PUSH = 7L
        private const val CMD_BATCH = 22L
        private const val URI_CHAT = 1400L
        private const val URI_END = 8001L

        /** 命令 16：注册主播的直播/聊天分组。 */
        fun register(presenterUid: Long): ByteArray {
            val uid = presenterUid.takeIf { it > 0 } ?: 0L
            val payload =
                Tars.Writer()
                    .apply {
                        writeStringList(0, listOf("live:$uid", "chat:$uid"))
                        writeString(1, "")
                    }.toBytes()
            return Tars.Writer()
                .apply {
                    writeInt(0, CMD_REGISTER)
                    writeBytes(1, payload)
                }.toBytes()
        }

        /** 命令 20：心跳（空负载）。 */
        fun heartbeat(): ByteArray =
            Tars.Writer()
                .apply {
                    writeInt(0, CMD_HEARTBEAT)
                    writeBytes(1, ByteArray(0))
                }.toBytes()
    }
}
