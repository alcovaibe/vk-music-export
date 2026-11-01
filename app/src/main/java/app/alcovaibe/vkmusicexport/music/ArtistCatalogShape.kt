package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.json.*

/** Whitelisted field names and value types only. Never export IDs, titles, URLs, tokens or unknown keys. */
internal object ArtistCatalogShape {
    private val keys=setOf("catalog","section","sections","block","blocks","blocks_ids","artists","artists_ids","artist_ids","items",
        "layout","header","meta","show_all_info","actions","buttons","links","action","link","title","text","id","section_id","block_id","url","next_from","type","is_following","is_followed","default_section","data_type","caption","label")
    fun describe(value: JsonElement): String {
        val lines=mutableListOf<String>();var nodes=0
        val root=value as? JsonObject
        val catalog=root?.get("catalog") as? JsonObject
        val sections=((catalog?.get("sections")?:root?.get("sections")) as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}+
            listOfNotNull(root?.get("section") as? JsonObject,catalog?.get("header") as? JsonObject,root?.get("header") as? JsonObject)
        fun count(v: JsonElement?)=when(v){is JsonArray->v.size;is JsonObject->v.size;else->0}
        for((index,section)in sections.take(20).withIndex()) {
            val blocks=(section["blocks"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}
            val heading=(section["title"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()
            val label=when(heading){"моя музыка","my music","my library"->"own";"мои исполнители","ваши исполнители","подписки","followed artists"->"subscriptions";else->"other"}
            lines.add("section[$index] label=$label blocks=${blocks.size} artist_refs=${blocks.sumOf{count(it["artists_ids"]?:it["artist_ids"])}} artists=${count(section["artists"])} buttons=${count(section["buttons"])}")
        }
        val summary=lines.joinToString("\n").take(1800)
        lines.clear()
        fun visit(node: JsonElement,path: String,depth: Int) {
            if(++nodes>80||depth>6)return
            when(node) {
                is JsonObject->{
                    val allowed=node.keys.filter{it in keys}.sorted()
                    lines.add("$path object(${allowed.joinToString(",")}); other=${node.keys.size-allowed.size}")
                    allowed.forEach{key->visit(node.getValue(key),"$path.$key",depth+1)}
                }
                is JsonArray->{lines.add("$path array(${node.size})");node.take(2).forEachIndexed{i,child->visit(child,"$path[$i]",depth+1)}}
                is JsonPrimitive->lines.add("$path "+when{node is JsonNull->"null";node.isString->"string";node.booleanOrNull!=null->"boolean";else->"number"})
            }
        }
        visit(value,"root",0)
        return (summary+"\n"+lines.joinToString("\n").take(1800)).take(3600)
    }
}
