package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.*

internal data class MusicArtist(val id: String,val name: String,val photo: String?=null)
internal data class MusicArtistDetails(val artist: MusicArtist,val tracks: NativePage,val albums: List<NativePlaylist>)

/** Catalogue pools contain recommendations too. Only IDs from the user's artist subscription section belong here. */
internal object MusicArtists {
    private val subscriptionTitles=setOf("мои исполнители","исполнители","вы подписаны","подписки","my artists","followed artists","artists","subscriptions")
    private val releaseTitles=setOf("альбомы","синглы","альбомы и синглы","релизы","все альбомы","albums","singles","releases","albums and singles")
    private fun str(e: JsonElement?)=(e as? JsonPrimitive)?.contentOrNull
    private fun title(o: JsonObject): String {
        val layout=o["layout"] as? JsonObject
        val raw=o["title"]?:layout?.get("title")?:((layout?.get("header") as? JsonObject)?.get("title"))
        return (str(raw)?:str((raw as? JsonObject)?.get("text"))).orEmpty().trim().lowercase()
    }
    private fun flag(o: JsonObject,key: String)=str(o[key]) in setOf("1","true")
    private fun text(o: JsonObject,key: String): String {
        val s=str(o[key])?:throw MusicFailure(MusicError.RESPONSE)
        musicCheck(s.isNotBlank()&&s.length<=500&&s.none{it<' '||it=='\u007f'})
        return s
    }
    private fun cursor(raw: JsonElement?): String?=str(raw)?.takeIf{it.isNotEmpty()}?.also{
        musicCheck(it.length<=8192&&it.none{c->c<' '||c=='\u007f'})
    }
    fun photo(e: JsonElement?): String? {
        val values=when(e) {
            is JsonObject->listOfNotNull(str(e["url"]))+listOf(300,270,135,100,68).mapNotNull{str(e["photo_$it"])}+
                listOfNotNull(e["photo"],e["sizes"],e["photos"]).mapNotNull{photo(it)}
            is JsonArray->e.reversed().mapNotNull{photo(it)}
            is JsonPrimitive->listOfNotNull(e.contentOrNull)
            else->emptyList()
        }
        return values.firstNotNullOfOrNull{raw->runCatching{ArtworkAddress.require(raw).toString()}.getOrNull()}
    }
    private fun artist(o: JsonObject)=MusicArtist(text(o,"id"),text(o,"name"),photo(o["photo"]?:o["photos"]?:o["thumb"]))
    private fun blocks(o: JsonObject): List<JsonObject> {
        val catalog=o["catalog"] as? JsonObject
        val sections=(catalog?.get("sections") as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}+
            listOfNotNull(o["section"] as? JsonObject)
        return sections.flatMap{(it["blocks"] as? JsonArray).orEmpty().mapNotNull{b->b as? JsonObject}}+
            listOfNotNull(o["block"] as? JsonObject)
    }
    private data class Target(val method: String,val id: String,val from: String?=null)
    private suspend fun collect(initial: JsonObject,subscriptions: Boolean,
        fetch:suspend(String,Map<String,String>)->JsonElement): Pair<List<MusicArtist>,List<NativePlaylist>> {
        val artists=linkedMapOf<String,MusicArtist>();val playlists=linkedMapOf<String,NativePlaylist>()
        val pool=mutableMapOf<String,JsonObject>();val listPool=mutableMapOf<String,JsonObject>()
        val pending=java.util.ArrayDeque<Pair<JsonObject,Boolean>>();pending.add(initial to false)
        val targets=java.util.ArrayDeque<Target>();val visited=mutableSetOf<Target>()
        var recognized=!subscriptions;var pages=0
        fun schedule(method: String,id: JsonElement?,from: JsonElement?=null) {
            cursor(id)?.let{val target=Target(method,it,cursor(from));if(target !in targets)targets.add(target)}
        }
        fun subscription(o: JsonObject)=title(o) in subscriptionTitles
        while(pending.isNotEmpty()||targets.isNotEmpty()) {
            if(pending.isEmpty()) {
                val t=targets.removeFirst();musicCheck(visited.add(t),MusicError.INCOMPLETE)
                musicCheck(++pages<=1000,MusicError.TOO_LARGE)
                val fields=mapOf((if(t.method=="catalog.getSection")"section_id" else "block_id") to t.id)+
                    (t.from?.let{mapOf("start_from" to it)}?:emptyMap())
                val response=fetch(t.method,fields) as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
                pending.add(response to true)
            }
            val (o,trusted)=pending.removeFirst()
            for(a in (o["artists"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject})pool[text(a,"id")]=a
            for(p in (o["playlists"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject})
                listPool[text(p,"owner_id")+"_"+text(p,"id")]=p
            val sections=(((o["catalog"] as? JsonObject)?.get("sections") as? JsonArray).orEmpty()).mapNotNull{it as? JsonObject}
            if(subscriptions)for(s in sections)if(subscription(s)) {
                recognized=true
                if((s["blocks"] as? JsonArray).isNullOrEmpty())schedule("catalog.getSection",s["id"])
            }
            val namedBlocks=sections.filter{subscription(it)}.flatMap{(it["blocks"] as? JsonArray).orEmpty()}.toSet()
            var heading=""
            for(b in blocks(o)) {
                val caption=title(b);if(caption.isNotEmpty())heading=caption
                val accepted=trusted||if(subscriptions)b in namedBlocks||caption in subscriptionTitles||heading in subscriptionTitles else caption in releaseTitles||heading in releaseTitles
                if(subscriptions&&accepted)recognized=true
                if(accepted) {
                    if(subscriptions)for(id in (b["artists_ids"] as? JsonArray).orEmpty()) {
                        val raw=pool[str(id)]?:throw MusicFailure(MusicError.INCOMPLETE)
                        if(!raw.containsKey("is_following")||flag(raw,"is_following"))artist(raw).let{artists[it.id]=it}
                    }else for(id in (b["playlists_ids"] as? JsonArray).orEmpty()) {
                        val key=str(id)?.split('_')?.take(2)?.joinToString("_")
                        val raw=listPool[key]?:throw MusicFailure(MusicError.INCOMPLETE)
                        val parsed=NativeProtocol.catalog(buildJsonObject{put("count",1);put("items",JsonArray(listOf(raw)))})
                        parsed.lists.single().let{playlists[it.key]=it.classified(PlaylistKind.ALBUM)}
                    }
                    val meta=(b["meta"] as? JsonObject)?.get("show_all_info") as? JsonObject
                    if(meta!=null)schedule("catalog.getSection",meta["section_id"])
                    if(cursor(b["next_from"])!=null)schedule("catalog.getBlockItems",b["id"],b["next_from"])
                }
                for(action in (b["actions"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}) {
                    val wanted=if(subscriptions)subscription(action)else title(action) in releaseTitles
                    if(wanted) {recognized=true;schedule("catalog.getSection",action["section_id"])}
                }
            }
            val s=o["section"] as? JsonObject
            if(trusted&&cursor(s?.get("next_from"))!=null)schedule("catalog.getSection",s?.get("id"),s?.get("next_from"))
            // Explicit following flags are useful even if VK omits its subscription block; unmarked pools are never inferred.
            if(subscriptions)for(raw in pool.values)if(flag(raw,"is_following")) {recognized=true;artist(raw).let{artists[it.id]=it}}
        }
        musicCheck(recognized,MusicError.METHOD)
        return artists.values.toList() to playlists.values.toList()
    }
    suspend fun subscriptions(fetch:suspend(String,Map<String,String>)->JsonElement): List<MusicArtist> {
        val response=fetch("catalog.getAudio",emptyMap()) as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
        return collect(response,true,fetch).first
    }
    suspend fun albums(response: JsonElement,fetch:suspend(String,Map<String,String>)->JsonElement)=
        collect(response as? JsonObject?:throw MusicFailure(MusicError.RESPONSE),false,fetch).second
}
