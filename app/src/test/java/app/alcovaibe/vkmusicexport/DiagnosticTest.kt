package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.diagnostics.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class DiagnosticTest {
    @Test fun urlRedactionIsDenyByDefault() {
        val secrets=listOf("+79991234567","pAssw0rd","123456","vk1.a.secret","cookieSecret","short")
        for(s in secrets) {
            listOf("https://id.vk.com/auth?phone=$s&password=$s#code=$s", "https://$s.evil.test/login/$s",
                "https://vk.ru/$s/callback/$s", "intent://$s#Intent;token=$s;end", "data:text/html,$s",
                "https://user:$s@vk.ru/login", "https://vk.ru/%2B79991234567/$s").forEach { raw ->
                assertFalse(Privacy.url(raw),Privacy.url(raw).contains(s))
            }
        }
        assertEquals("https://id.vk.com/auth[parameters-omitted]", Privacy.url("https://id.vk.com/auth?x=y"))
        assertFalse(Privacy.url("https://vk.ru/music/playlist/123_456_secret").contains("secret"))
    }
    @Test fun arbitraryMessagesAndCodesNeverSurvive() {
        for(s in listOf("short", "password=abc", "1234", "token=foo", "TypeError: password=abc", "Bearer abc")) {
            assertFalse(Privacy.messageKind(s).contains(s))
            assertEquals("OTHER_CODE_OMITTED", Privacy.code(s))
        }
        assertEquals("TypeError",Privacy.messageKind("TypeError: code 123456"))
        assertEquals("[unexpected-agent-omitted]",Privacy.agent("password=short"))
    }
    @Test fun interruptedTailExportsValidJsonAndPreviousSurvivesRestart() {
        val root=Files.createTempDirectory("journal").toFile()
        try {
            val first=RunJournal(root,"001")
            first.append("{\"type\":\"before_kill\"}"); first.close()
            File(root,"001/0.ndjson").appendText("{\"type\":\"interrupted")
            val second=RunJournal(root,"002")
            assertEquals("001",second.previous())
            val data=Json.parseToJsonElement(second.export("001")).jsonObject
            assertEquals(1,data["recovery_invalid_records"]!!.jsonPrimitive.int)
            assertEquals("before_kill",data["events"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
            second.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun startupVersionMetadataSurvivesEventRotation() {
        val root=Files.createTempDirectory("journal-meta").toFile()
        try {
            val journal=RunJournal(root,"001",segmentLimit=12_000)
            journal.append("{\"type\":\"application_create\",\"data\":{\"version\":\"0.3.3\"}}")
            repeat(70) {journal.append(buildJsonObject{put("type","test");put("padding","x".repeat(1500))}.toString())}
            val data=Json.parseToJsonElement(journal.export()).jsonObject
            assertTrue(data["history_truncated"]!!.jsonPrimitive.boolean)
            assertTrue(data["startup_snapshot"]!!.jsonArray.any{it.jsonObject["data"]?.jsonObject?.get("version")?.jsonPrimitive?.content=="0.3.3"})
            journal.close()
        } finally {root.deleteRecursively()}
    }
    @Test fun capsFiveRunsAndSegmentsWithExplicitRotationAndSequence() {
        val root=Files.createTempDirectory("journal").toFile()
        try {
            repeat(8) { i ->
                val journal=RunJournal(root,"run-$i",segmentLimit=12_000)
                repeat(70) { journal.append(buildJsonObject {put("type","test");put("padding","x".repeat(1500))}.toString()) }
                val data=Json.parseToJsonElement(journal.export()).jsonObject
                assertTrue(data["history_truncated"]!!.jsonPrimitive.boolean)
                val events=data["events"]!!.jsonArray.map{it.jsonObject}
                assertTrue(events.any{it["type"]!!.jsonPrimitive.content=="segment_rotation"})
                val seq=events.map{it["seq"]!!.jsonPrimitive.long}
                assertEquals(seq.sorted().distinct(),seq)
                assertTrue(events.all{it["run_id"]!!.jsonPrimitive.content=="run-$i" && it["elapsed_ns"]!=null && it["time"]!=null})
                journal.close()
                assertTrue(root.listFiles()!!.size<=5)
                assertTrue(root.walkTopDown().filter{it.isFile}.sumOf{it.length()}<=5*2*12_000)
            }
        } finally {root.deleteRecursively()}
    }
}
