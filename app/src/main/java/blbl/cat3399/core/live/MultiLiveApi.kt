package blbl.cat3399.core.live

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard

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
        return when (site) {
            LiveSite.DOUYU -> DouyuApi.playUrl(roomId, qualityId)
            LiveSite.HUYA -> HuyaApi.playUrl(roomId, qualityId)
            LiveSite.BILIBILI ->
                BiliApi.livePlayUrl(
                    roomId,
                    qn = qualityId,
                    highBitrateEnabled = highBitrateEnabled,
                )
        }
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

    /** 重新获取最近一次 [playUrl] 的结果；从未调用过 [playUrl] 时返回 null。 */
    suspend fun refreshLastPlayUrl(): BiliApi.LivePlayUrl? {
        val call = lastPlayUrlCall ?: return null
        return playUrl(call.site, call.roomId, call.qualityId, call.highBitrateEnabled)
    }

    /** 未选择清晰度时的默认清晰度 id。 */
    fun defaultQualityId(site: LiveSite): Int =
        when (site) {
            LiveSite.DOUYU -> DouyuApi.defaultQualityId()
            LiveSite.HUYA -> HuyaApi.defaultQualityId()
            LiveSite.BILIBILI -> 150
        }
}
