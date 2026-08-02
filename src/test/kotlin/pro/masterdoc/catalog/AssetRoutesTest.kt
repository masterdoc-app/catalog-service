package pro.masterdoc.catalog

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.server.testing.ApplicationTestBuilder
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Testcontainers(disabledWithoutDocker = true)
class AssetRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dataSource: HikariDataSource

    private suspend fun io.ktor.client.HttpClient.ensureSite(
        orgId: String,
        siteId: String = "site-1",
        name: String = "Site",
    ) {
        post("/sites") {
            header("X-Org-Id", orgId)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$siteId","name":"$name"}""")
        }
    }

    @Test
    fun createDraftAndConfirm() = withApplication {
        client.ensureSite("org-1")
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
    fun confirmNonDraftFails() = withApplication {
        client.ensureSite("org-1", "s")
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
    fun listScopedByOrg() = withApplication {
        client.ensureSite("org-a", "s")
        client.ensureSite("org-b", "s")
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
    fun rejectDraftSucceeds() = withApplication {
        client.ensureSite("org-1", "s")
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
    fun rejectActiveFails() = withApplication {
        client.ensureSite("org-1", "s")
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
    fun deleteActiveAssetSucceeds() = withApplication {
        client.ensureSite("org-1", "s")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Active","siteId":"s","source":"manual","asDraft":false,"documentIds":["doc-1"]}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val delete = client.delete("/assets/$id") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NoContent, delete.status)

        val get = client.get("/assets/$id") { header("X-Org-Id", "org-1") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun createRejectsDocumentAlreadyBoundToOtherAsset() = withApplication {
        client.ensureSite("org-1")
        client.post("/assets") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"First","siteId":"site-1","documentIds":["doc-shared"]}""")
        }
        val second =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Second","siteId":"site-1","documentIds":["doc-shared"]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, second.status)
        assertTrue(second.bodyAsText().contains("document already bound"))
    }

    @Test
    fun patchRejectsDocumentAlreadyBoundToOtherAsset() = withApplication {
        client.ensureSite("org-1")
        client.post("/assets") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"First","siteId":"site-1","documentIds":["doc-shared"]}""")
        }
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Second","siteId":"site-1"}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val patch =
            client.patch("/assets/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"documentIds":["doc-shared"]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, patch.status)
        assertTrue(patch.bodyAsText().contains("document already bound"))
    }

    @Test
    fun getOtherOrgAssetNotFound() = withApplication {
        client.ensureSite("org-a", "s")
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
    fun aiGeneratedForcesDraftEvenWhenAsDraftFalse() = withApplication {
        client.ensureSite("org-1", "s")
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
    fun descriptionPersistedOnCreate() = withApplication {
        client.ensureSite("org-1")
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

    @Test
    fun patchAssetUpdatesNameInventoryNoAndDescription() = withApplication {
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Черновик","siteId":"site-1","description":"Старое описание"}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val patch =
            client.patch("/assets/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Компрессор","inventoryNo":"ИНВ-42","description":"Описание из документа"}""")
            }

        assertEquals(HttpStatusCode.OK, patch.status)
        val body = json.parseToJsonElement(patch.bodyAsText()).jsonObject
        assertEquals("Компрессор", body["name"]!!.jsonPrimitive.content)
        assertEquals("ИНВ-42", body["inventoryNo"]!!.jsonPrimitive.content)
        assertEquals("Описание из документа", body["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun patchAssetReplacesDocumentIdsWithAtMostOne() = withApplication {
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Компрессор","siteId":"site-1","documentIds":["doc-1"]}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val patch =
            client.patch("/assets/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"documentIds":["doc-2"]}""")
            }

        assertEquals(HttpStatusCode.OK, patch.status)
        assertEquals(
            "[\"doc-2\"]",
            json.parseToJsonElement(patch.bodyAsText()).jsonObject["documentIds"].toString(),
        )
    }

    @Test
    fun patchAssetRejectsMultipleDocumentIds() = withApplication {
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Компрессор","siteId":"site-1","documentIds":["doc-1"]}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val patch =
            client.patch("/assets/$id") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"documentIds":["doc-1","doc-2"]}""")
            }

        assertEquals(HttpStatusCode.BadRequest, patch.status)
        assertTrue(patch.bodyAsText().contains("at most one document"))
    }

    @Test
    fun createAssetRejectsMultipleDocumentIds() = withApplication {
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Компрессор","siteId":"site-1","documentIds":["doc-1","doc-2"]}""")
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
        assertTrue(create.bodyAsText().contains("at most one document"))
    }

    @Test
    fun createWithUnknownSiteFails() = withApplication {
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"X","siteId":"missing"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, create.status)
        assertTrue(create.bodyAsText().contains("Unknown siteId"))
    }

    @Test
    fun moveAssetChangesSite() = withApplication {
        client.ensureSite("org-1", "a", "A")
        client.ensureSite("org-1", "b", "B")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Pump","siteId":"a","asDraft":false}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val moved =
            client.post("/assets/$id/move") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"siteId":"b"}""")
            }
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals("b", json.parseToJsonElement(moved.bodyAsText()).jsonObject["siteId"]!!.jsonPrimitive.content)
        val filtered = client.get("/assets?siteId=b") { header("X-Org-Id", "org-1") }
        assertTrue(filtered.bodyAsText().contains(id))
    }

    @Test
    fun unlinkDocumentsRemovesIds() = withApplication {
        client.ensureSite("org-1")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Компрессор","siteId":"site-1","documentIds":["doc-1"]}""",
                )
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val unlinked =
            client.post("/assets/$id/unlink-documents") {
                header("X-Org-Id", "org-1")
                contentType(ContentType.Application.Json)
                setBody("""{"documentIds":["doc-1"]}""")
            }
        assertEquals(HttpStatusCode.OK, unlinked.status)
        val body = json.parseToJsonElement(unlinked.bodyAsText()).jsonObject
        val docs = body["documentIds"]
        assertTrue(docs == null || docs.toString() == "[]")

        val get = client.get("/assets/$id") { header("X-Org-Id", "org-1") }
        assertTrue(!get.bodyAsText().contains("doc-1"))
    }

    @Test
    fun unlinkDocumentsOtherOrgNotFound() = withApplication {
        client.ensureSite("org-a")
        val create =
            client.post("/assets") {
                header("X-Org-Id", "org-a")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"A","siteId":"site-1","documentIds":["d1"]}""")
            }
        val id = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val unlinked =
            client.post("/assets/$id/unlink-documents") {
                header("X-Org-Id", "org-b")
                contentType(ContentType.Application.Json)
                setBody("""{"documentIds":["d1"]}""")
            }
        assertEquals(HttpStatusCode.NotFound, unlinked.status)
    }
    private fun withApplication(block: suspend ApplicationTestBuilder.() -> Unit) {
        Db.connect(postgres.jdbcUrl, postgres.username, postgres.password).use { connected ->
            dataSource = connected
            connected.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("TRUNCATE user_scopes, assets, sites")
                }
            }
            testApplication {
                application { module(dataSource) }
                block()
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog")
    }

}
