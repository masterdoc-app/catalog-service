package pro.masterdoc.catalog

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("pro.masterdoc.catalog")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8091
    log.info("event=startup port=$port")
    val dataSource = Db.connect()
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(dataSource) }.start(wait = true)
}

fun Application.module(dataSource: DataSource) {
    val sites = JdbcSiteStore(dataSource)
    val assets = JdbcAssetStore(dataSource)
    val scopes = JdbcScopeStore(dataSource)
    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            log.warn("event=bad_request reason=${cause.message}")
            call.respondText(cause.message ?: "Bad Request", status = HttpStatusCode.BadRequest)
        }
        exception<NoSuchElementException> { call, cause ->
            log.warn("event=not_found reason=${cause.message}")
            call.respondText(cause.message ?: "Not Found", status = HttpStatusCode.NotFound)
        }
        exception<ConflictException> { call, cause ->
            log.warn("event=conflict reason=${cause.message}")
            call.respondText(cause.message ?: "Conflict", status = HttpStatusCode.Conflict)
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        post("/sites") {
            val orgId = call.orgId()
            val req = call.receive<CreateSiteRequest>()
            call.respond(HttpStatusCode.Created, sites.create(orgId, req))
        }
        get("/sites") {
            val orgId = call.orgId()
            call.respond(SiteList(items = sites.ensureDefaultIfEmpty(orgId)))
        }
        get("/sites/{id}") {
            val orgId = call.orgId()
            call.respond(sites.get(orgId, call.parameters["id"]!!))
        }
        put("/sites/{id}") {
            val orgId = call.orgId()
            val req = call.receive<UpdateSiteRequest>()
            call.respond(sites.update(orgId, call.parameters["id"]!!, req))
        }
        patch("/sites/{id}") {
            val orgId = call.orgId()
            val req = call.receive<UpdateSiteRequest>()
            call.respond(sites.update(orgId, call.parameters["id"]!!, req))
        }
        delete("/sites/{id}") {
            val orgId = call.orgId()
            val id = call.parameters["id"]!!
            sites.get(orgId, id)
            if (assets.countOnSite(orgId, id) > 0) {
                throw ConflictException("Site has assets; move or delete them first")
            }
            sites.delete(orgId, id)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/assets") {
            val orgId = call.orgId()
            val req = call.receive<CreateAssetRequest>()
            if (!sites.exists(orgId, req.siteId)) {
                throw IllegalArgumentException("Unknown siteId")
            }
            call.respond(HttpStatusCode.Created, assets.create(orgId, req))
        }
        get("/assets") {
            val orgId = call.orgId()
            val siteId = call.request.queryParameters["siteId"]?.takeIf { it.isNotBlank() }
            val listed = assets.list(orgId, siteId)
            val items =
                if (call.scopeFilterEnabled()) {
                    scopes.filterAllowed(orgId, call.userId(), listed)
                } else {
                    listed
                }
            call.respond(AssetList(items = items))
        }
        get("/assets/{id}") {
            val orgId = call.orgId()
            call.respond(assets.get(orgId, call.parameters["id"]!!))
        }
        patch("/assets/{id}") {
            val orgId = call.orgId()
            val req = call.receive<UpdateAssetRequest>()
            call.respond(assets.update(orgId, call.parameters["id"]!!, req))
        }
        post("/assets/{id}/move") {
            val orgId = call.orgId()
            val req = call.receive<MoveAssetRequest>()
            if (!sites.exists(orgId, req.siteId)) {
                throw IllegalArgumentException("Unknown siteId")
            }
            call.respond(assets.move(orgId, call.parameters["id"]!!, req.siteId))
        }
        post("/assets/{id}/confirm") {
            val orgId = call.orgId()
            call.respond(assets.confirm(orgId, call.parameters["id"]!!))
        }
        post("/assets/{id}/reject") {
            val orgId = call.orgId()
            assets.reject(orgId, call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/assets/{id}") {
            val orgId = call.orgId()
            assets.delete(orgId, call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/assets/{id}/unlink-documents") {
            val orgId = call.orgId()
            val req = call.receive<UnlinkDocumentsRequest>()
            call.respond(assets.unlinkDocumentIds(orgId, call.parameters["id"]!!, req.documentIds))
        }

        get("/user-scopes/candidates/{assetId}") {
            val orgId = call.orgId()
            val assetId = call.parameters["assetId"]!!
            val asset = assets.get(orgId, assetId)
            call.respond(ScopeCandidatesResponse(userIds = scopes.candidates(orgId, asset)))
        }
        get("/user-scopes/{userId}") {
            val orgId = call.orgId()
            call.respond(scopes.get(orgId, call.parameters["userId"]!!))
        }
        put("/user-scopes/{userId}") {
            val orgId = call.orgId()
            val req = call.receive<PutUserScopeRequest>()
            call.respond(scopes.put(orgId, call.parameters["userId"]!!, req))
        }
        get("/user-scopes/{userId}/covers/{assetId}") {
            val orgId = call.orgId()
            val userId = call.parameters["userId"]!!
            val assetId = call.parameters["assetId"]!!
            val asset = assets.get(orgId, assetId)
            call.respond(CoversResponse(covers = scopes.covers(orgId, userId, asset)))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.orgId(): String =
    request.header("X-Org-Id")?.takeIf { it.isNotBlank() } ?: "default-org"

private fun io.ktor.server.application.ApplicationCall.userId(): String =
    request.header("X-User-Id")?.takeIf { it.isNotBlank() } ?: "unknown"

private fun io.ktor.server.application.ApplicationCall.scopeFilterEnabled(): Boolean {
    val value = request.header("X-Scope-Filter")?.trim()?.lowercase() ?: return false
    return value == "1" || value == "true"
}

class ConflictException(message: String) : RuntimeException(message)

@Serializable
enum class RecordStatus { draft, active }

@Serializable
enum class RecordSource { manual, ai_generated }

@Serializable
data class Site(
    val id: String,
    val orgId: String,
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val geofenceRadiusM: Int? = null,
)

@Serializable
data class CreateSiteRequest(
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val geofenceRadiusM: Int? = null,
    val id: String? = null,
)

@Serializable
data class UpdateSiteRequest(
    val name: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val geofenceRadiusM: Int? = null,
)

@Serializable
data class SiteList(val items: List<Site>)

@Serializable
data class Asset(
    val id: String,
    val orgId: String,
    val siteId: String,
    val name: String,
    val inventoryNo: String? = null,
    val category: String? = null,
    val description: String? = null,
    val status: RecordStatus,
    val source: RecordSource,
    val documentIds: List<String> = emptyList(),
    val qrToken: String? = null,
)

@Serializable
data class CreateAssetRequest(
    val name: String,
    val siteId: String,
    val inventoryNo: String? = null,
    val category: String? = null,
    val description: String? = null,
    val documentIds: List<String> = emptyList(),
    val source: RecordSource = RecordSource.manual,
    val asDraft: Boolean = true,
)

@Serializable
data class UpdateAssetRequest(
    val name: String? = null,
    val inventoryNo: String? = null,
    val category: String? = null,
    val description: String? = null,
    val documentIds: List<String>? = null,
)

@Serializable
data class MoveAssetRequest(val siteId: String)

@Serializable
data class UnlinkDocumentsRequest(val documentIds: List<String>)

@Serializable
data class AssetList(val items: List<Asset>)

@Serializable
data class UserScope(
    val userId: String,
    val orgId: String,
    val siteIds: List<String> = emptyList(),
    val assetIds: List<String> = emptyList(),
)

@Serializable
data class PutUserScopeRequest(
    val siteIds: List<String> = emptyList(),
    val assetIds: List<String> = emptyList(),
)

@Serializable
data class CoversResponse(val covers: Boolean)

@Serializable
data class ScopeCandidatesResponse(val userIds: List<String>)

