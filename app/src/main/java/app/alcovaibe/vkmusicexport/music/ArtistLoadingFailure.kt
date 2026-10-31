package app.alcovaibe.vkmusicexport.music

/** Fixed categories only: server text, catalogue titles, IDs, URLs and credentials never become error text. */
internal enum class ArtistFailureReason { REQUEST,SUBSCRIPTIONS_NOT_FOUND,MISSING_ARTIST,CURSOR_LOOP,NO_PROGRESS,UNEXPECTED_FORMAT,PAGE_LIMIT }
internal class ArtistLoadingFailure(val reason: ArtistFailureReason,code: MusicError,
    val method: String?=null,val pages: Int=0,val sections: Int=0,val blocks: Int=0,val pool: Int=0,
    failure: MusicFailure?=null): MusicFailure(code,failure?.remoteCode,failure?.kind,failure?.httpStatus) {
    init {require(method==null||method in METHODS)}
    companion object {val METHODS=setOf("catalog.getAudio","catalog.getSection","catalog.getBlockItems","catalog.getAudioArtist","audio.getAudiosByArtist")}
}
internal object ArtistFailureText {
    fun describe(error: Exception): String {
        val failure=error as? MusicFailure
        val artist=error as? ArtistLoadingFailure
        val prefix=artist?.method?.let{"Запрос $it. "}.orEmpty()
        val detail=when(artist?.reason) {
            ArtistFailureReason.SUBSCRIPTIONS_NOT_FOUND->"Ответ ВК получен, но раздел подписок на исполнителей не найден. Раздел мог отсутствовать в ответе или иметь неподдерживаемый приложением формат. Это не подтверждённый отказ ВК в доступе. "+
                "В последнем проходе прочитано страниц: ${artist.pages}, разделов: ${artist.sections}, блоков: ${artist.blocks}, записей исполнителей: ${artist.pool}."
            ArtistFailureReason.MISSING_ARTIST->"ВК передал ссылку на исполнителя в разделе подписок, но не передал его данные. Список неполный."
            ArtistFailureReason.CURSOR_LOOP->"ВК повторил уже прочитанную страницу каталога. Загрузка остановлена, чтобы не повторять запросы бесконечно."
            ArtistFailureReason.NO_PROGRESS->"Продолжение каталога не добавляет исполнителей. Полноту списка подтвердить не удалось."
            ArtistFailureReason.PAGE_LIMIT->"Достигнут предел числа страниц каталога. Список загружен не полностью."
            ArtistFailureReason.UNEXPECTED_FORMAT->"Ответ получен, но приложение не смогло разобрать его структуру."
            else->when {
                failure?.remoteCode!=null->"ВК вернул ошибку API ${failure.remoteCode}: "+when(failure.code) {
                    MusicError.METHOD->"метод недоступен для текущего подключения."
                    MusicError.DENIED->"доступ к этому методу запрещён."
                    MusicError.ACCESS->"сохранённый доступ не принят. Требуется повторный вход."
                    MusicError.RATE_LIMIT->"ограничено количество запросов."
                    MusicError.CAPTCHA,MusicError.VALIDATION->"требуется подтверждение входа в ВК."
                    else->"запрос не выполнен."
                }
                failure?.httpStatus!=null->"Сервер ВК вернул HTTP ${failure.httpStatus}. Ошибка API ВК в ответе не подтверждена."
                failure?.code in setOf(MusicError.NETWORK,MusicError.TLS,MusicError.TIMEOUT)->failure!!.code.label+
                    (failure.kind?.let{" Тип соединения: ${it.name}."}?:"")
                failure?.code==MusicError.RESPONSE->"Приложение не смогло разобрать ответ ВК."
                failure?.code==MusicError.INCOMPLETE->"Не удалось подтвердить полноту ответа ВК. Уже полученные исполнители сохранены на экране."
                else->failure?.code?.label?:"Не удалось завершить загрузку. Причина не определена; подробности доступны в JSON-отчёте."
            }
        }
        return prefix+detail+(failure?.let{" Код приложения: ${it.code.name}."}?:"")
    }
}
