package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MusicCatalogState(
    val section: CatalogSection = CatalogSection.LIBRARY,
    val selected: NativePlaylist? = null,
    val page: NativePage? = null,
    val catalog: NativeCatalog? = null,
    val catalogError: MusicError? = null,
    val catalogRefreshing: Boolean = false,
    val catalogStale: Boolean = false,
    val catalogVerifying: Boolean = false,
    val hiddenCatalog: Set<String> = emptySet(),
    val preparing: String? = null
)

/**
 * Isolated catalog controller for section browsing, playlist selection,
 * and page pagination. Keeps catalog state and coroutines scoped independently.
 */
internal class MusicCatalogController(
    private val scope: CoroutineScope,
    private val api: MusicApi,
    private val exportReads: ExportReadCache,
    private val access: suspend () -> MusicAccess,
    private val checked: suspend (MusicAccess) -> MusicAccess,
    private val blocked: () -> Boolean,
    private val report: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow(MusicCatalogState())
    val state = _state.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    fun reset() {
        pause()
        _state.value = MusicCatalogState()
    }

    fun pause() {
        generation++
        work?.cancel()
        work = null
        _state.update { it.copy(catalogRefreshing = false, preparing = null) }
    }

    private suspend fun active(g: Int) {
        currentCoroutineContext().ensureActive()
        if (g != generation || blocked()) throw CancellationException("Catalog operation invalidated")
    }

    fun selectSection(section: CatalogSection, probe: NativeProbe?) {
        if (blocked()) return
        pause()
        _state.update {
            it.copy(
                section = section,
                selected = null,
                page = if (section == CatalogSection.LIBRARY) probe?.library else null
            )
        }
    }

    fun selectPlaylist(list: NativePlaylist?, probe: NativeProbe?, readSessionId: String?) {
        if (blocked() || probe == null) return
        if (list == null) {
            pause()
            _state.update { it.copy(page = probe.library, selected = null, section = CatalogSection.LIBRARY) }
            return
        }
        val displayed = _state.value.catalog?.lists?.singleOrNull { it.key == list.key && !it.deleted } ?: return
        val g = ++generation
        _state.update { it.copy(page = null, selected = displayed, section = CatalogSection.forKind(displayed.kind)) }
        work = scope.launch {
            try {
                withContext(io) {
                    val a = access()
                    active(g)
                    musicCheck(readSessionId != null && a.readSessionId == readSessionId, MusicError.ACCESS)
                    val read = MusicSourceReader(api, exportReads).playlist(
                        a, probe.owner, displayed,
                        guard = { active(g) },
                        progress = { n, total -> _state.update { it.copy(preparing = "Получено треков: $n/$total") } },
                        partial = { part -> if (g == generation) _state.update { old -> if (old.selected?.key == displayed.key) old.copy(page = part) else old } }
                    )
                    active(g)
                    checked(a)
                    active(g)
                    _state.update { old ->
                        old.copy(
                            page = read.page,
                            selected = read.list,
                            section = CatalogSection.forKind(read.list.kind),
                            catalog = old.catalog?.let { catalog ->
                                catalog.copy(lists = catalog.lists.map { if (it.key == read.list.key) read.list else it })
                            },
                            preparing = null
                        )
                    }
                    report("native_music_sample", mapOf("stage" to "playlist", "entries" to read.page.tracks.size, "expected_count" to read.page.count, "complete" to true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (g == generation) {
                    _state.update { it.copy(catalogError = (e as? MusicFailure)?.code ?: MusicError.RESPONSE, preparing = null) }
                }
            } finally {
                if (g == generation) {
                    work = null
                }
            }
        }
    }
}
