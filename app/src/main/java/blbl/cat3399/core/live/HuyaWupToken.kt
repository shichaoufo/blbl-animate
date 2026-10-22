package blbl.cat3399.core.live

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
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

    /** 虎牙 native 凭据的 appId（PureLive `tokenAppId`）。 */
    private const val APP_ID = 66

    /** `tId.sHuYaUA`：native 客户端身份。 */
    private const val TARS_USER_AGENT = "pc_exe&7060000&official"

    /** 媒体/WUP 请求 UA：与 PureLive 的 `hysdkUserAgent` 一致。 */
    const val NATIVE_USER_AGENT =
        "HYSDK(Windows,30000002)_APP(pc_exe&7100004&official)_SDK(trans&2.40.0.6448)"

    private val CONTENT_TYPE = "application/x-wup".toMediaType()

    /**
     * 独立 client：不带 B 站 cookie，也不走 apiOkHttp 的媒体链路。
     * 仍然基于 apiOkHttp，是为了复用连接池与 DNS 配置；拦截器只填空白头，这里全部显式设置。
     */
    private val client: OkHttpClient by lazy {
        BiliClient.apiOkHttp
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 取 native 凭据；任何一步失败都返回 null。 */
    suspend fun fetch(streamName: String): String? {
        val name = streamName.trim()
        if (name.isEmpty()) return null
        val body = runCatching { encodeRequest(name) }.getOrNull() ?: return null
        val request =
            Request.Builder()
                .url(WUP_URL)
                .header("content-type", "application/x-wup")
                .header("origin", "https://www.huya.com")
                .header("referer", "https://www.huya.com/")
                .header("user-agent", NATIVE_USER_AGENT)
                .post(body.toRequestBody(CONTENT_TYPE))
                .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLog.w("HuyaToken", "wup http=${response.code}")
                        return@use null
                    }
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        AppLog.w("HuyaToken", "wup empty body")
                        return@use null
                    }
                    parseToken(bytes)
                }
            }.onFailure { AppLog.w("HuyaToken", "wup request failed", it) }.getOrNull()
        }
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
        if (start < 0) {
            AppLog.w("HuyaToken", "wup response without token (${bytes.size}B)")
            return null
        }
        var end = start
        while (end < text.length) {
            val code = text[end].code
            if (code < 32 || code > 126) break
            end++
        }
        val token = text.substring(start, end).trim()
        if (!token.contains("ctype=huya_pc_exe")) {
            AppLog.w("HuyaToken", "unexpected token shape: ${token.take(80)}")
            return null
        }
        return token
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
