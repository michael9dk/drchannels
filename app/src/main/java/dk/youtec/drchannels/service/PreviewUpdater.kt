package dk.youtec.drchannels.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.support.media.tv.Channel
import android.support.media.tv.PreviewProgram
import android.support.media.tv.TvContractCompat
import android.util.Log
import androidx.core.content.systemService
import androidx.core.util.forEach
import androidx.work.*
import com.google.android.media.tv.companionlibrary.utils.TvContractUtils
import dk.youtec.drchannels.ui.PlayerActivity
import dk.youtec.drchannels.util.SharedPreferences
import dk.youtec.drchannels.util.putPreference
import dk.youtec.drchannels.util.serverDateFormat
import java.util.*

class PreviewUpdater : Worker() {
    private val tag = PreviewUpdater::class.java.simpleName
    private val inputId = "dk.youtec.drchannels/.service.DrTvInputService"

    override fun doWork(): Result {
        //Id of preview channel
        val channelId = SharedPreferences.getLong(applicationContext, "channelId")
        if (channelId > 0L) {
            applicationContext.contentResolver
                    .query(TvContractCompat.buildChannelUri(channelId),
                            null, null, null, null)
                    .use { cursor ->
                        if (cursor?.moveToNext() == true) {
                            val channel = Channel.fromCursor(cursor)
                            if (channel.isBrowsable) {
                                //update channel's programs
                                updatePrograms(channelId)
                            }
                        }
                    }
        }
        return Result.SUCCESS
    }

    private fun updatePrograms(channelId: Long) {
        val now = System.currentTimeMillis()
        var nextProgramFinishTime = Long.MAX_VALUE
        val contentResolver = applicationContext.contentResolver

        val channelMap = TvContractUtils.buildChannelMap(contentResolver, inputId)
        channelMap.forEach { id, _ ->
            val programs = TvContractUtils.getPrograms(contentResolver,
                    TvContract.buildChannelUri(id))
            programs.forEach { program ->
                if (program.startTimeUtcMillis <= now && now < program.endTimeUtcMillis) {

                    //Find the next time to start updating previews
                    nextProgramFinishTime = nextProgramFinishTime.coerceAtMost(program.endTimeUtcMillis)

                    val intent = Intent(applicationContext, PlayerActivity::class.java).apply {
                        action = PlayerActivity.ACTION_VIEW
                        putExtra(PlayerActivity.PREFER_EXTENSION_DECODERS_EXTRA, false)
                        data = Uri.parse(program.internalProviderData.videoUrl)
                    }

                    //If a current broadcast, then add it to our channel
                    val previewProgram =
                            PreviewProgram.Builder()
                                    .setChannelId(channelId)
                                    .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                                    .setTitle(program.title)
                                    .setDescription(program.description)
                                    .setIntent(intent)
                                    .setInternalProviderId(program.internalProviderData.videoUrl)
                                    .setStartTimeUtcMillis(program.startTimeUtcMillis)
                                    .setEndTimeUtcMillis(program.endTimeUtcMillis)
                                    .setDurationMillis((program.endTimeUtcMillis - program.startTimeUtcMillis).toInt())
                                    .setLive(true)
                                    .setPosterArtUri(Uri.parse(program.posterArtUri))
                                    .build().toContentValues()

                    val programIdKey = "${program.channelId}-programId"
                    val previewIdKey = "${program.channelId}-previewId"

                    val programId = SharedPreferences.getLong(applicationContext, programIdKey)
                    val previewId = SharedPreferences.getLong(applicationContext, previewIdKey)

                    //If this channel is showing the same program as last time.
                    if (programId == program.id) {
                        Log.d(tag, "Updating program ${program.title} with preview id $previewId")
                        contentResolver.update(TvContractCompat.buildPreviewProgramUri(previewId),
                                previewProgram, null, null)
                    } else {
                        if (previewId > 0) {
                            //Delete the existing program
                            if (contentResolver.delete(TvContractCompat.buildPreviewProgramUri(
                                            previewId),
                                            null, null) > 0) {
                                Log.d(tag, "Deleted program with id $previewId")
                            }
                        }

                        //Create the new program
                        val programUri = contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI,
                                previewProgram)
                        val newPreviewId = ContentUris.parseId(programUri)

                        Log.d(tag, "Added program ${program.title} with preview id $newPreviewId")

                        //Save mapping between channel->previewId(database) and channel->programId(program)
                        applicationContext.putPreference {
                            putLong(programIdKey, program.id)
                            putLong(previewIdKey, newPreviewId)
                        }
                    }
                }
            }
        }

        if (nextProgramFinishTime < Long.MAX_VALUE) {
            val time = serverDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(nextProgramFinishTime))
            Log.d(tag, "Scheduling next preview update at $time")

            val alarmIntent = Intent(applicationContext, PreviewUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(applicationContext,
                    0,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT)

            //Schedule the pending intent
            val alarmManager = applicationContext.systemService<AlarmManager>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        nextProgramFinishTime,
                        pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextProgramFinishTime, pendingIntent)
            }
        }
    }
}

/**
 * Schedules a new task to update the preview channel if no other task is pending or running.
 */
fun schedulePreviewUpdate() {
    val statuses = WorkManager.getInstance()?.getStatusesByTag("updatePreviewPrograms")
    val pendingWork = statuses?.value?.any { !it.state.isFinished } ?: false
    if (!pendingWork) {
        val updatePreviewPrograms = OneTimeWorkRequestBuilder<PreviewUpdater>()
                .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .addTag("updatePreviewPrograms")
                .build()
        WorkManager.getInstance()?.enqueue(updatePreviewPrograms)
    } else {
        Log.d("PreviewUpdater", "Work task already pending")
    }
}