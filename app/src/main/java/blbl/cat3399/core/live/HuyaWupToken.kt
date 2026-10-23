package blbl.cat3399.core.live

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 虎牙 native FLV 凭据（WUP `liveui.getCdnTokenInfoEx`）。
 *
 * 为什么需要它：房间下发的 web antiCode（`ctype=tars_mp`）只签出「短传输租约」地址，
 * CDN 在连接建立约 120 秒后主动断开（实测 120.8 秒，与码率无关），播放器只收到正常
 * EOF，表现是「看着看着停一下、不报错」。native 凭据（`ctype=huya_pc_exe`）没有这个
 * 限制：实测同房间连读 210 秒无 EOF，且其有效期只约束**新建连接**，不影响已建立的连接。
 *
 * 协议（移植自 PureLive `live_api` 与其 `tars.dart`）：
 * - `POST https://wup.huya.com/`，`content-type: application/x-wup`；
 * - 包体是 TUP3 报文：4 字节大端长度 + 字段 1=版本3、2=0、3=0、4=requestId、5=servant
 *   (`liveui`)、6=function (`getCdnTokenInfoEx`)、7=sBuffer（参数 map）、8=0、9/10=空 map；
 * - `tReq` 结构：tag0 sFlvUrl（空）、tag1 sStreamName、tag2 iLoopTime=0、tag3 tId、tag4 iAppId=66；
 * - 响应里 `tRsp` 的 tag0 是 sFlvToken —— 一段**完整的** query 串，
 *   **必须原样使用，不能再用 antiCode 算法二次签名**（二次签名会得到不匹配的 wsSecret，403，实测）。
 *
 * 取不到时返回 null，调用方回落到 web antiCode 签名，不影响原有播放能力。
 */
internal object HuyaWupToken {
    private const val WUP_URL = "https://wup.huya.com/"

    /** 明文兜底：仅当 HTTPS 各档都失败时才用（实测同一份报文明文同样 200）。 */
    private const val WUP_URL_PLAIN = "http://wup.huya.com/"

    /** 虎牙 native 凭据的 appId（PureLive `tokenAppId`）。 */
    private const val APP_ID = 66

    /** `tId.sHuYaUA`：native 客户端身份。 */
    private const val TARS_USER_AGENT = "pc_exe&7060000&official"

    /** 媒体/WUP 请求 UA：与 PureLive 的 `hysdkUserAgent` 一致。 */
    const val NATIVE_USER_AGENT =
        "HYSDK(Windows,30000002)_APP(pc_exe&7100004&official)_SDK(trans&2.40.0.6448)"

    /** 连续失败达到该次数后暂停重试，避免每轮续流空打一次请求。 */
    private const val FAILURE_BLOCK_MS = 3 * 60 * 1000L

    private val CONTENT_TYPE = "application/x-wup".toMediaType()

    /**
     * 传输层阶梯：设备上恒定 502，而同网络下用同一份报文从 PC 打
     * TLS1.3 / TLS1.2 / keep-alive / 明文 http 四种组合全是 200（`server: HUYA/2.15.3`），
     * 说明报文不是问题、差异在设备侧的传输层。于是按「最可能通」到「最不可能但能绕过
     * TLS 中间层」的顺序逐档尝试，成败都记进日志：
     *
     * 1. `h2`      —— 默认（会协商 HTTP/2）
     * 2. `h1.1`    —— 强制 HTTP/1.1，排除网关对 h2 的处理差异
     * 3. `h1.1-tls12` —— 再锁到 TLS1.2，排除设备 TLS1.3 栈的差异
     * 4. `http`    —— 明文。设备上若有 TLS 中间层（安全软件/模拟器网络层）在 502，
     *                这一档能整体绕开；虎牙的媒体地址本身也是明文 http。
     */
    private class Attempt(
        val label: String,
        val url: String,
        val client: OkHttpClient,
    )

