package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class MusicArtist(val id: String,val name: String,val photo: String?=null)
internal data class MusicArtistDetails(val artist: MusicArtist,val tracks: NativePage,val albums: List<NativePlaylist>)

/** Catalogue pools contain recommendations too. Only IDs from the user's artist subscription section belong here. */
internal object MusicArtists {
    private val subscriptionTitles=setOf("мои исполнители","ваши исполнители","мои артисты","любимые исполнители","музыкальные подписки","подписки на исполнителей","подписки на артистов","вы подписаны","вы подписаны на","подписки","my artists","your artists","followed artists","artists you follow","subscriptions","following")
    private val ownTitles=setOf("моя музыка","моя библиотека","my music","my library")
    private val artistTitles=setOf("исполнители","артисты","artists")
    private val showAllTitles=setOf("показать всех","показать все","все","все исполнители","show all","see all","view all","all artists")
    private val releaseTitles=setOf("альбомы","синглы","альбомы и синглы","релизы","все альбомы","albums","singles","releases","albums and singles")
    private fun str(e: JsonElement?)=(e as? JsonPrimitive)?.contentOrNull
    private fun title(o: JsonObject): String {
        val layout=o["layout"] as? JsonObject
        val raw=o["title"]?:o["text"]?:layout?.get("title")?:((layout?.get("header") as? JsonObject)?.get("title"))
        return (str(raw)?:str((raw as? JsonObject)?.get("text"))).orEmpty().replace('\u00a0',' ').trim().lowercase()
            .replace(Regex("\\s+")," ").replace(Regex("\\s*(?:[·•]\\s*|\\()?\\d+\\)?$"),"").trim()
    }
    private fun ownSection(o: JsonObject)=title(o) in ownTitles||str(o["type"]) in setOf("my_music","my_audio","own_music")
    private fun subscribed(o: JsonObject,own: Boolean=false)=title(o) in subscriptionTitles||own&&title(o) in artistTitles||
        str(o["type"]) in setOf("followed_artists","artist_subscriptions","subscribed_artists")
    private fun containers(o: JsonObject)=listOf(o)+listOf("catalog","section","block").mapNotNull{o[it] as? JsonObject}
    private fun artistRows(value: JsonElement?): List<JsonObject> = when(value) {
        is JsonArray->value.mapNotNull{it as? JsonObject}.filter{it["id"]!=null&&it["name"]!=null}
        is JsonObject->if(value["id"]!=null&&value["name"]!=null)listOf(value) else value.entries.mapNotNull{(id,v)->
            (v as? JsonObject)?.takeIf{it["name"]!=null}?.let{if(it["id"]==null)JsonObject(it+mapOf("id" to JsonPrimitive(id)))else it}
        }
        else->emptyList()

    }
    private fun following(o: JsonObject): Boolean? {
        val values=listOf("is_following","is_followed").mapNotNull{key->when(str(o[key])){"0","false"->false;"1","true"->true;else->null}}
        return if(false in values)false else if(true in values)true else null
    }
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
    private fun artist(o: JsonObject)=MusicArtist(text(o,"id"),text(o,"name"),photo(o["photo"]?:o["photos"]?:o["thumb"]?:o["images"]?:o["cover"]?:o))
    private fun blocks(o: JsonObject): List<Triple<JsonObject,String,Int>> {
        val catalog=o["catalog"] as? JsonObject
        val shared=(listOf(o,catalog).filterNotNull()).flatMap{(it["blocks"] as? JsonArray).orEmpty().mapNotNull{b->b as? JsonObject}}
        val byId=shared.mapNotNull{b->str(b["id"])?.let{it to b}}.toMap()
        val sections=sections(o)
        val used=mutableSetOf<JsonObject>()
        val result=sections.flatMapIndexed{index,section->
            val references=(section["blocks"] as? JsonArray)?:section["blocks_ids"] as? JsonArray
            references.orEmpty().mapNotNull{raw->
                val block=(raw as? JsonObject)?:byId[str(raw)]
                block?.also{used.add(it)}?.let{Triple(it,if(ownSection(section))"моя музыка" else title(section),index)}
            }
        }
        return result+shared.filter{it !in used}.map{Triple(it,"",-1)}+listOfNotNull((o["block"] as? JsonObject)?.let{Triple(it,"",-1)})
    }
    private fun navigation(o: JsonObject): List<JsonObject> {
        val layout=o["layout"] as? JsonObject
        val header=layout?.get("header") as? JsonObject
        val nodes=listOf(o,layout,header).filterNotNull()
        val buttons=nodes.flatMap{node->listOf("actions","buttons","links").flatMap{key->
            (node[key] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}
        }}+listOfNotNull(((o["meta"] as? JsonObject)?.get("show_all_info")) as? JsonObject)
        return buttons.flatMap{button->listOf(button)+listOf("action","link").mapNotNull{key->
            (button[key] as? JsonObject)?.let{action->
                if(action["title"]==null&&action["text"]==null)JsonObject(action+((button["title"]?:button["text"])?.let{mapOf("title" to it)}?:emptyMap()))else action
            }
        }}
    }
    private fun ownMusicUrl(raw: JsonElement?,owner: Long?): String? {
        val value=str(raw)?:return null
        val url=(if(value.startsWith("/"))"https://vk.com$value" else value).toHttpUrlOrNull()?:return null
        if(url.scheme!="https"||url.host !in setOf("vk.com","vk.ru","m.vk.com","m.vk.ru","music.vk.com","music.vk.ru")||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty())return null
        val pathOwner=Regex("/audios([1-9][0-9]*)/?").matchEntire(url.encodedPath)?.groupValues?.get(1)?.toLongOrNull()
        if(pathOwner!=null&&owner!=null&&pathOwner!=owner)return null
        if(pathOwner==null&&url.encodedPath !in setOf("/audio","/music","/music/"))return null
        if(url.queryParameter("owner_id")?.let{owner!=null&&it.toLongOrNull()!=owner}==true)return null
        return url.toString()
    }
    private fun sections(o: JsonObject)=(((o["catalog"] as? JsonObject)?.get("sections") as? JsonArray).orEmpty()).mapNotNull{it as? JsonObject}+
        listOfNotNull(o["section"] as? JsonObject)

