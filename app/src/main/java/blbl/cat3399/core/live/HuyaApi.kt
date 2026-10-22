package blbl.cat3399.core.live

import android.util.Base64
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 虎牙直播接口（移植自 PureLive `live_core/src/tars.dart`...（占位）
 */
