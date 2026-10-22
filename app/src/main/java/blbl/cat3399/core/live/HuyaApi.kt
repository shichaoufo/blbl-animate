package blbl.cat3399.core.live

import android.util.Base64
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 虎牙直播接口（移植自 PureLive `live_core/src/sites/huya`，MIT License）。
 *
 * 已核对的能力（2026-10 实测）：
 * - 分区：`live.cdn.huya.com/liveconfig/game/bussLive?bussType=<1|2|8|3>`（四个固定一级分区）
 * - 推荐 / 分区房间：`www.huya.com/cache.php?m=LiveList&do=getLiveListByPage`（每页 120）
 * - 房间信息 / 线路 / 清晰度：`mp.huya.com/cache.php?m=Live&do=profileRoom`
 * - 播放地址：对 `sFlvAntiCode` / `sHlsAntiCode` 做模板签名
 *   （`wsSecret = md5(fm 模板替换 $0=u, $1=streamName, $2=md5(seqid|ctype|t), $3=wsTime)`）
 * - 弹幕：`wss://wsapi.huya.com`（Tars 协议，见 [blbl.cat3399.feature.live.HuyaDanmakuClient]）
 *
 * 说明：匿名访客使用 PureLive 的临时 uid 方案签名（`fallbackViewerUid`），实测可正常拉流；
 * 未实现 WUP `getCdnTokenInfoEx` 原生令牌与 SuperChat 留言板（WUP/Tars 请求），
 * 这些在 PureLive 中属于增强路径，首版不接入。
 */
internal object HuyaApi {
    private const val SITE = "huya"
    private const val WEB = "https://www.huya.com"

    /** 移动端 Chrome UA：API 请求使用（与 PureLive 一致）。 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/90.0.4430.91 Mobile Safari/537.36 Edg/117.0.0.0"

    /** PC 客户端 UA：媒体请求头使用（与 PureLive 一致）。 */
    const val MEDIA_USER_AGENT =
        "HYSDK(Windows,30000002)_APP(pc_exe&7100004&official)_SDK(trans&2.40.0.6448)"

    /** 虎牙一级分区（固定四个，与 PureLive 一致）。 */
    private val TOP_CATEGORIES =
        listOf(
            "1" to "网游",
            "2" to "单机",
            "8" to "娱乐",
            "3" to "手游",
        )

    /** 清晰度 id 映射：虎牙 iBitRate 0（原画）映射为应用通用的「原画」id。 */
    const val QUALITY_SOURCE = 10_000

    private const val PROFILE_CACHE_TTL_MS = 3_000L

    /** native 凭据缓存时长：凭据本身 5 分钟有效，留 1 分钟余量。 */
    private const val NATIVE_TOKEN_TTL_MS = 4 * 60 * 1000L

    // ---------------------------------------------------------------- 访客 uid

    @Volatile
    private var viewerUid: Long = 0

    /**
     * 签名用访客 uid：匿名登录一次；失败时用 PureLive 的临时 uid
     * （`1400000000000 + rand(1e6)*1e5 + rand(1e5)`）。
     */
    private suspend fun ensureViewerUid(): Long {
        viewerUid.takeIf { it > 0 }?.let { return it }
        val anonymous = runCatching { fetchAnonymousUid() }.getOrDefault(0)
        val uid =
            if (anonymous > 0) {
                anonymous
            } else {
                1_400_000_000_000L + Random.nextInt(1_000_000) * 100_000L + Random.nextInt(100_000)
            }
        synchronized(this) { viewerUid = uid }
        return uid
    }

    private suspend fun fetchAnonymousUid(): Long {
        val json =
            BiliClient.getJson(
                "https://udblgn.huya.com/web/anonymousLogin",
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$WEB/"),
                noCookies = true,
            )
        return json.optJSONObject("data")?.optLong("uid", 0) ?: 0
    }

    // ---------------------------------------------------------------- 分区

