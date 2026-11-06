package app.alcovaibe.vkmusicexport.music

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * UIDT is available only on API 34+. On Q–Tiramisu an explicitly started dataSync
 * foreground service executes the SAME durable queue/scan, never a second engine.
 * Calls originate from a visible user action; OS background-launch restrictions
 * are reported as a scheduling failure rather than bypassed.
 */
internal object BackgroundTransport {
    internal const val START_TICKET="foreground_start_ticket"
    internal val exportStarts=ForegroundStartGate()
    internal val duplicateStarts=ForegroundStartGate()
    private fun startLegacy(context: Context,intent: Intent,gate: ForegroundStartGate): Boolean {
        val ticket=gate.begin()
        try {
            val accepted=ContextCompat.startForegroundService(context,intent.putExtra(START_TICKET,ticket))!=null
            if(!accepted) {
                val failed=gate.acknowledge(ticket)
                if(failed.cancelled&&failed.drained)context.stopService(intent)
            }
            return accepted
        }catch(e: Exception) {
            val failed=gate.acknowledge(ticket)
            if(failed.cancelled&&failed.drained)runCatching{context.stopService(intent)}
            throw e
        }
    }
    internal fun usesUserInitiatedJobs(sdk: Int) = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun startExport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return startLegacy(context,Intent(context,LegacyAudioExportService::class.java),exportStarts)
        }
        val info=JobInfo.Builder(AudioExportRuntime.JOB_ID,ComponentName(context,AudioExportJob::class.java))
            .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true).build()
        return context.getSystemService(JobScheduler::class.java).schedule(info)==JobScheduler.RESULT_SUCCESS
    }

    fun stopExport(context: Context) {
        if (usesUserInitiatedJobs(Build.VERSION.SDK_INT))context.getSystemService(JobScheduler::class.java).cancel(AudioExportRuntime.JOB_ID)
        else if(exportStarts.stop())context.stopService(Intent(context,LegacyAudioExportService::class.java))
    }

    fun startDuplicates(context: Context,id: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return startLegacy(context,Intent(context,LegacyDuplicateCheckService::class.java)
                .putExtra(DuplicateCheckRuntime.RUN_EXTRA,id),duplicateStarts)
        }
        return context.getSystemService(JobScheduler::class.java)
            .schedule(DuplicateCheckRuntime.job(context,id))==JobScheduler.RESULT_SUCCESS
    }

    fun stopDuplicates(context: Context) {
        if (usesUserInitiatedJobs(Build.VERSION.SDK_INT))context.getSystemService(JobScheduler::class.java).cancel(DuplicateCheckRuntime.JOB_ID)
        else if(duplicateStarts.stop())context.stopService(Intent(context,LegacyDuplicateCheckService::class.java))
    }
}
