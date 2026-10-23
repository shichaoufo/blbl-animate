@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.ParsingLoadable
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.live.MultiLiveApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.CdnFailoverDataSourceFactory
import blbl.cat3399.feature.player.CdnFailoverState
import blbl.cat3399.feature.player.DebugStreamKind
import blbl.cat3399.feature.player.Playable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToLong

internal data class LiveHlsDebugInfo(
    val mediaSequence: Long?,
    val targetDurationSec: Double?,
    val mapUri: String?,
    val segmentCount: Int,
    val recentSegmentCount: Int,
    val recentBitrateBps: Long?,
    val lastSegmentUri: String?,
    val lastSegmentDurationSec: Double?,
    val lastSegmentBytes: Long?,
    val lastSegmentSequence: Long?,
    val lastAuxRaw: String?,
)

internal class ExoPlayerEngine(
    context: Context,
    private val okHttpClient: OkHttpClient = BiliClient.cdnOkHttp,
    private val onTransferHost: ((kind: DebugStreamKind, host: String) -> Unit)? = null,
    private val onBytesTransferred: ((kind: DebugStreamKind, bytesTransferred: Long) -> Unit)? = null,
    private val onLiveHlsDebugInfo: ((LiveHlsDebugInfo) -> Unit)? = null,
    audioBalanceLevel: AudioBalanceLevel = AudioBalanceLevel.Off,
) : BlblPlayerEngine {
    private val appContext: Context = context.applicationContext
    private val seamlessManifestFile: File = File(appContext.cacheDir, "blbl_seamless_dash_${System.identityHashCode(this)}.mpd")

    private val volumeBalanceProcessor = VolumeBalanceAudioProcessor(level = audioBalanceLevel)
    private val loadControl: DefaultLoadControl =
        DefaultLoadControl.Builder()
            // Keep roughly one forward buffer window behind the playhead so in-buffer seek
            // does not immediately discard media that was already fetched.
            .setBackBuffer(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, true)
            .build()
    private val liveHlsPlaylistParserFactory: HlsPlaylistParserFactory =
        ExtXStartStrippingHlsPlaylistParserFactory(onPlaylistParsed = onLiveHlsDebugInfo)
    private val seamlessQualityController = SeamlessQualitySelectionController()
    private val trackSelector = DefaultTrackSelector(context, SeamlessQualityTrackSelectionFactory(seamlessQualityController))

    val exoPlayer: ExoPlayer =
        ExoPlayer.Builder(context, BlblRenderersFactory(context.applicationContext, volumeBalanceProcessor))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()

    private val listeners: MutableSet<BlblPlayerEngine.Listener> = CopyOnWriteArraySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前播放源：直播续流时要知道原始地址（用来匹配同一条 CDN 线路）。 */
    @Volatile
    private var currentSource: PlaybackSource? = null

    /**
     * 直播续流状态。
     *
     * 直播地址都带时限：虎牙 web FLV 的连接租约实测约 120 秒（签名 `wsTime + 300s` 到期之前
     * CDN 就会主动断开），断流时 ExoPlayer 只收到正常 EOF 并进入 STATE_ENDED，**不报错**，
     * 用户看到的就是「看着看着停住」。这里在 ENDED 时重新签一次地址再 prepare，续上直播。
     */
    private var liveReconnectCount = 0
    private var liveReconnectWindowStartMs = 0L
    private var liveReconnectInFlight = false

    /** 断流前主动换签的任务（短租约地址专用）。 */
    private var liveSwitchJob: Job? = null

    /**
     * HLS 就地续签状态。
     *
     * 虎牙 HLS 的 playlist 是每 4 秒重取一次的长跑请求，而同一份签名实测约 125 秒后开始回
     * 403（ci.34 真机两条线路都是 131~132 秒，PC 端 1s 轮询 125 秒）。旧做法靠 autoFailover
     * 换线下台阶，代价是位置重置到直播边缘、画面明显跳一下，还弹「线路 x 失败」。
     * 这里改为：后台每 [HLS_RENEW_INTERVAL_MS] 现签一条同线路地址，数据源层把 playlist 的
     * URI 换掉——分片地址由新 playlist 自带新签名，播放不中断、不跳。
     */
    private var hlsRenewJob: Job? = null

    @Volatile
    private var hlsRenewUrl: String = ""

    @Volatile
    private var hlsRenewedAtMs: Long = 0L

    @Volatile
    private var hlsPlaylistPath: String = ""

    /** 已经替换进 playlist 请求的那份地址，用来把「换签」日志压到每 [HLS_RENEW_INTERVAL_MS] 一条。 */
    @Volatile
    private var hlsRenewApplied: Uri? = null

    private var seamlessQualitySource: Boolean = false
    private var seamlessAvailableQns: Set<Int> = emptySet()
    private var seamlessAvailableTracks: Set<Pair<Int, Int>> = emptySet()
    private var pendingSeamlessQn: Int? = null
    private var currentSeamlessCodecid: Int? = null
    private var appliedSeamlessQn: Int? = null
    private var appliedSeamlessCodecid: Int? = null
    private var qualitySwitchTargetQn: Int? = null
    private var qualitySwitchStartedAtMs: Long = 0L
    private var qualitySwitchStartedPositionMs: Long = 0L
    private var qualitySwitchFormatChangedAtMs: Long = 0L

    override val kind: PlayerEngineKind = PlayerEngineKind.ExoPlayer
    override val capabilities: EngineCapabilities = EngineCapabilities(subtitlesSupported = true)

    override val playbackState: Int
        get() = exoPlayer.playbackState

    override val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    override var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    override val duration: Long
        get() = exoPlayer.duration

    override val currentPosition: Long
        get() = exoPlayer.currentPosition

    override val bufferedPosition: Long
        get() = exoPlayer.bufferedPosition

    override fun setVideoSurface(surface: Surface?) {
        // No-op: ExoPlayer renders through PlayerView.
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override val playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
    }

    override val isSeamlessQualitySource: Boolean
        get() = seamlessQualitySource

    override fun selectVideoQuality(qn: Int): Boolean {
        if (!seamlessQualitySource) {
            AppLog.w("QualitySwitch", "engine reject qn=$qn reason=not_seamless_source available=$seamlessAvailableQns")
            return false
        }
        if (qn !in seamlessAvailableQns) {
            AppLog.w("QualitySwitch", "engine reject qn=$qn reason=qn_not_available available=$seamlessAvailableQns")
            return false
        }
        val activeCodecid = currentSeamlessCodecid
        if (
            activeCodecid != null &&
            (qn to activeCodecid) in seamlessAvailableTracks &&
            isVideoTrackKnownUnsupported(qn = qn, codecid = activeCodecid)
        ) {
            AppLog.w("QualitySwitch", "engine reject qn=$qn codecid=$activeCodecid reason=decoder_unsupported")
            return false
        }
        qualitySwitchTargetQn = qn
        qualitySwitchStartedAtMs = SystemClock.elapsedRealtime()
        qualitySwitchStartedPositionMs = exoPlayer.currentPosition
        qualitySwitchFormatChangedAtMs = 0L
        pendingSeamlessQn = qn
        if (activeCodecid != null && (qn to activeCodecid) in seamlessAvailableTracks) {
            seamlessQualityController.setTarget(qn, activeCodecid)
            AppLog.i(
                "QualitySwitch",
                "controller request qn=$qn codecid=$activeCodecid pos=${exoPlayer.currentPosition} buffered=${exoPlayer.bufferedPosition}",
            )
            return true
        }
        AppLog.i(
            "QualitySwitch",
            "engine accept qn=$qn currentCodec=$currentSeamlessCodecid pos=${exoPlayer.currentPosition} " +
                "buffered=${exoPlayer.bufferedPosition} state=${exoPlayer.playbackState} available=$seamlessAvailableQns",
        )
        applyPendingSeamlessVideoOverride(exoPlayer.currentTracks)
        return true
    }

    private fun isVideoTrackKnownUnsupported(qn: Int, codecid: Int): Boolean {
        var matchingTrackFound = false
        exoPlayer.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (trackIndex in 0 until group.length) {
                val parsed = DashMpdGenerator.parseVideoRepresentationId(group.getTrackFormat(trackIndex).id) ?: continue
                if (parsed.first != qn || parsed.second != codecid) continue
                matchingTrackFound = true
                if (group.isTrackSupported(trackIndex)) return false
            }
        }
        return matchingTrackFound
    }

    override var repeatMode: Int
        get() = exoPlayer.repeatMode
        set(value) {
            exoPlayer.repeatMode = value
        }

    init {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    listeners.forEach { it.onPlayerError(error) }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listeners.forEach { it.onIsPlayingChanged(isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) maybeReconnectLive()
                    listeners.forEach { it.onPlaybackStateChanged(playbackState) }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    AppLog.i(
                        "QualitySwitch",
                        "position discontinuity reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs} " +
                            "delta=${newPosition.positionMs - oldPosition.positionMs} targetQn=${qualitySwitchTargetQn ?: -1}",
                    )
                    listeners.forEach { it.onPositionDiscontinuity(newPosition.positionMs) }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    listeners.forEach { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (qualitySwitchTargetQn != null) {
                        AppLog.i("QualitySwitch", "tracks changed targetQn=$qualitySwitchTargetQn ${describeVideoTracks(tracks)}")
                    }
                    applyPendingSeamlessVideoOverride(tracks)
                }
            },
        )
        exoPlayer.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    qualitySwitchTargetQn?.let { targetQn ->
                        AppLog.i(
                            "QualitySwitch",
                            "first frame after request targetQn=$targetQn elapsedMs=${SystemClock.elapsedRealtime() - qualitySwitchStartedAtMs} " +
                                "formatToFrameMs=${qualitySwitchFormatChangedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it } ?: -1L} " +
                                "requestPos=$qualitySwitchStartedPositionMs currentPos=${exoPlayer.currentPosition}",
                        )
                        qualitySwitchTargetQn = null
                    }
                    listeners.forEach { it.onRenderedFirstFrame() }
                }

                override fun onVideoInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    val (qn, codecid) = DashMpdGenerator.parseVideoRepresentationId(format.id) ?: return
                    val targetQn = qualitySwitchTargetQn
                    AppLog.i(
                        "QualitySwitch",
                        "video format changed qn=$qn codecid=$codecid targetQn=${targetQn ?: -1} id=${format.id} " +
                            "size=${format.width}x${format.height} bitrate=${format.bitrate} " +
                            "reuseResult=${decoderReuseEvaluation?.result ?: -1} discardReasons=${decoderReuseEvaluation?.discardReasons ?: -1} " +
                            "elapsedMs=${if (qualitySwitchStartedAtMs > 0L) SystemClock.elapsedRealtime() - qualitySwitchStartedAtMs else -1L} " +
                            "requestPos=$qualitySwitchStartedPositionMs currentPos=${exoPlayer.currentPosition}",
                    )
                    if (targetQn == qn) qualitySwitchFormatChangedAtMs = SystemClock.elapsedRealtime()
                    currentSeamlessCodecid = codecid
                    listeners.forEach { it.onVideoTrackChanged(qn = qn, codecid = codecid) }
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    AppLog.i(
                        "QualitySwitch",
                        "audio format changed id=${format.id} mime=${format.sampleMimeType} codecs=${format.codecs} " +
                            "bitrate=${format.bitrate} channels=${format.channelCount} sampleRate=${format.sampleRate}",
                    )
                }
            },
        )
    }

    override fun setSource(source: PlaybackSource) {
        currentSource = source
        AppLog.i(
            "QualitySwitch",
            "setSource type=${source.javaClass.simpleName} seamlessRequested=${(source as? PlaybackSource.Vod)?.seamlessQualitySwitchEnabled == true} " +
                "playable=${(source as? PlaybackSource.Vod)?.playable?.javaClass?.simpleName ?: "-"}",
        )
        seamlessQualitySource = false
        seamlessAvailableQns = emptySet()
        seamlessAvailableTracks = emptySet()
        seamlessQualityController.setTarget(0, 0)
        pendingSeamlessQn = null
        currentSeamlessCodecid = null
        appliedSeamlessQn = null
        appliedSeamlessCodecid = null
        qualitySwitchTargetQn = null
        qualitySwitchStartedAtMs = 0L
        qualitySwitchStartedPositionMs = 0L
        qualitySwitchFormatChangedAtMs = 0L
        cancelHlsRenew()
        when (source) {
            is PlaybackSource.Vod -> {
                val dash = source.playable as? Playable.Dash
                AppLog.i(
                    "QualitySwitch",
                    "source eligibility enabled=${source.seamlessQualitySwitchEnabled} dash=${dash != null} " +
                        "representations=${dash?.videoRepresentations?.size ?: 0} " +
                        "segmentRepresentations=${dash?.videoRepresentations?.count { it.videoTrackInfo.segmentBase != null } ?: 0} " +
                        "qns=${dash?.videoRepresentations?.map { it.qn }?.distinct().orEmpty()} " +
                        "audioSegmentBase=${dash?.audioTrackInfo?.segmentBase != null}",
                )
                val seamlessApplied =
                    if (source.seamlessQualitySwitchEnabled && dash != null) {
                        runCatching {
                            setSeamlessVodDash(
                                dash = dash,
                                subtitle = source.subtitle,
                                durationMs = source.durationMs,
                                initialPositionMs = source.initialPositionMs,
                            )
                        }.onFailure { throwable ->
                            AppLog.w("QualitySwitch", "build seamless DASH source failed; fallback to legacy source", throwable)
                        }.isSuccess
                    } else {
                        false
                    }
                if (!seamlessApplied) {
                    AppLog.w("QualitySwitch", "using legacy source seamlessRequested=${source.seamlessQualitySwitchEnabled} dash=${dash != null}")
                    setVodPlayable(
                        playable = source.playable,
                        subtitle = source.subtitle,
                        initialPositionMs = source.initialPositionMs,
                    )
                }
            }

            is PlaybackSource.Live -> {
                val url = source.url.trim()
                val uri = Uri.parse(url)
                val factory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = listOf(url))

                val isM3u8 = url.substringBefore('?').trim().lowercase(Locale.US).endsWith(".m3u8")
                if (isM3u8) {
                    val playlistPath = url.substringBefore('?')
                    val hlsFactory =
                        if (isHuyaHls(url)) {
                            startHlsRenew(playlistPath)
                            renewingFactory(factory, playlistPath)
                        } else {
                            factory
                        }
                    val hlsSource =
                        HlsMediaSource.Factory(hlsFactory)
                            .setPlaylistParserFactory(liveHlsPlaylistParserFactory)
                            .createMediaSource(MediaItem.fromUri(uri))
                    exoPlayer.setMediaSource(hlsSource)
                } else {
                    val mediaSourceFactory = DefaultMediaSourceFactory(factory)
                    val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(uri))
                    exoPlayer.setMediaSource(mediaSource)
                }
                // 短租约地址（虎牙 web 签名的 FLV 长连接）在断流之前主动换签；HLS 分片短连接
                // 与 native 凭据（huya_pc_exe）都不会被掐断，不需要切换。
                if (needsLeaseSwitch(url)) scheduleLeaseSwitch(url) else cancelLeaseSwitch()
            }
        }
    }

    override fun prepare() {
        exoPlayer.prepare()
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun stop() {
        exoPlayer.stop()
    }

    override fun release() {
        scope.cancel()
        exoPlayer.release()
    }

    /**
     * 直播续流：只在直播源正常结束（EOF）时触发。
     *
     * 重新取一次播放地址（同站点 / 同房间 / 同清晰度，优先保留同一条 CDN 线路）再 prepare
     * 一次，从最新直播位置继续，避免「每 2 分钟停一次」。5 分钟内最多重连 8 次，地址长期
     * 不可用时不至于原地打转。
     */
    private fun maybeReconnectLive() {
        val source = currentSource as? PlaybackSource.Live ?: return
        if (liveReconnectInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (liveReconnectWindowStartMs <= 0L || now - liveReconnectWindowStartMs > LIVE_RECONNECT_WINDOW_MS) {
            liveReconnectWindowStartMs = now
            liveReconnectCount = 0
        }
        if (liveReconnectCount >= LIVE_RECONNECT_MAX) {
            AppLog.w("LiveReconnect", "give up after $liveReconnectCount reconnects")
            return
        }
        liveReconnectCount++
        liveReconnectInFlight = true
        val previousUrl = source.url
        cancelLeaseSwitch()
        AppLog.i("LiveReconnect", "stream ended, refreshing url (${liveReconnectCount})")
        scope.launch {
            try {
                val fresh = runCatching { MultiLiveApi.refreshLastPlayUrl() }.getOrNull()
                val previousPath = previousUrl.substringBefore('?')
                val nextUrl =
                    fresh
                        ?.lines
                        ?.firstOrNull { it.url.substringBefore('?') == previousPath }
                        ?.url
                        ?: fresh?.lines?.firstOrNull()?.url
                        ?: previousUrl
                if (nextUrl.isBlank()) return@launch
                setSource(PlaybackSource.Live(url = nextUrl))
                exoPlayer.prepare()
                exoPlayer.play()
            } finally {
                liveReconnectInFlight = false
            }
        }
    }

    /**
     * 短租约直播地址的「提前换签」。
     *
     * 虎牙 web 签名的 FLV 长连接在建立约 120 秒后被 CDN 主动断开。等 ENDED 再重连会有约 1 秒
     * 空档（播放器要把缓冲耗尽才判定结束），观感是「卡一下再跳一下」。这里在断流之前
     * （[LIVE_LEASE_SWITCH_MS]）就换到预取好的新地址：两边都是直播的当前画面，内容连续，
     * 只剩解码器重启的零点几秒；配合 PlayerView 的 keepContentOnPlayerReset 只是「停一帧」。
     */
    private fun scheduleLeaseSwitch(currentUrl: String) {
        cancelLeaseSwitch()
        liveSwitchJob =
            scope.launch {
                delay(LIVE_LEASE_SWITCH_MS)
                if (currentSource !is PlaybackSource.Live) return@launch
                // 暂停中不要抢播：切源会自动开播，等于替用户点了播放。
                if (!exoPlayer.playWhenReady) return@launch
                val fresh = runCatching { MultiLiveApi.refreshLastPlayUrl() }.getOrNull() ?: return@launch
                val next = pickLiveUrl(fresh, currentUrl) ?: return@launch
                liveSwitchJob = null // 避免 applyLiveUrl -> setSource 时自取消
                AppLog.i("LiveReconnect", "proactive switch before lease expiry")
                applyLiveUrl(next)
            }
    }

    private fun cancelLeaseSwitch() {
        liveSwitchJob?.cancel()
        liveSwitchJob = null
    }

    private fun applyLiveUrl(url: String) {
        setSource(PlaybackSource.Live(url = url))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    /** 优先同一条 CDN 线路（URL path 相同），否则取第一条。 */
    private fun pickLiveUrl(
        fresh: BiliApi.LivePlayUrl?,
        previousUrl: String,
    ): String? {
        val previousPath = previousUrl.substringBefore('?')
        return fresh
            ?.lines
            ?.firstOrNull { it.url.substringBefore('?') == previousPath }
            ?.url
            ?: fresh?.lines?.firstOrNull()?.url
    }

    /**
     * 是否为「短传输租约」地址：虎牙域名下的 **FLV** 长连接，且不是 native 凭据
     * （`ctype=huya_pc_exe`）。判定与 PureLive 的 `hasShortTransportLease` 一致。
     *
     * 刻意不包含 m3u8：HLS 是「取一次 playlist、再按分片各开一条短连接」，单条连接远远
     * 到不了 120 秒，租约根本触发不到（实测同一份签名地址连续 233 秒仍正常出流）。
     * 给 HLS 也排换签只会平白制造一次跳画面。斗鱼（租约由 URL 的 `expire` 声明）与 B 站
     * 同样不属于此类。
     */
    private fun needsLeaseSwitch(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (!host.contains("huya")) return false
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        if (!path.endsWith(".flv")) return false
        return uri.getQueryParameter("ctype") != "huya_pc_exe"
    }

    private fun isHuyaHls(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (!host.contains("huya")) return false
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        return path.endsWith(".m3u8")
    }

    /**
     * 只在「重取 playlist（同一个 .m3u8）」时把 URI 换成刚签好的地址。
     *
     * 分片请求（.ts）一律放行原地址——新 playlist 里的分片本来就带着新签名，
     * 替换它们反而会把不同批次的签名混在一起。
     */
    private fun renewingFactory(factory: DataSource.Factory, playlistPath: String): DataSource.Factory =
        ResolvingDataSource.Factory(factory) { dataSpec ->
            val renewed = renewedPlaylistUri(dataSpec.uri)
            if (renewed == null) {
                dataSpec
            } else {
                if (renewed != hlsRenewApplied) {
                    hlsRenewApplied = renewed
                    AppLog.i("LivePlayer", "hls playlist re-signed seqid=${renewed.toString().substringAfter("seqid=", "").substringBefore('&').take(17)}")
                }
                dataSpec.withUri(renewed)
            }
        }

    private fun renewedPlaylistUri(uri: Uri): Uri? {
        val path = hlsPlaylistPath
        if (path.isBlank()) return null
        if (uri.toString().substringBefore('?') != path) return null
        val renewed = hlsRenewUrl
        if (renewed.isBlank()) return null
        if (SystemClock.elapsedRealtime() - hlsRenewedAtMs > HLS_RENEW_MAX_AGE_MS) return null
        return runCatching { Uri.parse(renewed) }.getOrNull()
    }

    /**
     * 每 [HLS_RENEW_INTERVAL_MS] 现签一次同一条线路的地址放进 [hlsRenewUrl]。
     *
     * 只认「同一 CDN 同一条流」的地址（URL path 完全一致）：换了 CDN 主机的话，
     * playlist 里的相对分片地址会被解析到另一台机器上，取不到分片。
     */
    private fun startHlsRenew(playlistPath: String) {
        cancelHlsRenew()
        hlsPlaylistPath = playlistPath
        hlsRenewJob =
            scope.launch {
                delay(HLS_RENEW_INTERVAL_MS)
                var misses = 0
                while (true) {
                    if (currentSource !is PlaybackSource.Live) return@launch
                    val fresh = MultiLiveApi.renewLastPlayUrl()
                    val same =
                        fresh
                            ?.lines
                            ?.firstOrNull { it.url.substringBefore('?') == playlistPath }
                            ?.url
                    if (same.isNullOrBlank()) {
                        misses += 1
                        AppLog.w("LivePlayer", "hls renew miss #$misses path=${playlistPath.takeLast(26)}")
                        if (misses >= HLS_RENEW_MAX_MISSES) return@launch
                    } else {
                        misses = 0
                        hlsRenewUrl = same
                        hlsRenewedAtMs = SystemClock.elapsedRealtime()
                        AppLog.i("LivePlayer", "hls renew ok seqid=${same.substringAfter("seqid=", "").substringBefore('&').take(17)}")
                    }
                    delay(HLS_RENEW_INTERVAL_MS)
                }
            }
    }

    private fun cancelHlsRenew() {
        hlsRenewJob?.cancel()
        hlsRenewJob = null
        hlsRenewUrl = ""
        hlsRenewedAtMs = 0L
        hlsPlaylistPath = ""
        hlsRenewApplied = null
    }

    override fun addListener(listener: BlblPlayerEngine.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: BlblPlayerEngine.Listener) {
        listeners.remove(listener)
    }

    fun setAudioBalanceLevel(level: AudioBalanceLevel) {
        volumeBalanceProcessor.setLevel(level)
    }

    private fun createCdnFactory(
        kind: DebugStreamKind,
        urlCandidates: List<String>? = null,
        mediaRequestProfile: VideoMediaRequestProfile = VideoMediaRequestProfile.WEB,
    ): DataSource.Factory {
        val listener =
            object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    val host = dataSpec.uri.host?.trim().orEmpty()
                    if (host.isBlank()) return
                    onTransferHost?.invoke(kind, host.lowercase(Locale.US))
                }

                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (!isNetwork || bytesTransferred <= 0) return
                    onBytesTransferred?.invoke(kind, bytesTransferred.toLong())
                }

                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            }

        val client =
            when (mediaRequestProfile) {
                VideoMediaRequestProfile.WEB -> okHttpClient
                VideoMediaRequestProfile.APP -> BiliClient.appCdnOkHttp
            }
        val upstream = OkHttpDataSource.Factory(client).setTransferListener(listener)
        val uris =
            urlCandidates
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { Uri.parse(it) }
        if (uris.size <= 1) return upstream
        return CdnFailoverDataSourceFactory(upstreamFactory = upstream, state = CdnFailoverState(kind = kind, candidates = uris))
    }

    private fun buildMerged(
        videoFactory: DataSource.Factory,
        audioFactory: DataSource.Factory,
        videoUrl: String,
        audioUrl: String,
        subtitle: MediaItem.SubtitleConfiguration?,
    ): MediaSource {
        val subs = listOfNotNull(subtitle)
        val videoSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, videoFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subs).build(),
                )
        val audioSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, audioFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(audioUrl)).build(),
                )
        return MergingMediaSource(true, true, videoSource, audioSource)
    }

    private fun buildProgressive(factory: DataSource.Factory, url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaSource {
        val subs = listOfNotNull(subtitle)
        val item =
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setSubtitleConfigurations(subs)
                .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, factory)).createMediaSource(item)
    }

    private fun setSeamlessVodDash(
        dash: Playable.Dash,
        subtitle: MediaItem.SubtitleConfiguration?,
        durationMs: Long?,
        initialPositionMs: Long?,
    ) {
        val videos = dash.videoRepresentations.filter { it.videoTrackInfo.segmentBase != null }
        require(videos.size > 1) { "Seamless DASH needs at least two video representations" }
        require(videos.any { it.qn == dash.qn && it.codecid == dash.codecid }) { "Selected DASH video representation is missing" }
        require(dash.audioTrackInfo.segmentBase != null) { "Selected DASH audio representation is missing SegmentBase" }

        val routeFactories = LinkedHashMap<String, DataSource.Factory>()
        videos.forEach { video ->
            routeFactories[video.videoUrl] =
                createCdnFactory(
                    kind = DebugStreamKind.VIDEO,
                    urlCandidates = video.videoUrlCandidates,
                    mediaRequestProfile = video.videoMediaRequestProfile,
                )
        }
        routeFactories[dash.audioUrl] =
            createCdnFactory(
                kind = DebugStreamKind.AUDIO,
                urlCandidates = dash.audioUrlCandidates,
                mediaRequestProfile = dash.audioMediaRequestProfile,
            )

        val routedHttpFactory =
            RoutedHttpDataSourceFactory(
                routeFactories = routeFactories,
                fallbackFactory = createCdnFactory(DebugStreamKind.MAIN),
            )
        val dataSourceFactory = DefaultDataSource.Factory(appContext, routedHttpFactory)
        val mpd = DashMpdGenerator.buildAdaptiveOnDemandMpd(dash = dash, durationMs = durationMs)
        seamlessManifestFile.writeText(mpd, Charsets.UTF_8)
        val item =
            MediaItem.Builder()
                .setUri(Uri.fromFile(seamlessManifestFile))
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setSubtitleConfigurations(listOfNotNull(subtitle))
                .build()

        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
        setMediaSource(
            mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item),
            initialPositionMs = initialPositionMs,
        )
        seamlessQualitySource = true
        seamlessAvailableQns = videos.mapTo(LinkedHashSet()) { it.qn }
        seamlessAvailableTracks = videos.mapTo(LinkedHashSet()) { it.qn to it.codecid }
        pendingSeamlessQn = dash.qn
        currentSeamlessCodecid = dash.codecid
        seamlessQualityController.setTarget(dash.qn, dash.codecid)
        AppLog.i(
            "QualitySwitch",
            "seamless DASH source ready qns=${seamlessAvailableQns.toList()} representations=${videos.size} " +
                "allRepresentations=${dash.videoRepresentations.size} initialQn=${dash.qn} codecid=${dash.codecid} " +
                "audioSegmentBase=true",
        )
    }

    private fun applyPendingSeamlessVideoOverride(tracks: Tracks) {
        val targetQn = pendingSeamlessQn ?: return
        if (!seamlessQualitySource) return
        val activeCodecid = currentSeamlessCodecid
        if (activeCodecid != null && (targetQn to activeCodecid) in seamlessAvailableTracks) {
            AppLog.i("QualitySwitch", "controller keeps sample stream qn=$targetQn codecid=$activeCodecid")
            return
        }

        data class Candidate(
            val group: Tracks.Group,
            val trackIndex: Int,
            val qn: Int,
            val codecid: Int,
        )

        val candidates = ArrayList<Candidate>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (trackIndex in 0 until group.length) {
                val (qn, codecid) = DashMpdGenerator.parseVideoRepresentationId(group.getTrackFormat(trackIndex).id) ?: continue
                if (qn == targetQn && group.isTrackSupported(trackIndex)) {
                    candidates += Candidate(group = group, trackIndex = trackIndex, qn = qn, codecid = codecid)
                }
            }
        }
        val candidate =
            candidates.firstOrNull { it.codecid == currentSeamlessCodecid }
                ?: candidates.firstOrNull()
                ?: run {
                    AppLog.w(
                        "QualitySwitch",
                        "override missing targetQn=$targetQn currentCodec=$currentSeamlessCodecid ${describeVideoTracks(tracks)}",
                    )
                    return
                }
        if (appliedSeamlessQn == candidate.qn && appliedSeamlessCodecid == candidate.codecid) {
            AppLog.i("QualitySwitch", "override unchanged qn=${candidate.qn} codecid=${candidate.codecid}")
            return
        }

        val override = TrackSelectionOverride(candidate.group.mediaTrackGroup, listOf(candidate.trackIndex))
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(override)
                .build()
        appliedSeamlessQn = candidate.qn
        appliedSeamlessCodecid = candidate.codecid
        AppLog.i(
            "QualitySwitch",
            "override applied qn=${candidate.qn} codecid=${candidate.codecid} trackIndex=${candidate.trackIndex} " +
                "pos=${exoPlayer.currentPosition} buffered=${exoPlayer.bufferedPosition} state=${exoPlayer.playbackState}",
        )
    }

    private fun describeVideoTracks(tracks: Tracks): String {
        val groups =
            tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.mapIndexed { groupIndex, group ->
                val formats =
                    (0 until group.length).joinToString(prefix = "[", postfix = "]") { trackIndex ->
                        val format = group.getTrackFormat(trackIndex)
                        val parsed = DashMpdGenerator.parseVideoRepresentationId(format.id)
                        "id=${format.id}:${parsed?.first ?: -1}/${parsed?.second ?: -1}:supported=${group.isTrackSupported(trackIndex)}:" +
                            "selected=${group.isTrackSelected(trackIndex)}"
                    }
                "g$groupIndex$formats"
            }
        return "videoGroups=${groups.joinToString()}"
    }

    private fun setVodPlayable(
        playable: Playable,
        subtitle: MediaItem.SubtitleConfiguration?,
        initialPositionMs: Long?,
    ) {
        when (playable) {
            is Playable.Dash -> {
                val videoFactory =
                    createCdnFactory(
                        DebugStreamKind.VIDEO,
                        urlCandidates = playable.videoUrlCandidates,
                        mediaRequestProfile = playable.videoMediaRequestProfile,
                    )
                val audioFactory =
                    createCdnFactory(
                        DebugStreamKind.AUDIO,
                        urlCandidates = playable.audioUrlCandidates,
                        mediaRequestProfile = playable.audioMediaRequestProfile,
                    )
                setMediaSource(
                    mediaSource = buildMerged(videoFactory, audioFactory, playable.videoUrl, playable.audioUrl, subtitle),
                    initialPositionMs = initialPositionMs,
                )
            }

            is Playable.VideoOnly -> {
                val mainFactory =
                    createCdnFactory(
                        DebugStreamKind.MAIN,
                        urlCandidates = playable.videoUrlCandidates,
                        mediaRequestProfile = playable.videoMediaRequestProfile,
                    )
                setMediaSource(
                    mediaSource = buildProgressive(mainFactory, playable.videoUrl, subtitle),
                    initialPositionMs = initialPositionMs,
                )
            }

            is Playable.Progressive -> {
                val mainFactory =
                    createCdnFactory(
                        DebugStreamKind.MAIN,
                        urlCandidates = playable.urlCandidates,
                        mediaRequestProfile = playable.mediaRequestProfile,
                    )
                setMediaSource(
                    mediaSource = buildProgressive(mainFactory, playable.url, subtitle),
                    initialPositionMs = initialPositionMs,
                )
            }
        }
    }

    private fun setMediaSource(mediaSource: MediaSource, initialPositionMs: Long?) {
        val initialPosition = initialPositionMs?.takeIf { it > 0L }
        if (initialPosition != null) {
            exoPlayer.setMediaSource(mediaSource, initialPosition)
        } else {
            exoPlayer.setMediaSource(mediaSource)
        }
    }
}