    private val attempts: List<Attempt> by lazy {
        val base =
            BiliClient.apiOkHttp
                .newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                // 强制直连：设备上常见的代理/分流（如 Clash 规则把 huya 走某个不健康节点）
                // 会让这一个请求返回 502，而视频与房间接口是直连、看起来一切正常。
                .proxy(Proxy.NO_PROXY)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
        val tls12 =
            ConnectionSpec
                .Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        listOf(
            Attempt("h2", WUP_URL, base.build()),
            Attempt("h1.1", WUP_URL, base.protocols(listOf(Protocol.HTTP_1_1)).build()),
            Attempt(
                "h1.1-tls12",
                WUP_URL,
                base
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .connectionSpecs(listOf(tls12))
                    .build(),
            ),
            Attempt("http", WUP_URL_PLAIN, base.protocols(listOf(Protocol.HTTP_1_1)).build()),
        )
    }

    @Volatile
    private var failures = 0

    @Volatile
    private var blockedUntilMs = 0L

    /** 取 native 凭据；任何一步失败都返回 null。 */
    suspend fun fetch(streamName: String): String? =
        withContext(Dispatchers.IO) {
            val name = streamName.trim()
            if (name.isEmpty()) return@withContext null
            if (System.currentTimeMillis() < blockedUntilMs) return@withContext null
            val body = runCatching { encodeRequest(name) }.getOrNull() ?: return@withContext null
            var reason = ""
            for (attempt in attempts) {
                val request = buildRequest(body, attempt.url)
                val token =
                    runCatching {
                        attempt.client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                reason = "${attempt.label} ${describe(response)}"
                                AppLog.w("HuyaToken", "wup ${attempt.label} -> ${describe(response)}")
                                return@use null
                            }
                            val bytes = response.body?.bytes()
                            if (bytes == null || bytes.isEmpty()) {
                                reason = "${attempt.label} empty body"
                                return@use null
                            }
                            parseToken(bytes).also { parsed ->
                                if (parsed == null) reason = "${attempt.label} token unparsable"
                            }
                        }
                    }.onFailure {
                        reason = "${attempt.label} ${it::class.java.simpleName}: ${it.message}"
                    }.getOrNull()
                if (token != null) {
                    failures = 0
                    blockedUntilMs = 0L
                    AppLog.i(
                        "HuyaToken",
                        "native token ok via ${attempt.label} (${attempt.url.substringBefore("//")})",
                    )
                    return@withContext token
                }
            }
            failures++
            if (failures >= 2) blockedUntilMs = System.currentTimeMillis() + FAILURE_BLOCK_MS
            AppLog.w("HuyaToken", "native token failed [$reason] failures=$failures")
            null
        }

    private fun buildRequest(
        body: ByteArray,
        url: String,
    ): Request =
        Request.Builder()
            .url(url)
            .header("content-type", "application/x-wup")
            .header("origin", "https://www.huya.com")
            .header("referer", "https://www.huya.com/")
            .header("user-agent", NATIVE_USER_AGENT)
            .post(body.toRequestBody(CONTENT_TYPE))
            .build()

    /** 失败时的诊断串：状态码 + 协议/TLS + 网关标识 + 响应体可打印前缀。 */
    private fun describe(response: Response): String {
        val server = response.header("server").orEmpty()
        val via = response.header("via").orEmpty()
        val cache = response.header("x-cache").orEmpty()
        val tls = response.handshake?.tlsVersion?.javaName ?: "-"
        val snippet =
            runCatching {
                val bytes = response.body?.bytes() ?: return@runCatching ""
                String(bytes, Charsets.ISO_8859_1)
                    .filter { it.code in 32..126 }
                    .take(100)
                    .trim()
            }.getOrDefault("")
        return "http=${response.code} proto=${response.protocol} tls=$tls " +
            "server=$server via=$via cache=$cache body=$snippet"
    }

    /**
     * 从响应字节里取出 `sFlvToken`。
     *
     * 不按结构逐字段解析：token 本身是纯 ASCII 的 query 串，直接从 `wsSecret=` 起截取可打印
     * 区间最稳（结构里还会嵌套 map/struct，字段顺序一变就得跟着改）。仍然做形态校验，
     * 不是 native 形态就当作失败。
     */
    private fun parseToken(bytes: ByteArray): String? {
        // ISO-8859-1 保证字节与字符 1:1，便于按可打印区间截取。
        val text = String(bytes, Charsets.ISO_8859_1)
        val start = text.indexOf("wsSecret=")
        if (start < 0) return null
        var end = start
        while (end < text.length) {
            val code = text[end].code
            if (code < 32 || code > 126) break
            end++
        }
        val token = text.substring(start, end).trim()
        return token.takeIf { it.contains("ctype=huya_pc_exe") }
    }

    // ---------------------------------------------------------------- 报文编码

    private const val TYPE_INT8 = 0
    private const val TYPE_INT16 = 1
    private const val TYPE_INT32 = 2
    private const val TYPE_STRING1 = 6
    private const val TYPE_STRING4 = 7
    private const val TYPE_MAP = 8
    private const val TYPE_STRUCT_BEGIN = 10
    private const val TYPE_STRUCT_END = 11
    private const val TYPE_ZERO = 12
    private const val TYPE_SIMPLE_LIST = 13

    private class TarsWriter(size: Int = 256) {
        private val out = ByteArrayOutputStream(size)

        fun head(
            type: Int,
            tag: Int,
        ) {
            if (tag < 15) {
                out.write((tag shl 4) or type)
            } else {
                out.write(0xF0 or type)
                out.write(tag)
            }
        }

        fun int(
            tag: Int,
            value: Int,
        ) {
            when {
                value == 0 -> head(TYPE_ZERO, tag)
                value in -128..127 -> {
                    head(TYPE_INT8, tag)
                    out.write(value and 0xFF)
                }
                value in -32768..32767 -> {
                    head(TYPE_INT16, tag)
                    out.write((value shr 8) and 0xFF)
                    out.write(value and 0xFF)
                }
                else -> {
                    head(TYPE_INT32, tag)
                    for (shift in intArrayOf(24, 16, 8, 0)) out.write((value shr shift) and 0xFF)
                }
            }
        }

        fun string(
            tag: Int,
            value: String,
        ) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > 255) {
                head(TYPE_STRING4, tag)
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((bytes.size shr shift) and 0xFF)
            } else {
                head(TYPE_STRING1, tag)
                out.write(bytes.size)
            }
            out.write(bytes)
        }

        fun byteArray(
            tag: Int,
            value: ByteArray,
        ) {
            head(TYPE_SIMPLE_LIST, tag)
            head(TYPE_INT8, 0)
            int(0, value.size)
            out.write(value)
        }

        fun struct(
            tag: Int,
            fields: TarsWriter.() -> Unit,
        ) {
            head(TYPE_STRUCT_BEGIN, tag)
            this.fields()
            head(TYPE_STRUCT_END, 0)
        }

        fun mapStringBytes(
            tag: Int,
            items: Map<String, ByteArray>,
        ) {
            head(TYPE_MAP, tag)
            int(0, items.size)
            for ((key, value) in items) {
                string(0, key)
                byteArray(1, value)
            }
        }

        fun emptyMap(tag: Int) {
            head(TYPE_MAP, tag)
            int(0, 0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** 构造 `liveui.getCdnTokenInfoEx` 的 TUP3 请求体。 */
    private fun encodeRequest(streamName: String): ByteArray {
        val tReq = TarsWriter()
        tReq.struct(0) {
            string(0, "")
            string(1, streamName)
            int(2, 0)
            struct(3) {
                int(0, 0) // lUid：匿名即可
                string(1, "") // sGuid
                string(2, "") // sToken
                string(3, TARS_USER_AGENT) // sHuYaUA
                string(4, "") // sCookie
                int(5, 0) // iTokenType
                string(6, "") // sDeviceInfo
                string(7, "") // sQIMEI
            }
            int(4, APP_ID)
        }
        val params = TarsWriter()
        params.mapStringBytes(0, mapOf("tReq" to tReq.toByteArray()))

        val body = TarsWriter()
        body.int(1, 3) // TUP3
        body.int(2, 0)
        body.int(3, 0)
        body.int(4, 0) // requestId
        body.string(5, "liveui")
        body.string(6, "getCdnTokenInfoEx")
        body.byteArray(7, params.toByteArray())
        body.int(8, 0)
        body.emptyMap(9)
        body.emptyMap(10)
        val raw = body.toByteArray()

        val framed = ByteArrayOutputStream(raw.size + 4)
        framed.write((raw.size ushr 24) and 0xFF)
        framed.write((raw.size ushr 16) and 0xFF)
        framed.write((raw.size ushr 8) and 0xFF)
        framed.write(raw.size and 0xFF)
        framed.write(raw)
        return framed.toByteArray()
    }
}