    private data class Target(val method: String,val id: String,val from: String?=null,val trusted: Boolean=true,val own: Boolean=false)
    private suspend fun collect(initial: JsonObject,subscriptions: Boolean,owner: Long?,
        progress:(List<MusicArtist>,Int)->Unit,fetch:suspend(String,Map<String,String>)->JsonElement): Pair<List<MusicArtist>,List<NativePlaylist>> {
        val artists=linkedMapOf<String,MusicArtist>();val playlists=linkedMapOf<String,NativePlaylist>()
        val pool=mutableMapOf<String,JsonObject>();val listPool=mutableMapOf<String,JsonObject>()
        val pending=java.util.ArrayDeque<Triple<JsonObject,Boolean,Boolean>>();pending.add(Triple(initial,false,false))
        val targets=java.util.ArrayDeque<Target>();val visited=mutableSetOf<Target>()
        var recognized=!subscriptions;var pages=0;var stalled=0;var sectionsSeen=0;var blocksSeen=0
        fun schedule(method: String,id: JsonElement?,from: JsonElement?=null,trusted: Boolean=true,own: Boolean=false) {
            cursor(id)?.let{val target=Target(method,it,cursor(from),trusted,own);if(target !in targets)targets.add(target)}
        }
        fun subscription(o: JsonObject,own: Boolean=false)=subscribed(o,own)
        while(pending.isNotEmpty()||targets.isNotEmpty()) {
            if(pending.isEmpty()) {
                val t=targets.removeFirst();if(!visited.add(t))throw ArtistLoadingFailure(ArtistFailureReason.CURSOR_LOOP,MusicError.INCOMPLETE,t.method)
                if(++pages>200)throw ArtistLoadingFailure(ArtistFailureReason.PAGE_LIMIT,MusicError.TOO_LARGE,t.method)
                val fields=mapOf((when(t.method){"catalog.getSection"->"section_id";"catalog.getAudio"->"url";else->"block_id"}) to t.id)+
                    (t.from?.let{mapOf("start_from" to it)}?:emptyMap())
                val response=fetch(t.method,fields) as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
                pending.add(Triple(response,t.trusted,t.own))
            }
            val (o,trusted,own)=pending.removeFirst()
            val before=artists.size
            val artistContainers=(containers(o)+sections(o)+blocks(o).map{it.first}).distinct()
            for(a in artistContainers.flatMap{artistRows(it["artists"])}) {
                val id=text(a,"id");pool[id]=a
                if(subscriptions)when(following(a)) {
                    true->{recognized=true;artist(a).let{artists[it.id]=it}}
                    false->artists.remove(id)
                    null->Unit
                }
            }
            for(p in (o["playlists"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject})
                listPool[text(p,"owner_id")+"_"+text(p,"id")]=p
            val sections=sections(o);sectionsSeen+=sections.size;blocksSeen+=blocks(o).size
            if(subscriptions)for(s in sections) {
                if(subscription(s,own)) {
                    recognized=true
                    if(subscriptions)for(a in artistRows(s["artists"]))if(following(a)!=false)artist(a).let{artists[it.id]=it}
                    if(!trusted&&(s["blocks"] as? JsonArray).isNullOrEmpty())schedule("catalog.getSection",s["id"]?:s["section_id"])
                }
            }
            if(subscriptions&&trusted)for(c in containers(o))for(a in artistRows(c["items"]?:if(c===o["section"])c["artists"]else null))
                if(following(a)!=false)artist(a).let{artists[it.id]=it}
            // Section-level tabs/buttons may carry the only link to the subscriptions.
            if(subscriptions&&!trusted)for(container in listOf(o)+listOfNotNull(o["catalog"] as? JsonObject)+sections) {
                val ownContainer=own||ownSection(container)
                for(action in navigation(container))if(subscription(action,ownContainer)) {
                    if(cursor(action["section_id"])!=null){recognized=true;schedule("catalog.getSection",action["section_id"])}
                    else ownMusicUrl(action["url"],owner)?.let{url->schedule("catalog.getAudio",JsonPrimitive(url))}
                }
            }
            val namedBlocks=blocks(o).filter{(_,_,index)->sections.getOrNull(index)?.let{subscription(it,own)}==true}.map{it.first}.toSet()
            var heading="";var parent: Int?=null
            for((b,parentTitle,parentIndex) in blocks(o)) {
                if(parent!=parentIndex){heading="";parent=parentIndex}
                val caption=title(b);if(caption.isNotEmpty())heading=caption
                val ownBlock=own||parentTitle in ownTitles
                val meta=(b["meta"] as? JsonObject)?.get("show_all_info") as? JsonObject
                val accepted=trusted||if(subscriptions)subscription(b,ownBlock)||(meta!=null&&subscription(meta,ownBlock))||b in namedBlocks||caption in subscriptionTitles||heading in subscriptionTitles||ownBlock&&(caption in artistTitles||heading in artistTitles) else caption in releaseTitles||heading in releaseTitles
                if(subscriptions&&accepted)recognized=true
                if(accepted) {
                    if(subscriptions) {
                        for(id in ((b["artists_ids"]?:b["artist_ids"]) as? JsonArray).orEmpty()) {
                            val raw=(id as? JsonObject)?:pool[str(id)]?:throw ArtistLoadingFailure(ArtistFailureReason.MISSING_ARTIST,MusicError.INCOMPLETE)
                            if(following(raw)!=false)artist(raw).let{artists[it.id]=it}
                        }
                        for(raw in artistRows(b["artists"]?:b["items"]))if(following(raw)!=false)artist(raw).let{artists[it.id]=it}
                    }else for(id in (b["playlists_ids"] as? JsonArray).orEmpty()) {
                        val key=str(id)?.split('_')?.take(2)?.joinToString("_")
                        val raw=listPool[key]?:throw MusicFailure(MusicError.INCOMPLETE)
                        val parsed=NativeProtocol.catalog(buildJsonObject{put("count",1);put("items",JsonArray(listOf(raw)))})
                        parsed.lists.single().let{playlists[it.key]=it.classified(PlaylistKind.ALBUM)}
                    }
                    if(meta!=null&&!trusted)schedule("catalog.getSection",meta["section_id"])
                    if(cursor(b["next_from"])!=null)schedule("catalog.getBlockItems",b["id"],b["next_from"])
                }
                for(action in navigation(b)) {
                    val wanted=if(subscriptions)subscription(action,ownBlock)||accepted&&title(action) in showAllTitles else title(action) in releaseTitles
                    if(wanted&&!trusted) {
                        if(cursor(action["section_id"])!=null) {recognized=true;schedule("catalog.getSection",action["section_id"])}
                        else ownMusicUrl(action["url"],owner)?.let{url->recognized=true;schedule("catalog.getAudio",JsonPrimitive(url))}
                    }
                }
            }
            // need_blocks may include only a preview of My Music. Hydrate its section when
            // the preview exposes neither a subscriptions block nor an explicit followed flag.
            if(subscriptions&&!recognized&&!own)for(section in sections) {
                if(ownSection(section))
                    schedule("catalog.getSection",section["id"]?:section["section_id"],trusted=false,own=true)
            }
            val s=o["section"] as? JsonObject
            if(trusted&&cursor(s?.get("next_from"))!=null)schedule("catalog.getSection",s?.get("id")?:s?.get("section_id"),s?.get("next_from"),trusted,own)

            if(subscriptions) {
                progress(artists.values.toList(),pages+1)
                val hasMore=cursor(s?.get("next_from"))!=null||blocks(o).any{cursor(it.first["next_from"])!=null}
                stalled=if(trusted&&artists.size==before&&hasMore)stalled+1 else 0
                if(stalled>=2)throw ArtistLoadingFailure(ArtistFailureReason.NO_PROGRESS,MusicError.INCOMPLETE)
            }
        }
        if(!recognized)throw ArtistLoadingFailure(ArtistFailureReason.SUBSCRIPTIONS_NOT_FOUND,MusicError.METHOD,pages=pages+1,sections=sectionsSeen,blocks=blocksSeen,pool=pool.size)
        return artists.values.toList() to playlists.values.toList()
    }
    suspend fun subscriptions(owner: Long?=null,progress:(List<MusicArtist>,Int)->Unit={_,_->},fetch:suspend(String,Map<String,String>)->JsonElement): List<MusicArtist> {
        val response=fetch("catalog.getAudio",emptyMap()) as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
        return try{collect(response,true,owner,progress,fetch).first}
        catch(e: MusicFailure) {
            if(e.code!=MusicError.METHOD||owner==null)throw e
            val own=fetch("catalog.getAudio",mapOf("current_user" to "1")) as? JsonObject?:throw MusicFailure(MusicError.RESPONSE)
            collect(own,true,owner,progress,fetch).first
        }
    }
    suspend fun albums(response: JsonElement,fetch:suspend(String,Map<String,String>)->JsonElement)=
        collect(response as? JsonObject?:throw MusicFailure(MusicError.RESPONSE),false,null,{_,_->},fetch).second
}
