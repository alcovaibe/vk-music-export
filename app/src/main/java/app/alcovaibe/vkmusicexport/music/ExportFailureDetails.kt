package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable

/** Fixed reasons and measurements only; never persist signed URLs or server messages. */
@Serializable
internal data class ExportFailureDetails(val expectedSeconds: Int?=null,val actualMs: Long?=null,
    val hlsSeconds: Double?=null,val accessReason: String?=null,val httpStatus: Int?=null) {
    val label: String get()=when {
        expectedSeconds!=null&&actualMs!=null->"Длительность ВК: $expectedSeconds с; получено: ${actualMs/1000.0} с."+
            (hlsSeconds?.let{" HLS: $it с."}?:"")
        accessReason!=null->TrackAccessIssue.entries.firstOrNull{it.name==accessReason}?.label?:"Причина недоступности не определена."
        httpStatus!=null->"Медиасервер вернул HTTP $httpStatus."
        else->"Подробности этой попытки не сохранены."
    }
}

internal fun exportFailureDetails(error: Exception): ExportFailureDetails?=when(error) {
    is AudioDurationMismatch->ExportFailureDetails(error.expectedSeconds,error.actualMs,error.hlsSeconds)
    is TrackUnavailable->ExportFailureDetails(accessReason=error.issue.name)
    is MediaHttpFailure->ExportFailureDetails(httpStatus=error.status)
    else->null
}
