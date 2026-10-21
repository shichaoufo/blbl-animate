package blbl.cat3399.core.live

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 斗鱼直播接口（移植自 PureLive `live_core/src/sites/douyu`，MIT License）。
 *
 * 已核对的能力（2026-10 实测）：
 * - 分区列表：`m.douyu.com/api/cate/list`
 * - 推荐 / 分区房间：`japi/weblist/apinc/allpage/6/<page>`、`gapi/rkc/directory/mixList/2_<area>/<page>`
 * - 房间信息：`betard/<rid>`
 * - 播放地址：`wgapi/livenc/liveweb/websec/getEncryption` 取签名描述符，
 *   POST `lapi/live/getH5PlayV1/<rid>`（`auth = md5(md5^n(rand_str+key) + key + rid + tt)`）
 * - 弹幕：`wss://danmuproxy.douyu.com:8506`（见 [blbl.cat3399.feature.live.DouyuDanmakuClient]）
 *
 * 播放流为 FLV，ExoPlayer(media3) 自带 FlvExtractor 可直接播放。
 */
internal object DouyuApi {
    private const val SITE = "douyu"

    /** 桌面 Chrome UA：API 与媒体请求使用（与 PureLive 一致）。 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 Safari/537.36"

    /** Edge UA：betard 房间详情使用（与 PureLive 一致）。 */
    private const val DETAIL_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.43"

    private const val ORIGIN = "https://www.douyu.com"

    /** 清晰度 id 映射：斗鱼 rate 0（原画）映射为应用通用的「原画」id，保证清晰度弹窗排在最前。 */
    const val QUALITY_SOURCE = 10_000

    private const val DESCRIPTOR_TTL_MS = 5 * 60 * 1000L

    // ---------------------------------------------------------------- 设备 id

    @Volatile
    private var processDid: String? = null

    /** 进程级设备 id：32 位小写十六进制（每次启动随机生成，匿名访客即可）。 */
    fun deviceId(): String {
        processDid?.let { return it }
        synchronized(this) {
            processDid?.let { return it }
            val sb = StringBuilder(32)
            while (sb.length < 32) {
                sb.append("0123456789abcdef"[kotlin.random.Random.nextInt(16)])
            }
            return sb.toString().also { processDid = it }
        }
    }

    // ---------------------------------------------------------------- 签名描述符

    private class Descriptor(
        val did: String,
        val key: String,
        val randStr: String,
        val encData: String,
        val encTime: Int,
        val expireAtSec: Long,
        val isSpecial: Boolean,
        val fetchedAtMs: Long,
    )

    @Volatile
    private var descriptorCache: Descriptor? = null

    /** `auth = md5(rand_str 连加 key 哈希 enc_time 次 + key + rid + tt)`（is_special 时不加 rid/tt）。 */
    internal fun auth(roomId: Long, tt: Long, d: Descriptor): String {
        var secret = d.randStr
        val rounds = d.encTime.coerceIn(1, 16)
        for (i in 0 until rounds) {
            secret = md5Hex(secret + d.key)
        }
        val salt = if (d.isSpecial) "" else "$roomId$tt"
        return md5Hex(secret + d.key + salt)
    }

    private suspend fun descriptor(did: String, force: Boolean): Descriptor {
        if (!force) {
            descriptorCache?.let { c ->
                if (c.did == did &&
                    c.expireAtSec * 1000 - System.currentTimeMillis() > 30_000 &&
                    System.currentTimeMillis() - c.fetchedAtMs < DESCRIPTOR_TTL_MS
                ) {
                    return c
                }
            }
        }
        val json =
            BiliClient.getJson(
                "https://www.douyu.com/wgapi/livenc/liveweb/websec/getEncryption?did=$did",
                headers = apiHeaders(roomId = null, did = did),
                noCookies = true,
            )
        if (json.optInt("error", -1) != 0) {
            error("$SITE getEncryption failed: ${json.optInt("error")} ${json.optString("msg")}")
        }
        val data = json.optJSONObject("data") ?: error("$SITE getEncryption: no data")
        val d =
            Descriptor(
                did = did,
                key = data.optString("key").trim(),
                randStr = data.optString("rand_str").trim(),
                encData = data.optString("enc_data").trim(),
                encTime = data.optInt("enc_time", 1),
                expireAtSec = data.optLong("expire_at", 0),
                isSpecial = data.optInt("is_special", 0) == 1,
                fetchedAtMs = System.currentTimeMillis(),
            )
        if (d.key.isBlank() || d.randStr.isBlank() || d.encData.isBlank() || d.expireAtSec <= 0) {
            error("$SITE getEncryption: incomplete descriptor")
        }
        descriptorCache = d
        return d
    }

