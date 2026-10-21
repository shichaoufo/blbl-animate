package blbl.cat3399.core.live

/**
 * 直播平台标识。
 *
 * - [BILIBILI]：应用原生支持的哔哩哔哩直播（B 站接口见 [blbl.cat3399.core.api.BiliApi] / `LiveApi`）。
 * - [DOUYU] / [HUYA]：多平台直播扩展，接口实现参考 PureLive（https://github.com/wzgrx/PureLive ）
 *   的斗鱼 / 虎牙站点适配，移植到本项目的 Kotlin 实现见 [DouyuApi] / [HuyaApi]。
 */
enum class LiveSite(
    val key: String,
    val displayName: String,
) {
    BILIBILI("bilibili", "哔哩哔哩"),
    DOUYU("douyu", "斗鱼"),
    HUYA("huya", "虎牙"),
    ;

    /** 是否为本应用原生（B 站登录态、关注列表、SuperChat 等仅 B 站可用）。 */
    val isNative: Boolean get() = this == BILIBILI

    companion object {
        val DEFAULT: LiveSite = BILIBILI

        fun fromKey(key: String?): LiveSite {
            val k = key?.trim().orEmpty()
            return entries.firstOrNull { it.key == k } ?: DEFAULT
        }
    }
}