private class RoutedHttpDataSourceFactory(
    private val routeFactories: Map<String, DataSource.Factory>,
    private val fallbackFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutedHttpDataSource(routeFactories, fallbackFactory)
}

private class RoutedHttpDataSource(
    private val routeFactories: Map<String, DataSource.Factory>,
    private val fallbackFactory: DataSource.Factory,
) : DataSource {
    private val transferListeners = ArrayList<TransferListener>(2)
    private var upstream: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val factory = routeFactories[dataSpec.uri.toString()] ?: fallbackFactory
        val dataSource = factory.createDataSource().also { dataSource ->
            transferListeners.forEach(dataSource::addTransferListener)
            upstream = dataSource
        }
        return try {
            dataSource.open(dataSpec)
        } catch (throwable: Throwable) {
            close()
            throw throwable
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return checkNotNull(upstream) { "read() before open()" }.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        runCatching { upstream?.close() }
        upstream = null
    }
}

private class ExtXStartStrippingHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
    private val onPlaylistParsed: ((LiveHlsDebugInfo) -> Unit)? = null,
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        return ExtXStartStrippingParser(delegate.createPlaylistParser(), onPlaylistParsed = onPlaylistParsed)
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> {
        return ExtXStartStrippingParser(
            delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
            onPlaylistParsed = onPlaylistParsed,
        )
    }
}