    suspend fun categories(): List<LiveAreaParent> {
        val out = ArrayList<LiveAreaParent>(TOP_CATEGORIES.size)
        for ((id, name) in TOP_CATEGORIES) {
            val json =
                BiliClient.getJson(
                    "https://live.cdn.huya.com/liveconfig/game/bussLive?bussType=$id",
                    headers = mapOf("User-Agent" to USER_AGENT),
                    noCookies = true,
                )
            if (json.optInt("status", -1) != 200) {
                error("$SITE bussLive failed: status=${json.optInt("status")}")
            }
            val arr = json.optJSONArray("data") ?: JSONArray()
            val children =
                withContext(Dispatchers.Default) {
                    val list = ArrayList<LiveAreaParent.Child>(arr.length())
                    for (i in 0 until arr.length()) {
                        val g = arr.optJSONObject(i) ?: continue
                        val gid = g.optDouble("gid", 0.0).toLong()
                        val gname = g.optString("gameFullName").trim()
                        if (gid <= 0 || gname.isBlank()) continue
                        list.add(
                            LiveAreaParent.Child(
                                id = gid.toInt(),
                                parentId = id.toInt(),
                                name = gname,
                                hot = false,
                                coverUrl = "https://huyaimg.msstatic.com/cdnimage/game/$gid-MS.jpg",
                            ),
                        )
                    }
                    list
                }
            out.add(LiveAreaParent(id = id.toInt(), name = name, children = children))
        }
        return out
    }

    // ---------------------------------------------------------------- 房间列表

    /** 推荐直播（每页 120 个）。 */
    suspend fun recommendRooms(page: Int): List<LiveRoomCard> = liveList(page = page, gameId = null)

    /** 分区房间（每页 120 个）。 */
    suspend fun areaRooms(gameId: Int, page: Int): List<LiveRoomCard> = liveList(page = page, gameId = gameId.toString())

