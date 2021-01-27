package dk.youtec.drchannels.logic.viewmodel

import dk.youtec.drapi.DrMuRepository
import dk.youtec.drapi.Logger
import dk.youtec.drapi.MuNowNext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

open class TvChannelsViewModelImpl : TvChannelsViewModel, ViewModel, CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Main

    private val api = DrMuRepository()

    override val playback: SharedFlow<VideoItem> = MutableSharedFlow()
    override val error: SharedFlow<ChannelsError> = MutableSharedFlow()

    override val channels: Flow<List<MuNowNext>> = flow {
        while (true) {
            try {
                emit(api.getScheduleNowNext().filter { it.now != null })
                delay(30000)
            } catch (e: CancellationException) {
                return@flow
            } catch (e: Exception) {
                Logger.e(e, e.message ?: "")
                error.emit(ChannelsError.LoadingChannelsFailed)
                delay(5000)
            }
        }
    }


    /**
     * Used by iOS to observe the channels Flow by having this VM collect and call [callback].
     * @return a Cancelable that can cancel the coroutine launched.
     */
    @Suppress("unused")
    fun observeChannels(callback: (List<MuNowNext>) -> Unit): Cancelable {
        val job = launch {
            channels.collect { channels ->
                callback(channels)
            }
        }
        return Cancelable { job.cancel() }
    }

    @Suppress("unused")
    fun observeError(callback: (ChannelsError) -> Unit): Cancelable {
        val job = launch {
            error.collect { error ->
                callback(error)
            }
        }
        return Cancelable { job.cancel() }
    }

    @Suppress("unused")
    fun playTvChannel(muNowNext: MuNowNext, callback: (VideoItem) -> Unit): Cancelable {
        val job = launch {
            playback.collect { videoItem ->
                callback(videoItem)
            }
        }
        playTvChannel(muNowNext)
        return Cancelable { job.cancel() }
    }

    override fun playTvChannel(muNowNext: MuNowNext) {
        launch {
            try {
                val title = muNowNext.now?.title ?: muNowNext.channelSlug.toUpperCase()
                val server = api.getAllActiveDrTvChannels()
                        .firstOrNull { it.slug == muNowNext.channelSlug }
                        ?.server() ?: throw Exception("Unable to get streaming server")

                val stream = server
                        .qualities.maxByOrNull { it.kbps }
                        ?.streams?.first()?.stream ?: ""

                playback.emit(
                        VideoItem(
                                title,
                                "${server.server}/$stream",
                                muNowNext.now?.programCard?.primaryImageUri
                        )
                )
            } catch (e: Exception) {
                handleException(e)
            }
        }
    }

    override fun playProgram(muNowNext: MuNowNext) {
        launch {
            val uri = muNowNext.now?.programCard?.primaryAsset?.uri
            if (uri != null) {
                try {
                    val manifest = api.getManifest(uri)

                    val playbackUri = manifest.getUri() ?: decryptUri(manifest.getEncryptedUri())
                    if (playbackUri.isNotBlank()) {
                        this@TvChannelsViewModelImpl.playback.emit(
                                VideoItem(
                                        muNowNext.now?.title?.toUpperCase() ?: "",
                                        playbackUri,
                                        muNowNext.now?.programCard?.primaryImageUri
                                )
                        )
                    } else {
                        error.emit(ChannelsError.NoStream)
                    }
                } catch (e: Exception) {
                    handleException(e)
                }
            } else {
                error.emit(ChannelsError.NoStream)
            }
        }
    }

    override fun onCleared() {
        job.cancel()
    }

    private suspend fun handleException(e: Exception) {
        error.emit(if (e.message != null && e.message != "Success") {
            ChannelsError.LoadingChannelFailed(e.message)
        } else {
            ChannelsError.LoadingChannelFailed("Can't change channel")
        })
    }
}
