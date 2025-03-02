package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Lists
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Profile
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.TrackItem
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.get
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.run
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerRadio(
    context: Context,
    private val scope: CoroutineScope,
    private val player: Player,
    private val throwFlow: MutableSharedFlow<Throwable>,
    private val stateFlow: MutableStateFlow<PlayerState.Radio>,
    private val extensionList: StateFlow<List<MusicExtension>?>,
) : Player.Listener {

    companion object {
        const val AUTO_START_RADIO = "auto_start_radio"
        suspend fun start(
            throwableFlow: MutableSharedFlow<Throwable>,
            extensionListFlow: StateFlow<List<MusicExtension>?>,
            extId: String,
            item: EchoMediaItem,
            itemContext: EchoMediaItem?
        ): PlayerState.Radio.Loaded? {
            val extension = extensionListFlow.getExtensionOrThrow(extId)
            return extension.get<RadioClient, PlayerState.Radio.Loaded?>(throwableFlow) {
                val radio = when (item) {
                    is TrackItem -> radio(item.track, itemContext)
                    is Lists.PlaylistItem -> radio(item.playlist)
                    is Lists.AlbumItem -> radio(item.album)
                    is Profile.ArtistItem -> radio(item.artist)
                    is Profile.UserItem -> radio(item.user)
                    is Lists.RadioItem -> throw IllegalStateException()
                }
                val tracks = loadTracks(radio)
                PlayerState.Radio.Loaded(extId, radio, null) {
                    extension.run(throwableFlow) { tracks.loadList(it) }
                }
            }
        }

        suspend fun play(
            player: Player,
            settings: SharedPreferences,
            stateFlow: MutableStateFlow<PlayerState.Radio>,
            loaded: PlayerState.Radio.Loaded
        ) {
            stateFlow.value = PlayerState.Radio.Loading
            val tracks = loaded.tracks(loaded.cont) ?: return

            stateFlow.value = if (tracks.continuation == null) PlayerState.Radio.Empty
            else loaded.copy(cont = tracks.continuation)

            val item = tracks.data.map {
                MediaItemUtils.build(
                    settings, it, loaded.clientId, loaded.radio.toMediaItem()
                )
            }

            withContext(Dispatchers.Main) {
                player.addMediaItems(item)
                player.prepare()
            }
        }
    }

    private val settings = context.getSettings()

    private suspend fun loadPlaylist() {
        val mediaItem = withContext(Dispatchers.Main) { player.currentMediaItem } ?: return
        val client = mediaItem.extensionId
        val item = mediaItem.track.toMediaItem()
        val itemContext = mediaItem.context
        stateFlow.value = PlayerState.Radio.Loading
        val loaded = start(throwFlow, extensionList, client, item, itemContext)
        stateFlow.value = loaded ?: PlayerState.Radio.Empty
        if (loaded != null) play(player, settings, stateFlow, loaded)
    }

    private var autoStartRadio = settings.getBoolean(AUTO_START_RADIO, true)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { pref, key ->
        if (key != AUTO_START_RADIO) return@OnSharedPreferenceChangeListener
        autoStartRadio = pref.getBoolean(AUTO_START_RADIO, true)
    }

    init {
        settings.registerOnSharedPreferenceChangeListener(listener)
    }

    private suspend fun startRadio() {
        if (!autoStartRadio) return
        val hasNext = withContext(Dispatchers.Main) {
            player.hasNextMediaItem() || player.currentMediaItem == null
        }
        if (hasNext) return
        when (val state = stateFlow.value) {
            is PlayerState.Radio.Loading -> {}
            is PlayerState.Radio.Empty -> loadPlaylist()
            is PlayerState.Radio.Loaded -> play(player, settings, stateFlow, state)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        scope.launch { startRadio() }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (player.mediaItemCount == 0) stateFlow.value = PlayerState.Radio.Empty
        scope.launch { startRadio() }
    }
}