    private suspend fun liveList(page: Int, gameId: String?): List<LiveRoomCard> {
        val p = page.coerceAtLeast(1)
        val params =
            buildMap {
                put("m", "LiveList")
                put("do", "getLiveListByPage")
                put("tagAll", "0")
                if (gameId != null) put("gameId", gameId)
                put("page", p.toString())
            }
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val headers =
            buildMap {
                put("User-Agent", USER_AGENT)
                if (gameId == null) {
                    // 推荐接口需要 Origin/Referer（与 PureLive 一致）。
                    put("Origin", WEB)
                    put("Referer", "$WEB/")
                }
            }
        val json =
            BiliClient.getJson(
                "https://www.huya.com/cache.php?$query",
                headers = headers,
                noCookies = true,
            )
        if (json.optInt("status", -1) != 200) {
            error("$SITE getLiveListByPage failed: status=${json.optInt("status")}")
        }
        val datas = json.optJSONObject("data")?.optJSONArray("datas") ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val out = ArrayList<LiveRoomCard>(datas.length())
            for (i in 0 until datas.length()) {
                val r = datas.optJSONObject(i) ?: continue
                val roomId = r.optString("profileRoom").trim().toLongOrNull() ?: continue
                if (roomId <= 0) continue
                out.add(
                    LiveRoomCard(
                        roomId = roomId,
                        uid = 0,
                        title = titleOf(r.optString("introduction"), r.optString("roomName")),
                        uname = r.optString("nick").trim(),
                        coverUrl = normalizeImageUrl(r.optString("screenshot")),
                        faceUrl = normalizeImageUrl(r.optString("avatar180")),
                        online = parseCount(r.opt("totalCount")),
                        isLive = true,
                        parentAreaId = null,
                        parentAreaName = null,
                        areaId = null,
                        areaName = r.optString("gameFullName").trim().takeIf { it.isNotBlank() },
                        keyframe = null,
                    ),
                )
            }
            out
        }
    }

    // ---------------------------------------------------------------- 房间信息

    private class Profile(
        val json: JSONObject,
        val fetchedAtMs: Long,
    )

    @Volatile
    private var profileCache: Pair<Long, Profile>? = null

    private suspend fun profile(roomId: Long, force: Boolean = false): JSONObject {
        if (!force) {
            profileCache?.let { (id, p) ->
                if (id == roomId && System.currentTimeMillis() - p.fetchedAtMs < PROFILE_CACHE_TTL_MS) return p.json
            }
        }
        val headers =
            mapOf(
                "Accept" to "*/*",
                "Origin" to WEB,
                "Referer" to "$WEB/",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-site",
                "User-Agent" to USER_AGENT,
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
            )
        val json =
            BiliClient.getJson(
                "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$roomId&showSecret=1&_=" +
                    System.currentTimeMillis(),
                headers = headers,
                noCookies = true,
            )
        val status = json.optInt("status", -1)
        if (status == 422) error("$SITE profileRoom: room not found")
        if (status != 200) error("$SITE profileRoom failed: status=$status ${json.optString("message")}")
        profileCache = roomId to Profile(json, System.currentTimeMillis())
        return json
    }

    suspend fun roomInfo(roomId: Long): BiliApi.LiveRoomInfo {
        val root = profile(roomId)
        val data = root.optJSONObject("data") ?: error("$SITE profileRoom: no data")
        val live = data.optJSONObject("liveData") ?: JSONObject()
        val info = data.optJSONObject("profileInfo") ?: JSONObject()
        val liveStatus = data.optString("liveStatus").trim().uppercase()
        return BiliApi.LiveRoomInfo(
            roomId = roomId,
            // 主播 uid：弹幕注册分组（live:<uid>/chat:<uid>）需要。
            uid = info.optLong("uid", 0).takeIf { it > 0 } ?: live.optLong("uid", 0),
            uname = info.optString("nick").trim().takeIf { it.isNotBlank() },
            faceUrl = normalizeImageUrl(info.optString("avatar180")).takeIf { it.isNotBlank() },
            title = titleOf(live.optString("introduction"), live.optString("roomName")),
            liveStatus = if (liveStatus == "ON") 1 else 0,
            areaName = live.optString("gameFullName").trim().takeIf { it.isNotBlank() },
            parentAreaName = null,
        )
    }

    // ---------------------------------------------------------------- 播放地址

    /**
     * 播放地址。[qualityId] 为应用内清晰度 id（[QUALITY_SOURCE]=原画，其余为虎牙 iBitRate）。
     */
    suspend fun playUrl(roomId: Long, qualityId: Int): BiliApi.LivePlayUrl {
        val root = profile(roomId)
        val data = root.optJSONObject("data") ?: error("$SITE profileRoom: no data")
        val stream = data.optJSONObject("stream") ?: error("$SITE profileRoom: no stream")
        val bases = stream.optJSONArray("baseSteamInfoList") ?: JSONArray()
        val bitRate = uiQualityToBitRate(qualityId)
        val lines = ArrayList<BiliApi.LivePlayLine>()
        val seen = HashSet<String>()
        val flv = stream.optJSONObject("flv")?.optJSONArray("multiLine") ?: JSONArray()
        val hls = stream.optJSONObject("hls")?.optJSONArray("multiLine") ?: JSONArray()
        for ((arr, isHls) in listOf(flv to false, hls to true)) {
            for (i in 0 until arr.length()) {
                if (lines.size >= 6) break
                val entry = arr.optJSONObject(i) ?: continue
                val cdn = entry.optString("cdnType").trim()
                if (cdn.isBlank()) continue
                val base = findBase(bases, cdn) ?: continue
                val baseUrl = base.optString(if (isHls) "sHlsUrl" else "sFlvUrl").trim()
                val antiCode = base.optString(if (isHls) "sHlsAntiCode" else "sFlvAntiCode")
                val streamName = base.optString("sStreamName").trim()
                if (baseUrl.isBlank() || streamName.isBlank()) continue
                // FLV 优先用 native 凭据：web antiCode 签出的地址在连接建立约 120 秒后被 CDN
                // 主动断开（实测 120.8s，正常 EOF、不报错），native 凭据只约束新建连接，
                // 不会掐断已建立的连接（实测 210s 无 EOF）。
                val url =
                    (
                        if (isHls) {
                            runCatching { mediaUrl(baseUrl, streamName, antiCode, bitRate, true) }.getOrNull()
                        } else {
                            nativeFlvUrl(baseUrl, streamName, bitRate)
                                ?: runCatching { mediaUrl(baseUrl, streamName, antiCode, bitRate, false) }.getOrNull()
                        }
                    ) ?: continue
                if (seen.add(url)) {
                    lines.add(BiliApi.LivePlayLine(order = lines.size + 1, url = url))
                }
            }
        }
        if (lines.isEmpty()) error("$SITE profileRoom: no playable line")
        val qualities = parseQualities(data)
        return BiliApi.LivePlayUrl(
            currentQn = qualityId,
            acceptQn = qualities.map { it.first }.distinct(),
            qnDesc = qualities.toMap(),
            lines = lines,
        )
    }

    // ---------------------------------------------------------------- native 凭据

    /** native 凭据缓存：同一条流只取一次。失败不缓存，下次调用会重试。 */
    @Volatile
    private var nativeToken: Pair<String, String>? = null

    @Volatile
    private var nativeTokenFetchedAtMs = 0L

    /**
     * 用 native 凭据拼 FLV 地址（`ctype=huya_pc_exe`）。
     *
     * 注意：`sFlvToken` 本身就是完整的 query，**不能**再走 [signAntiCode] 二次签名——
     * 那样算出的 wsSecret 与服务端不一致，CDN 直接 403（实测）。
     */
    private suspend fun nativeFlvUrl(
        baseUrl: String,
        streamName: String,
        bitRate: Int,
    ): String? {
        val token = nativeTokenFor(streamName) ?: return null
        var query = token
        if (!Regex("(^|&)codec=").containsMatchIn(query)) query = "$query&codec=264"
        query = replaceQueryParameter(query, "ratio", if (bitRate > 0) bitRate.toString() else null)
        return "${baseUrl.trimEnd('/')}/$streamName.flv?$query"
    }

    private suspend fun nativeTokenFor(streamName: String): String? {
        val now = System.currentTimeMillis()
        nativeToken?.let { (name, token) ->
            if (name == streamName && now - nativeTokenFetchedAtMs < NATIVE_TOKEN_TTL_MS) return token
        }
        val token = HuyaWupToken.fetch(streamName)
        if (token.isNullOrBlank()) {
            AppLog.w("HuyaToken", "native token unavailable, fallback to web antiCode ${streamName.take(24)}")
            return null
        }
        nativeToken = streamName to token
        nativeTokenFetchedAtMs = now
        AppLog.i("HuyaToken", "native token ready ${streamName.take(24)} len=${token.length}")
        return token
    }

    private fun findBase(bases: JSONArray, cdnType: String): JSONObject? {
        for (i in 0 until bases.length()) {
            val b = bases.optJSONObject(i) ?: continue
            if (b.optString("sCdnType").trim() == cdnType) return b
        }
        return null
    }

    private fun parseQualities(data: JSONObject): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val seen = HashSet<Int>()
        fun add(rate: Int, name: String) {
            if (rate < 0 || !seen.add(rate)) return
            out.add(bitRateToUiQuality(rate) to name.ifBlank { "清晰度$rate" })
        }
        var info: Any? = data.optJSONObject("liveData")?.opt("bitRateInfo")
        if (info is String) {
            // info 是 var 且被下面的闭包捕获，Kotlin 拒绝智能转换；先取局部 val 再进闭包
            val raw = info
            info = runCatching { JSONObject("{\"a\":${raw.trim()}}").optJSONArray("a") }.getOrNull()
        }
        val arr = info as? JSONArray ?: data.optJSONObject("stream")?.optJSONObject("flv")?.optJSONArray("rateArray")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(o.optInt("iBitRate", -1), o.optString("sDisplayName").trim())
            }
        }
        if (out.isEmpty()) add(0, "原画")
        return out
    }

    // ---------------------------------------------------------------- antiCode 签名

    private class Params(
        val keys: List<String>,
        val values: HashMap<String, String>,
    )

    private fun parseQuery(query: String): Params {
        val keys = ArrayList<String>()
        val values = HashMap<String, String>()
        for (seg in query.split("&")) {
            if (seg.isBlank()) continue
            val idx = seg.indexOf('=')
            val k = decodeComponent(if (idx >= 0) seg.substring(0, idx) else seg)
            val v = decodeComponent(if (idx >= 0) seg.substring(idx + 1) else "")
            if (k.isBlank()) continue
            if (k !in values) keys.add(k)
            values[k] = v
        }
        return Params(keys, values)
    }

    /**
     * antiCode 的值是 URL 编码过的：`fm` 的 Base64 填充位会写成 `%3D%3D`。
     * 不解码直接交给 Base64 会抛 "Incorrect padding"，签名静默失败、返回未签名
     * 的地址，CDN 就会回 403。`+` 在 query 里是 Base64 字符而不是空格，先转义掉。
     */
    private fun decodeComponent(value: String): String =
        runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    /**
     * `fm` 是含 `$0`~`$3` 占位符的 Base64 模板。服务端给的形式不固定（可能缺填充、
     * 可能用 URL-safe 字母表），这里先归一化补齐再解码，避免一次解码失败就退化成
     * 未签名地址。
     */
    private fun decodeFmTemplate(fm: String): String? {
        val normalized = fm.trim().replace('-', '+').replace('_', '/')
        if (normalized.isBlank()) return null
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }

    /** `u = rotl64(uid)`：低 32 位左旋 8 位，高 32 位不变（PureLive REG-HUYA-008）。 */
    internal fun rotateUid(uid: Long): Long {
        val low = uid and 0xFFFFFFFFL
        val rotatedLow = ((low shl 8) or (low ushr 24)) and 0xFFFFFFFFL
        return (uid - low) or rotatedLow
    }

    /**
     * 对 [antiCode] 签名（PureLive `signAntiCode` 的 Kotlin 版）。
     * 返回签名后的查询串；没有 `fm` 模板时原样返回。
     */
    internal fun signAntiCode(antiCode: String, streamName: String, uid: Long, nowMs: Long): String {
        val params = parseQuery(antiCode)
        val fm = params.values["fm"]?.takeIf { it.isNotBlank() } ?: return antiCode
        val ctype = params.values["ctype"]?.takeIf { it.isNotBlank() } ?: "huya_webh5"
        val t = params.values["t"]?.takeIf { it.isNotBlank() } ?: "100"
        val wsTime = (params.values["wsTime"] ?: "").lowercase()
        val wsSeconds = wsTime.toLongOrNull(16) ?: return antiCode
        if (nowMs / 1000 > wsSeconds + 300) {
            error("$SITE antiCode expired (wsTime=$wsTime)")
        }
        val template =
            decodeFmTemplate(fm)
                ?: error("$SITE antiCode: fm is not a base64 template (an unsigned url would 403)")
        if (!template.contains("\$0") || !template.contains("\$1") || !template.contains("\$2") || !template.contains("\$3")) {
            return antiCode
        }
        val seqId = uid + nowMs
        val hash = DouyuApi.md5Hex("$seqId|$ctype|$t")
        val rotated = rotateUid(uid)
        val secret =
            template
                .replace("\$0", rotated.toString())
                .replace("\$1", streamName)
                .replace("\$2", hash)
                .replace("\$3", wsTime)
        val wsSecret = DouyuApi.md5Hex(secret)
        val out = ArrayList<String>(params.keys.size + 8)
        for (k in params.keys) {
            if (k in setOf("wsSecret", "seqid", "u", "uid", "uuid", "fm")) continue
            out.add("$k=${params.values[k]}")
        }
        fun put(key: String, value: String) {
            out.add("$key=$value")
        }
        put("wsSecret", wsSecret)
        put("wsTime", wsTime)
        put("seqid", seqId.toString())
        put("ctype", ctype)
        put("ver", "1")
        put("fs", params.values["fs"]?.takeIf { it.isNotBlank() } ?: "bgct")
        put("t", t)
        put("u", rotated.toString())
        return out.joinToString("&")
    }

    private suspend fun mediaUrl(baseUrl: String, streamName: String, antiCode: String, bitRate: Int, isHls: Boolean): String {
        val signed = signAntiCode(antiCode, streamName, uid = ensureViewerUid(), nowMs = System.currentTimeMillis())
        var query = signed
        if (!Regex("(^|&)codec=").containsMatchIn(query)) query = "$query&codec=264"
        query = replaceQueryParameter(query, "ratio", if (bitRate > 0) bitRate.toString() else null)
        val base = baseUrl.trimEnd('/')
        val ext = if (isHls) "m3u8" else "flv"
        return "$base/$streamName.$ext?$query"
    }

    private fun replaceQueryParameter(query: String, key: String, value: String?): String {
        val out = ArrayList<String>()
        var replaced = false
        for (seg in query.split("&")) {
            if (seg.isBlank()) continue
            val k = if (seg.contains('=')) seg.substringBefore('=') else seg
            if (k != key) {
                out.add(seg)
                continue
            }
            if (!replaced && value != null) out.add("$key=$value")
            replaced = true
        }
        if (!replaced && value != null) out.add("$key=$value")
        return out.joinToString("&")
    }

    // ---------------------------------------------------------------- 工具

    internal fun uiQualityToBitRate(qualityId: Int): Int = if (qualityId == QUALITY_SOURCE) 0 else qualityId

    internal fun bitRateToUiQuality(bitRate: Int): Int = if (bitRate == 0) QUALITY_SOURCE else bitRate

    fun defaultQualityId(): Int = QUALITY_SOURCE

    fun md5Hex(text: String): String = DouyuApi.md5Hex(text)

    private fun titleOf(introduction: String, roomName: String): String {
        val a = introduction.trim()
        return if (a.isNotBlank()) a else roomName.trim()
    }

    internal fun normalizeImageUrl(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isBlank() || v.equals("null", ignoreCase = true)) return ""
        if (v.startsWith("//")) return "https:$v"
        return if (v.startsWith("http://") || v.startsWith("https://")) v else ""
    }

    private fun parseCount(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toDoubleOrNull()?.toLong() ?: 0
            else -> 0
        }
    }
}
