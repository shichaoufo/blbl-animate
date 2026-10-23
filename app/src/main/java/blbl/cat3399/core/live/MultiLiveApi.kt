package blbl.cat3399.core.live

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 多平台直播统一入口：UI 层（直播页 / 播放器）只依赖本对象，按 [LiveSite] 分发到
 * B 站原生接口（[blbl.cat3399.core.api.LiveApi]）或斗鱼 / 虎牙扩展接口。
 */
internal object MultiLiveApi {
    /** 一级分区列表（斗鱼 cate/list、虎牙 bussLive×4、B 站内置默认分区）。 */
    suspend fun parents(site: LiveSite): List<LiveAreaParent> =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.categories()
            LiveSite.HUYA -> HuyaApi.categories()
            LiveSite.BILIBILI -> emptyList() // B 站分区由 LiveAreas.defaultParents + LiveApi.liveAreas 提供
        }

    /** 推荐直播。 */
    suspend fun recommendRooms(
        site: LiveSite,
        page: Int,
    ): List<LiveRoomCard> =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.recommendRooms(page)
            LiveSite.HUYA -> HuyaApi.recommendRooms(page)
            LiveSite.BILIBILI -> BiliApi.liveRecommend(page = page)
        }

    /** 分区房间列表。 */
    suspend fun areaRooms(
        site: LiveSite,
        parentAreaId: Int,
        areaId: Int,
        page: Int,
    ): List<LiveRoomCard> =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.areaRooms(areaId, page)
            LiveSite.HUYA -> HuyaApi.areaRooms(areaId, page)
            LiveSite.BILIBILI -> BiliApi.liveAreaRooms(parentAreaId, areaId, page)
        }

    /** 房间信息（标题 / 主播 / 分区 / 直播状态）。 */
    suspend fun roomInfo(
        site: LiveSite,
        roomId: Long,
    ): BiliApi.LiveRoomInfo =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.roomInfo(roomId)
            LiveSite.HUYA -> HuyaApi.roomInfo(roomId)
            LiveSite.BILIBILI -> BiliApi.liveRoomInfo(roomId)
        }

    /**
     * 播放地址。[qualityId] 为应用内清晰度 id：B 站为 qn，斗鱼为 rate（原画 = [DouyuApi.QUALITY_SOURCE]），
     * 虎牙为 iBitRate（原画 = [HuyaApi.QUALITY_SOURCE]）。
     */
    suspend fun playUrl(
        site: LiveSite,
        roomId: Long,
        qualityId: Int,
        highBitrateEnabled: Boolean = false,
    ): BiliApi.LivePlayUrl {
        lastPlayUrlCall = LastPlayUrlCall(site, roomId, qualityId, highBitrateEnabled)
        val result = fetchPlayUrl(site, roomId, qualityId, highBitrateEnabled)
        schedulePrefetch(site)
        return result
    }

    private suspend fun fetchPlayUrl(
        site: LiveSite,
        roomId: Long,
        qualityId: Int,
        highBitrateEnabled: Boolean,
    ): BiliApi.LivePlayUrl =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.playUrl(roomId, qualityId)
            LiveSite.HUYA -> HuyaApi.playUrl(roomId, qualityId)
            LiveSite.BILIBILI ->
                BiliApi.livePlayUrl(
                    roomId,
                    qn = qualityId,
                    highBitrateEnabled = highBitrateEnabled,
                )
        }

    // ---------------------------------------------------------------- 直播续流

    /**
     * 最近一次 [playUrl] 的入参。
     *
     * 直播地址都带时限：虎牙 web FLV 的连接租约实测约 120 秒（签名 `wsTime + 300s` 之前
     * CDN 就会主动断开），断流时播放器只收到正常 EOF，不报错，表现成「看着看着停住」。
     * 播放器层拿不到房间信息，所以由这里记下最近一次请求，续流时按同样的参数重新签一次。
     */
    private data class LastPlayUrlCall(
        val site: LiveSite,
        val roomId: Long,
        val qualityId: Int,
        val highBitrateEnabled: Boolean,
    )

    @Volatile
    private var lastPlayUrlCall: LastPlayUrlCall? = null

    // ---------------------------------------------------------------- 地址预取

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefetchJob: Job? = null

    @Volatile
    private var prefetched: BiliApi.LivePlayUrl? = null

    @Volatile
    private var prefetchedAtMs = 0L

    /**
     * 开播后静默预签好下一条地址。
     *
     * 虎牙的连接租约约 120 秒，断流时如果现签地址，界面会多等一次接口往返（几百毫秒到
     * 一秒多，房间详情接口 + 签名）；这段时间主角是黑屏/停顿，很难看。所以在连接建立约
     * [PREFETCH_DELAY_MS] 后就把下一条签好放着，断流时直接取用，只留连接与解码的时间。
     *
     * 只对虎牙做：斗鱼（租约由 URL 的 expire 声明）和 B 站的地址不是按固定时长断连的，
     * 没必要多打一次接口。
     */
    private fun schedulePrefetch(site: LiveSite) {
        prefetchJob?.cancel()
        prefetchJob = null
        prefetched = null
        if (site != LiveSite.HUYA) return
        prefetchJob =
            prefetchScope.launch {
                delay(PREFETCH_DELAY_MS)
                val call = lastPlayUrlCall ?: return@launch
                if (call.site != LiveSite.HUYA) return@launch
                val fresh =
                    runCatching {
                        fetchPlayUrl(call.site, call.roomId, call.qualityId, call.highBitrateEnabled)
                    }.getOrNull() ?: return@launch
                prefetched = fresh
                prefetchedAtMs = System.currentTimeMillis()
            }
    }

    /**
     * 重新获取最近一次 [playUrl] 的结果；从未调用过 [playUrl] 时返回 null。
     *
     * 有可用的预取地址时直接返回它（签名窗口 5 分钟，预取 90 秒后仍然有效），否则现签。
     */
    suspend fun refreshLastPlayUrl(): BiliApi.LivePlayUrl? {
        val call = lastPlayUrlCall ?: return null
        val cached = prefetched
        prefetched = null
        if (cached != null && System.currentTimeMillis() - prefetchedAtMs < PREFETCH_TTL_MS) {
            // 这一轮用预取，下一轮也要零等待：按新连接重新排一次预取。
            prefetchJob?.cancel()
            prefetchJob = null
            schedulePrefetch(call.site)
            return cached
        }
        return playUrl(call.site, call.roomId, call.qualityId, call.highBitrateEnabled)
    }

    /**
     * 强制现签一次最近一次 [playUrl] 的地址（不吃预取缓存）。
     *
     * HLS 的 playlist 要周期性重取，而虎牙的签名地址实测同一份签名约 125 秒后开始回 403
     * （ci.34 真机两条线路各一次：131s / 132s，与请求频次无关；PC 端 1s 间隔轮询同样在 125s
     * 挂掉）。换源会把播放位置重置到直播边缘、画面跳一下，所以改由数据源层把 playlist 的
     * URI 就地换成「新签的同一条线路地址」，播放不中断。每次替换必须是真·新签名，不能复用
     * 预取里那份可能已经放了两分钟的。
     */
    suspend fun renewLastPlayUrl(): BiliApi.LivePlayUrl? {
        val call = lastPlayUrlCall ?: return null
        return runCatching {
            fetchPlayUrl(call.site, call.roomId, call.qualityId, call.highBitrateEnabled)
        }.getOrNull()
    }

    /** 未选择清晰度时的默认清晰度 id。 */
    fun defaultQualityId(site: LiveSite): Int =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.defaultQualityId()
            LiveSite.HUYA -> HuyaApi.defaultQualityId()
            LiveSite.BILIBILI -> 150
        }
}

/** 开播后多久预签下一条直播地址：虎牙连接租约实测约 120s，留 30s 余量。 */
private const val PREFETCH_DELAY_MS = 90_000L

/** 预取地址的可用窗口：签名有效期 5 分钟，取 3 分钟，避免用到接近过期的地址。 */
private const val PREFETCH_TTL_MS = 180_000L
