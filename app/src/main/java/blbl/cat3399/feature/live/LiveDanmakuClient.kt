package blbl.cat3399.feature.live

/**
 * 直播弹幕客户端统一接口：B 站（[LiveMessageClient]）、斗鱼（[DouyuDanmakuClient]）、
 * 虎牙（[HuyaDanmakuClient]）各自实现，播放器只依赖本接口。
 */
interface LiveDanmakuClient {
    /** 建立连接（内部会先按平台协议获取弹幕服务器信息；B 站还需要 token）。 */
    suspend fun connect()

    /** 关闭连接并释放资源。 */
    fun close()
}
