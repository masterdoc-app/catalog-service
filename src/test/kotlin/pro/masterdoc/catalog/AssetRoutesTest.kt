package pro.masterdoc.catalog

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AssetRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createDraftAndConfirm() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Компрессор","siteId":"site-1","source":"ai_generated","documentIds":["doc-1"]}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val created = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("draft", created["status"]!!.jsonPrimitive.content)
        assertEquals("ai_generated", created["source"]!!.jsonPrimitive.content)
        val id = created["id"]!!.jsonPrimitive.content

        val confirm =
            client.post("/assets/$id/confirm") {
                header("X-Org-Id", "org-1")
            }
        assertEquals(HttpStatusCode.OK, confirm.status)
        assertEquals("active", json.parseToJsonElement(confirm.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun confirmNonDraftFails() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"A","siteId":"s","source":"manual","asDraft":false}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val confirm = client.post("/assets/$id/confirm") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.BadRequest, confirm.status)
    }

    @Test
    fun listScopedByOrg() = testApplication {
        val store = AssetStore()
        application { module(store) }
        client.post("/assets") {
            header("X-Org-Id", "org-a")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"A","siteId":"s"}""")
        }
        client.post("/assets") {
            header("X-Org-Id", "org-b")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"B","siteId":"s"}""")
        }
        val list = client.get("/assets") { header("X-Org-Id", "org-a") }
        assertTrue(list.bodyAsText().contains("\"A\""))
        assertTrue(!list.bodyAsText().contains("\"B\""))
    }

    @Test
    fun rejectDraftSucceeds() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Draft","siteId":"s","source":"ai_generated"}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val reject = client.post("/assets/$id/reject") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NoContent, reject.status)

        val get = client.get("/assets/$id") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun rejectActiveFails() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Active","siteId":"s","source":"manual","asDraft":false}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val reject = client.post("/assets/$id/reject") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.BadRequest, reject.status)
    }

    @Test
    fun getOtherOrgAssetNotFound() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-a")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Secret","siteId":"s"}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val get = client.get("/assets/$id") { header("X-Org-Id", "org-b") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun aiGeneratedForcesDraftEvenWhenAsDraftFalse() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"AI","siteId":"s","source":"ai_generated","asDraft":false}""")
            }
        assertEquals(HttpStatusCode.Created, create.status)
        assertEquals(
            "draft",
            json.parseToJsonElement(create.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun descriptionPersistedOnCreate() = testApplication {
        val store = AssetStore()
        application { module(store) }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Кран-балка","siteId":"site-1","category":"lifting","description":"Грузоподъёмная балка","source":"ai_generated"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)
        val body = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("Грузоподъёмная балка", body["description"]!!.jsonPrimitive.content)
        assertEquals("lifting", body["category"]!!.jsonPrimitive.content)
    }
}