    // ---------------------------------------------------------------- 请求头

    private fun apiHeaders(roomId: Long?, did: String): Map<String, String> =
        buildMap {
            put("Accept", "application/json, text/plain, */*")
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            put("Origin", ORIGIN)
            put("Referer", if (roomId != null && roomId > 0) "$ORIGIN/$roomId" else "$ORIGIN/")
            put("User-Agent", USER_AGENT)
            put("Cookie", "dy_did=$did; acf_did=$did")
        }

    // ---------------------------------------------------------------- 分区

    suspend fun categories(): List<LiveAreaParent> {
        val json =
            BiliClient.getJson(
                "https://m.douyu.com/api/cate/list",
                headers = mapOf("User-Agent" to USER_AGENT),
                noCookies = true,
            )
        if (json.optInt("code", -1) != 0) {
            error("$SITE cate/list failed: ${json.optInt("code")}")
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val groups = data.optJSONArray("cate1Info") ?: return emptyList()
        val areas = data.optJSONArray("cate2Info") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val childrenByParent = HashMap<Int, MutableList<LiveAreaParent.Child>>()
            for (i in 0 until areas.length()) {
                val a = areas.optJSONObject(i) ?: continue
                val parentId = a.optInt("cate1Id", 0)
                val id = a.optString("cate2Id").trim().toIntOrNull() ?: continue
                val name = decodeEntities(a.optString("cate2Name")).trim()
                if (parentId <= 0 || id <= 0 || name.isBlank()) continue
                val cover = firstImageUrl(a, "icon", "smallIcon", "pic")
                childrenByParent.getOrPut(parentId) { ArrayList() }
                    .add(LiveAreaParent.Child(id = id, parentId = parentId, name = name, hot = false, coverUrl = cover))
            }
            val out = ArrayList<LiveAreaParent>(groups.length())
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                val id = g.optInt("cate1Id", 0)
                val name = decodeEntities(g.optString("cate1Name")).trim()
                if (id <= 0 || name.isBlank()) continue
                out.add(LiveAreaParent(id = id, name = name, children = childrenByParent[id] ?: emptyList()))
            }
            out.sortBy { it.id }
            out
        }
    }

    // ---------------------------------------------------------------- 房间列表

    /** 推荐直播（`allpage/6`，每页 40 个）。 */
    suspend fun recommendRooms(page: Int): List<LiveRoomCard> {
        val p = page.coerceAtLeast(1)
        val json =
            BiliClient.getJson(
                "https://www.douyu.com/japi/weblist/apinc/allpage/6/$p",
                headers = apiHeaders(roomId = null, did = deviceId()),
                noCookies = true,
            )
        if (json.optInt("error", -1) != 0) {
            error("$SITE allpage failed: ${json.optInt("error")} ${json.optString("msg")}")
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        return withContext(Dispatchers.Default) { parseRoomList(data.optJSONArray("rl")) }
    }

    /** 分区房间（`mixList/2_<areaId>`，每页 120 个）。 */
    suspend fun areaRooms(areaId: Int, page: Int): List<LiveRoomCard> {
        val p = page.coerceAtLeast(1)
        val json =
            BiliClient.getJson(
                "https://www.douyu.com/gapi/rkc/directory/mixList/2_${areaId.coerceAtLeast(0)}/$p",
                headers = apiHeaders(roomId = null, did = deviceId()),
                noCookies = true,
            )
        if (json.optInt("error", -1) != 0) {
            error("$SITE mixList failed: ${json.optInt("error")} ${json.optString("msg")}")
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        return withContext(Dispatchers.Default) { parseRoomList(data.optJSONArray("rl")) }
    }

    private fun parseRoomList(arr: JSONArray?): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr?.length() ?: 0)
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optInt("type", 0) != 1) continue // 只有 type==1 是直播间
            val roomId = obj.optLong("rid", 0)
            if (roomId <= 0) continue
            val parentAreaId = obj.optInt("cid1", 0).takeIf { it > 0 }
            val areaId = obj.optInt("cid2", 0).takeIf { it > 0 }
            out.add(
                LiveRoomCard(
                    roomId = roomId,
                    uid = obj.optLong("uid", 0),
                    title = decodeEntities(obj.optString("rn")),
                    uname = decodeEntities(obj.optString("nn")),
                    coverUrl = normalizeImageUrl(obj.optString("rs16")),
                    faceUrl = listAvatar(obj.opt("av")),
                    online = parseCount(obj.opt("ol")),
                    isLive = true,
                    parentAreaId = parentAreaId,
                    parentAreaName = null,
                    areaId = areaId,
                    areaName = obj.optString("c2name").trim().takeIf { it.isNotBlank() },
                    keyframe = null,
                ),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 房间信息

    suspend fun roomInfo(roomId: Long): BiliApi.LiveRoomInfo {
        val json =
            BiliClient.getJson(
                "https://www.douyu.com/betard/$roomId",
                headers =
                    mapOf(
                        "Referer" to "$ORIGIN/$roomId",
                        "User-Agent" to DETAIL_USER_AGENT,
                    ),
                noCookies = true,
            )
        val root = decodeMaybeDoubleEncoded(json.toString())
        val room = root.optJSONObject("room") ?: error("$SITE betard: no room")
        val realRoomId = room.optLong("room_id", 0).takeIf { it > 0 } ?: roomId
        val replay = room.optInt("videoLoop", 0) == 1
        val live = room.optInt("show_status", 0) == 1 && !replay && !room.optString("room_name").startsWith("【回放】")
        return BiliApi.LiveRoomInfo(
            roomId = realRoomId,
            uid = 0,
            uname = decodeEntities(room.optString("owner_name")).trim().takeIf { it.isNotBlank() },
            faceUrl = normalizeImageUrl(room.optString("owner_avatar")).takeIf { it.isNotBlank() },
            title = decodeEntities(room.optString("room_name")),
            liveStatus = if (live) 1 else 0,
            areaName = room.optString("second_lvl_name").trim().takeIf { it.isNotBlank() },
            parentAreaName = null,
        )
    }

    // ---------------------------------------------------------------- 播放地址

    /**
     * 播放地址。[qualityId] 为应用内清晰度 id（[QUALITY_SOURCE]=原画，其余为斗鱼 rate）。
     * 返回结构与 B 站一致（[BiliApi.LivePlayUrl]），播放器无需区分平台。
     */
    suspend fun playUrl(roomId: Long, qualityId: Int): BiliApi.LivePlayUrl {
        val did = deviceId()
        val descriptor = descriptor(did = did, force = false)
        val apiRate = uiQualityToRate(qualityId)
        // 第一次请求拿清晰度列表与 CDN 列表（rate 传 -1 让服务端选择可用档位）。
        val meta = playRequest(roomId, descriptor, did, rate = apiRate, cdn = "")
        val qualities = parseQualities(meta)
        val cdns = parseCdns(meta)
        val lines = ArrayList<BiliApi.LivePlayLine>(cdns.size + 1)
        val seen = HashSet<String>(cdns.size * 2)
        fun addLine(url: String) {
            val u = url.trim()
            if (u.isNotBlank() && seen.add(u)) {
                lines.add(BiliApi.LivePlayLine(order = lines.size + 1, url = u))
            }
        }
        addLine(mediaUrl(meta))
        for (cdn in cdns) {
            if (lines.size >= 6) break
            val answer = runCatching { playRequest(roomId, descriptor, did, rate = apiRate, cdn = cdn) }.getOrNull() ?: continue
            addLine(mediaUrl(answer))
        }
        if (lines.isEmpty()) error("$SITE getH5PlayV1: no playable url")
        val acceptQn = qualities.map { it.first }.distinct()
        val qnDesc = qualities.toMap()
        val currentQn = meta.optInt("rate", -1).takeIf { it >= 0 }?.let { rateToUiQuality(it) } ?: qualityId
        return BiliApi.LivePlayUrl(currentQn = currentQn, acceptQn = acceptQn, qnDesc = qnDesc, lines = lines)
    }

    private suspend fun playRequest(
        roomId: Long,
        descriptor: Descriptor,
        did: String,
        rate: Int,
        cdn: String,
    ): JSONObject {
        val tt = System.currentTimeMillis() / 1000
        val form =
            buildMap {
                put("enc_data", descriptor.encData)
                put("tt", tt.toString())
                put("did", did)
                put("auth", auth(roomId, tt, descriptor))
                put("cdn", cdn)
                put("rate", rate.toString())
                put("hevc", "0")
                put("fa", "0")
                put("ive", "0")
                put("ver", "Douyu_new")
                put("iar", "0")
            }
        val json =
            BiliClient.postFormJson(
                "https://www.douyu.com/lapi/live/getH5PlayV1/$roomId",
                form = form,
                headers = apiHeaders(roomId = roomId, did = did),
                noCookies = true,
            )
        val code = json.optInt("error", -1)
        if (code != 0) {
            error("$SITE getH5PlayV1 error=$code ${json.optString("msg")}")
        }
        return json.optJSONObject("data") ?: error("$SITE getH5PlayV1: no data")
    }

    private fun parseQualities(data: JSONObject): List<Pair<Int, String>> {
        val arr = data.optJSONArray("multirates") ?: return emptyList()
        val out = ArrayList<Pair<Int, String>>(arr.length())
        val seen = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rate = o.optInt("rate", -1)
            if (rate < 0 || !seen.add(rate)) continue
            val name = o.optString("name").trim().ifBlank { "清晰度$rate" }
            out.add(rateToUiQuality(rate) to name)
        }
        return out
    }

    private fun parseCdns(data: JSONObject): List<String> {
        val codes = ArrayList<String>()
        val arr = data.optJSONArray("cdnsWithName") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val code = arr.optJSONObject(i)?.optString("cdn")?.trim().orEmpty()
            if (code.isNotBlank() && code !in codes) codes.add(code)
        }
        val current = data.optString("rtmp_cdn").trim()
        if (current.isNotBlank() && current !in codes) codes.add(0, current)
        // scdn 开头的 CDN 排到最后（与 PureLive 一致）。
        return codes.filterNot { it.startsWith("scdn") } + codes.filter { it.startsWith("scdn") }
    }

    private fun mediaUrl(data: JSONObject): String {
        val live = decodeEntities(data.optString("rtmp_live")).trim()
        if (live.startsWith("http://") || live.startsWith("https://")) return live
        if (live.isNotBlank()) {
            for (key in listOf("rtmp_url", "flv_url")) {
                val base = decodeEntities(data.optString(key)).trim().trimEnd('/')
                if (base.isNotBlank()) return "$base/${live.trimStart('/')}"
            }
        }
        for (key in listOf("player_1", "stream_url", "url")) {
            val v = decodeEntities(data.optString(key)).trim()
            if (v.startsWith("http")) return v
        }
        val flv = decodeEntities(data.optString("flv_url")).trim()
        if (flv.startsWith("http")) return flv
        return ""
    }

    // ---------------------------------------------------------------- 工具

    internal fun uiQualityToRate(qualityId: Int): Int =
        when {
            qualityId == QUALITY_SOURCE -> 0 // 原画
            qualityId < 0 -> -1 // 让服务端选择
            else -> qualityId
        }

    internal fun rateToUiQuality(rate: Int): Int = if (rate == 0) QUALITY_SOURCE else rate

    /** 默认清晰度：-1 = 让服务端选择可用档位（与 PureLive 初次请求一致）。 */
    fun defaultQualityId(): Int = -1

    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** betard 可能返回二次 JSON 编码的字符串。 */
    private fun decodeMaybeDoubleEncoded(text: String): JSONObject {
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        // text 本身是 JSON 字符串：去掉引号并反转义后解析
        val t = text.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            runCatching { JSONObject(inner) }.getOrNull()?.let { return it }
        }
        error("$SITE betard: not a json object")
    }

    private fun decodeEntities(text: String): String =
        text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    /** PureLive normalizeImageUrl：只接受 http(s) 或 `//` 开头的地址，其余返回空。 */
    internal fun normalizeImageUrl(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isBlank() || v.equals("null", ignoreCase = true)) return ""
        if (v.startsWith("//")) return "https:$v"
        return if (v.startsWith("http://") || v.startsWith("https://")) v else ""
    }

    private fun firstImageUrl(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val u = normalizeImageUrl(obj.optString(k))
            if (u.isNotBlank()) return u
        }
        return null
    }

    /** 斗鱼头像：完整 URL 直接用；路径拼 `apic.douyucdn.cn/upload/<path>_middle.jpg`。 */
    private fun listAvatar(value: Any?): String? {
        val direct = normalizeImageUrl(value?.toString())
        if (direct.isNotBlank()) return direct
        val path = value?.toString()?.trim().orEmpty()
        if (path.isBlank() || !Regex("^[\\w./-]+$").matches(path)) return null
        val p = path.removePrefix("/")
        return "https://apic.douyucdn.cn/upload/${p}_middle.jpg"
    }

    private fun parseCount(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> {
                val t = value.trim()
                val m = Regex("^([0-9]+(?:\\.[0-9]+)?)([万亿]?)$").find(t) ?: return t.toLongOrNull() ?: 0
                val num = m.groupValues[1].toDoubleOrNull() ?: return 0
                when (m.groupValues[2]) {
                    "万" -> (num * 10_000).toLong()
                    "亿" -> (num * 100_000_000).toLong()
                    else -> num.toLong()
                }
            }
            else -> 0
        }
    }
}
