package blbl.cat3399.feature.live

import blbl.cat3399.core.live.Tars
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.SystemClock
import okio.ByteString
import java.net.SocketTimeoutException
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

    /** 最近一次收到帧的时刻（elapsedRealtime）。只用于日志里区分「房间安静」和「连接已死」。 */
    @Volatile private var lastFrameAtMs = 0L

    /** 连续几次心跳发送失败。到 [HEARTBEAT_MAX_FAILS] 才判定连接失效。 */
    @Volatile private var heartbeatFailStreak = 0

    /** 心跳序号，用于定期打一行「还活着」的日志。 */
    private val heartbeatCount = AtomicInteger(0)

    private var heartbeatTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    /**
     * 虎牙专用的 WebSocket 客户端。
     *
     * [BiliClient.liveDanmakuOkHttp] 把 `pingInterval` 显式设成 0（B 站弹幕服务端自己会推心跳，
     * 客户端 Ping 反而会干扰），但虎牙这条连接必须由客户端主动 Ping：
     * 没有协议层 Ping/Pong，就分不清「房间安静没推送」和「TCP 已经半死」——
     * 之前那个「静默 180s 无条件重连」就是这么来的，安静房间每 3 分钟自己断一次。
     *
     * 实测（2026-10-23，`ws_ping_probe.py`）虎牙服务端会正常回 Pong，
     * 所以开启 20s Ping：**只有 Pong 超时（真死了）才触发 onFailure → 重连**。
     */
    private val wsClient by lazy {
        BiliClient.liveDanmakuOkHttp
            .newBuilder()
            .pingInterval(WS_PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun connect() {
        closed = false
        reconnectAttempt = 0
        connectNow()
    }

    private fun connectNow() {
        if (closed) return
        heartbeatTask?.cancel(true)
        heartbeatTask = null
        heartbeatFailStreak = 0
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
        ws = wsClient.newWebSocket(req, Listener())
    }

    override fun close() {
        closed = true
        heartbeatTask?.cancel(true)
        heartbeatTask = null
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
        heartbeatFailStreak = 0
        heartbeatTask =
            scheduler.scheduleWithFixedDelay(
                { sendHeartbeat() },
                HEARTBEAT_FIRST_SEC,
                HEARTBEAT_INTERVAL_SEC,
                TimeUnit.SECONDS,
            )
    }

    /**
     * 业务心跳（cmd 20）。
     *
     * **只有发送失败才算连接失效**。房间安静、服务端长时间不推帧都不是失败——
     * 那是正常状态（三分钟没人说话的房间本来就不该有弹幕），
     * 之前按「静默 180s」重连，等于每 3 分钟主动把好连接拆掉重建，
     * 重连瞬间补一批初始消息，看起来就是「弹幕有一点又没了」。
     *
     * 连接真正半死（TCP 黑洞）由 [wsClient] 的 20s Ping/Pong 兜住：
     * Pong 超时 OkHttp 会直接回调 onFailure，比这里更早、更准。
     */
    private fun sendHeartbeat() {
        if (closed) return
        val socket = ws
        if (socket == null) {
            scheduleReconnect("heartbeat: socket null")
            return
        }
        val sent = runCatching { socket.send(ByteString.of(*heartbeat())) }.getOrDefault(false)
        if (!sent) {
            heartbeatFailStreak++
            AppLog.w(
                "HuyaWs",
                "heartbeat send failed ${heartbeatFailStreak}/${HEARTBEAT_MAX_FAILS} roomId=$roomId",
            )
            if (heartbeatFailStreak >= HEARTBEAT_MAX_FAILS) {
                scheduleReconnect("heartbeat send failed x$heartbeatFailStreak")
            }
            return
        }
        heartbeatFailStreak = 0
        val n = heartbeatCount.incrementAndGet()
        if (n == 1 || n % HEARTBEAT_LOG_EVERY == 0) {
            // idle 很大但心跳正常 = 房间真的安静，不重连。idle=never 表示连上后一帧都没推。
            val idle =
                if (lastFrameAtMs <= 0L) {
                    "never"
                } else {
                    "${(SystemClock.elapsedRealtime() - lastFrameAtMs) / 1000}s"
                }
            AppLog.i(
                "HuyaWs",
                "heartbeat #$n ok idle=$idle frames=${frameCount.get()} chats=${chatCount.get()}",
            )
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            onStatus("虎牙弹幕已连接")
            reconnectAttempt = 0
            heartbeatFailStreak = 0
            AppLog.i("HuyaWs", "open room=$roomId register uid=$presenterUid code=${response.code}")
            // 诊断：把握手响应头落下来。PC 端用同样的报文能收到推送，设备端只有 cmd=17/21，
            // 需要看服务端在这条连接上回了什么（server / 有无 x- 头 / 是否被降级）。
            AppLog.i(
                "HuyaWs",
                "handshake headers=" + response.headers.joinToString("; ") { "${it.first}=${it.second.take(30)}" },
            )
            if (presenterUid <= 0L) {
                AppLog.w("HuyaWs", "presenterUid=0 → 分组注册成 live:0/chat:0，服务端不会推这个房间")
            }
            val reg = register(presenterUid)
            webSocket.send(ByteString.of(*reg))
            AppLog.i(
                "HuyaWs",
                "register len=${reg.size} hex=" +
                    reg.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            )
            runCatching { ws?.send(ByteString.of(*heartbeat())) }
            startHeartbeat()
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            // 虎牙正常走二进制帧；万一某些节点改推文本帧，这里至少能留下痕迹。
            AppLog.w("HuyaWs", "text frame len=${text.length} head=${text.take(60)}")
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            val n = frameCount.incrementAndGet()
            lastFrameAtMs = SystemClock.elapsedRealtime()
            val raw = bytes.toByteArray()
            if (n <= 12 || n % 40 == 0) {
                val cmd = runCatching { Tars.int(Tars.decode(raw), 0) }.getOrNull()
                val hex = if (n <= 6) " hex=" + raw.take(60).joinToString("") { "%02x".format(it.toInt() and 0xFF) } else ""
                AppLog.i("HuyaWs", "frame #$n cmd=$cmd len=${raw.size} chats=${chatCount.get()}$hex")
            }
            runCatching { handleFrame(raw) }
                .onFailure { AppLog.w("HuyaWs", "handle frame failed", it) }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            // 只有真失败才走到这里：连接层异常，或 OkHttp 的 Ping 在 20s 内没等到 Pong
            // （SocketTimeoutException）。房间安静不会触发。
            val kind = if (t is SocketTimeoutException) "ping timeout" else t::class.java.simpleName
            onStatus("虎牙弹幕连接失败：${t.message}")
            AppLog.w("HuyaWs", "onFailure roomId=$roomId kind=$kind msg=${t.message}")
            scheduleReconnect("onFailure $kind")
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
        /**
         * 协议层 Ping 间隔。虎牙服务端会回 Pong（实测 3/3 秒回），
         * 因此这是「心跳失败」最可靠的判据：Pong 20s 内没等到 → OkHttp 直接回调 onFailure。
         */
        private const val WS_PING_INTERVAL_SEC = 20L

        /** 业务心跳（cmd 20）：建连 10s 后第一次，之后每 60s 一次。 */
        private const val HEARTBEAT_FIRST_SEC = 10L
        private const val HEARTBEAT_INTERVAL_SEC = 60L

        /** 连续这么多次心跳发送失败才重连（一次失败可能只是发送队列瞬时拥塞）。 */
        private const val HEARTBEAT_MAX_FAILS = 2

        /** 每 N 次心跳打一行「连接还活着」的日志（安静房间排查用）。 */
        private const val HEARTBEAT_LOG_EVERY = 5
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
