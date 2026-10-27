package app.alcovaibe.vkmusicexport.music

import java.util.UUID

internal enum class ArtworkStep { INITIALIZE,RECOVERY,VERIFY_FILE,BACKUP,READ_TAGS,RESOLVE_COVER,FETCH_COVER,DECODE_COVER,
    WRITE_TAGS,VERIFY_TAGGED,REPLACE_FILE,VERIFY_WRITTEN,UPDATE_INDEX }
internal enum class ArtworkFetchReason { HTTP_STATUS,REDIRECT_LIMIT,REDIRECT_LOCATION_MISSING,REDIRECT_UNSAFE }
internal class ArtworkFetchFailure(val httpStatus: Int,val fetchReason: ArtworkFetchReason,code: MusicError=MusicError.HTTP,val candidatesTried: Int=1): MusicFailure(code)
internal enum class ArtworkSkipReason(val label: String) {
    NO_COVER("ВК не передал ссылку на обложку."),TRACK_UNAVAILABLE("ВК не вернул запись по сохранённому ID. Она могла быть удалена из твоей библиотеки.")
}
internal data class ArtworkSkip(val item: Int,val artist: String,val title: String,val reason: ArtworkSkipReason,val taskKey: String="")
internal data class ArtworkIssue(val item: Int,val name: String,val step: ArtworkStep,val code: MusicError,val reason: String,
    val artist: String="",val title: String="",val httpStatus: Int?=null,val fetchReason: ArtworkFetchReason?=null)
internal fun artworkIssue(item: Int,name: String,step: ArtworkStep,error: Exception,artist: String="",title: String=""): ArtworkIssue {
    val code=if(error is SavedFileFailure)MusicError.INCOMPLETE else (error as? MusicFailure)?.code?:MusicError.STORAGE
    val reason=when {
        error is ArtworkFetchFailure->when(error.fetchReason) {
            ArtworkFetchReason.REDIRECT_LIMIT->"Сервер картинки превысил число перенаправлений (HTTP ${error.httpStatus})."
            ArtworkFetchReason.REDIRECT_LOCATION_MISSING->"Сервер картинки не передал адрес перенаправления (HTTP ${error.httpStatus})."
            ArtworkFetchReason.REDIRECT_UNSAFE->"Перенаправление картинки отклонено: адрес не поддерживается (HTTP ${error.httpStatus})."
            ArtworkFetchReason.HTTP_STATUS->when(error.httpStatus) {
                404,410->if(error.candidatesTried>1)"Картинка не найдена по всем ${error.candidatesTried} предложенным ВК адресам (последний HTTP ${error.httpStatus})."
                    else "Картинка по переданному ВК адресу не найдена (HTTP ${error.httpStatus})."
                401,403->"Сервер картинки отказал в доступе (HTTP ${error.httpStatus})."
                else->"Сервер картинки вернул HTTP ${error.httpStatus}."
            }
        }
        error is SavedFileFailure->error.reason.label+" Запись не начиналась."
        code==MusicError.INCOMPLETE&&step==ArtworkStep.VERIFY_FILE->"Файл отсутствует или изменён. Запись не начиналась."
        code==MusicError.INCOMPLETE&&step in setOf(ArtworkStep.FETCH_COVER,ArtworkStep.DECODE_COVER)->"Картинка получена не полностью."
        code==MusicError.INCOMPLETE->"Повторная проверка файла не прошла. Запись не начиналась."
        code==MusicError.MEDIA_URL->"Адрес картинки не относится к поддерживаемым серверам ВК."
        code==MusicError.FORMAT&&step==ArtworkStep.READ_TAGS->"Структура тегов файла не поддерживается. Запись не начиналась."
        code==MusicError.FORMAT&&step==ArtworkStep.DECODE_COVER->"Картинка не распознана или превышает допустимые размеры."
        step==ArtworkStep.RESOLVE_COVER&&code==MusicError.RESPONSE->"ВК вернул неожиданные данные трека."
        code==MusicError.HTTP->"Сервер обложки вернул ошибку HTTP."
        code in setOf(MusicError.NETWORK,MusicError.TIMEOUT,MusicError.TLS)->"Не удалось получить обложку по сети."
        code==MusicError.FORMAT->"Не удалось безопасно добавить обложку в этот формат файла."
        code==MusicError.STORAGE->"Не удалось прочитать или записать данные. Проверь свободное место и результат восстановления."
        else->code.label
    }
    return ArtworkIssue(item,name,step,code,reason,artist,title,(error as? ArtworkFetchFailure)?.httpStatus,(error as? ArtworkFetchFailure)?.fetchReason)
}

/** Only fixed codes, random operation ID, ordinals and counters. Never names, URLs or exception messages. */
internal class ArtworkDiagnostics(private val observe:(String,Map<String,Any?>)->Unit) {
    private val operation=UUID.randomUUID().toString()
    var item=0
    var step=ArtworkStep.INITIALIZE
    fun candidate(attempt: ArtworkAttempt) {
        observe("audio_artwork_candidate",mapOf("operation_id" to operation,"item" to item,"candidate" to attempt.candidate,
            "total_candidates" to attempt.total,"origin" to attempt.origin.name,"size" to attempt.size,
            "hop" to attempt.hop,"outcome" to attempt.outcome.name,"http_status" to attempt.httpStatus))
    }
    fun result(outcome: String,error: Exception?=null) {
        val issue=error?.let{artworkIssue(item,"",step,it)}
        observe("audio_artwork_item",mapOf("operation_id" to operation,"item" to item,"stage" to step.name,
            "outcome" to outcome,"code" to issue?.code?.name,"kind" to (error as? MusicFailure)?.kind?.name,
            "vk_code" to (error as? MusicFailure)?.remoteCode,"file_reason" to (error as? SavedFileFailure)?.reason?.name,
            "http_status" to (error as? ArtworkFetchFailure)?.httpStatus,"fetch_reason" to (error as? ArtworkFetchFailure)?.fetchReason?.name,
            "candidates_tried" to (error as? ArtworkFetchFailure)?.candidatesTried))
    }
    fun summary(outcome: String,state: ArtworkState) {
        observe("audio_artwork_summary",mapOf("operation_id" to operation,"outcome" to outcome,"total" to state.total,
            "processed" to state.processed,"added" to state.added,"existing" to state.existing,"ready_skipped" to state.readySkipped,"missing" to state.missing,"no_cover" to state.noCover,"unavailable" to state.unavailable,"failed" to state.failed))
    }
}