private class ExtXStartStrippingParser(
    private val delegate: ParsingLoadable.Parser<HlsPlaylist>,
    private val onPlaylistParsed: ((LiveHlsDebugInfo) -> Unit)? = null,
) : ParsingLoadable.Parser<HlsPlaylist> {
    override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
        val bytes = inputStream.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        parseLiveHlsDebugInfo(text = text)?.let { info -> onPlaylistParsed?.invoke(info) }
        if (!text.contains("#EXT-X-START", ignoreCase = true)) {
            return delegate.parse(uri, ByteArrayInputStream(bytes))
        }

        val filtered =
            text
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#EXT-X-START", ignoreCase = true) }
                .joinToString("\n")
        return delegate.parse(uri, ByteArrayInputStream(filtered.toByteArray(Charsets.UTF_8)))
    }
}

private data class ParsedLiveHlsSegment(
    val durationSec: Double,
    val uri: String,
    val bytes: Long?,
    val auxRaw: String?,
)

private fun parseLiveHlsDebugInfo(text: String): LiveHlsDebugInfo? {
    var mediaSequence: Long? = null
    var targetDurationSec: Double? = null
    var mapUri: String? = null
    var pendingDurationSec: Double? = null
    var pendingAuxRaw: String? = null
    val segments = ArrayList<ParsedLiveHlsSegment>()

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isBlank()) continue
        when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                mediaSequence = line.substringAfter(':').trim().toLongOrNull()
            }

            line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                targetDurationSec = line.substringAfter(':').trim().toDoubleOrNull()
            }

            line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                mapUri = parseHlsQuotedAttr(line = line, key = "URI")
            }

            line.startsWith("#EXT-BILI-AUX:", ignoreCase = true) -> {
                pendingAuxRaw = line.substringAfter(':').trim().ifBlank { null }
            }

            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                pendingDurationSec = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
            }

            line.startsWith("#") -> Unit

            else -> {
                val durationSec = pendingDurationSec
                if (durationSec != null && durationSec > 0.0) {
                    segments +=
                        ParsedLiveHlsSegment(
                            durationSec = durationSec,
                            uri = line,
                            bytes = parseLiveHlsAuxBytes(pendingAuxRaw),
                            auxRaw = pendingAuxRaw,
                        )
                }
                pendingDurationSec = null
                pendingAuxRaw = null
            }
        }
    }

    val last = segments.lastOrNull()
    val recentSegments = segments.takeLast(LIVE_HLS_DEBUG_RECENT_SEGMENT_COUNT)
    val recentWithBytes = recentSegments.filter { it.bytes != null && it.durationSec > 0.0 }
    val recentDurationSec = recentWithBytes.sumOf { it.durationSec }
    val recentBytes = recentWithBytes.sumOf { it.bytes ?: 0L }
    val recentBitrateBps =
        if (recentBytes > 0L && recentDurationSec > 0.0) {
            ((recentBytes * 8.0) / recentDurationSec).roundToLong()
        } else {
            null
        }
    val lastSegmentSequence =
        if (mediaSequence != null && last != null) {
            mediaSequence + segments.lastIndex.toLong()
        } else {
            null
        }

    val info =
        LiveHlsDebugInfo(
            mediaSequence = mediaSequence,
            targetDurationSec = targetDurationSec,
            mapUri = mapUri,
            segmentCount = segments.size,
            recentSegmentCount = recentWithBytes.size,
            recentBitrateBps = recentBitrateBps,
            lastSegmentUri = last?.uri,
            lastSegmentDurationSec = last?.durationSec,
            lastSegmentBytes = last?.bytes,
            lastSegmentSequence = lastSegmentSequence,
            lastAuxRaw = last?.auxRaw,
        )
    return if (
        info.mediaSequence != null ||
        info.targetDurationSec != null ||
        !info.mapUri.isNullOrBlank() ||
        !info.lastSegmentUri.isNullOrBlank() ||
        !info.lastAuxRaw.isNullOrBlank()
    ) {
        info
    } else {
        null
    }
}

