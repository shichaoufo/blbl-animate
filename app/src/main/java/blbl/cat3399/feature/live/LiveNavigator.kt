package blbl.cat3399.feature.live

import blbl.cat3399.core.live.LiveSite

interface LiveNavigator {
    fun openAreaDetail(
        site: LiveSite,
        parentAreaId: Int,
        parentTitle: String,
        areaId: Int,
        areaTitle: String,
    ): Boolean
}