private fun parseHlsQuotedAttr(line: String, key: String): String? {
    val pattern = Regex("""(?:^|,)${Regex.escape(key)}=\"([^\"]+)\"""")
    return pattern.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
}

private fun parseLiveHlsAuxBytes(auxRaw: String?): Long? {
    val parts = auxRaw?.split('|') ?: return null
    val value = parts.getOrNull(2)?.trim().orEmpty()
    if (value.isBlank()) return null
    return value.toLongOrNull()
        ?: value.toLongOrNull(radix = 16)
}

private const val LIVE_HLS_DEBUG_RECENT_SEGMENT_COUNT = 3

/** 直播续流窗口：这段时间内最多重连 [LIVE_RECONNECT_MAX] 次。 */
private const val LIVE_RECONNECT_WINDOW_MS = 5 * 60 * 1000L
private const val LIVE_RECONNECT_MAX = 8

/** 短租约直播在连接建立多久后主动换签：虎牙实测约 120s 断流，留 20s 余量。 */
private const val LIVE_LEASE_SWITCH_MS = 100_000L

/** HLS playlist 的续签间隔：虎牙同一份签名实测约 125s 后开始 403，取一半留余量。 */
private const val HLS_RENEW_INTERVAL_MS = 45_000L

/** 续签地址的可用窗口：超过就不再用它，宁可让原地址先去试。 */
private const val HLS_RENEW_MAX_AGE_MS = 150_000L

/** 连续多次拿不到同线路新地址（房间下播 / 接口异常）就停下，避免无意义地轮询。 */
private const val HLS_RENEW_MAX_MISSES = 4

private class BlblRenderersFactory(
    context: Context,
    private val volumeBalanceProcessor: VolumeBalanceAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(volumeBalanceProcessor))
            .build()
    }
}
