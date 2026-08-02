package pro.masterdoc.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.postgresql.util.PGobject
import java.util.UUID
import javax.sql.DataSource

private val jdbcJson = Json

class JdbcAssetStore(private val dataSource: DataSource) {
    fun create(orgId: String, req: CreateAssetRequest): Asset {
        require(req.name.isNotBlank()) { "name required" }
        require(req.siteId.isNotBlank()) { "siteId required" }
        require(req.documentIds.size <= 1) { "at most one document" }
        requireDocumentsAvailable(orgId, req.documentIds, null)
        val source = req.source
        val asset = Asset(
            id = UUID.randomUUID().toString(),
            orgId = orgId,
            siteId = req.siteId,
            name = req.name.trim(),
            inventoryNo = req.inventoryNo?.trim()?.takeIf { it.isNotEmpty() },
            category = req.category?.trim()?.takeIf { it.isNotEmpty() },
            description = req.description?.trim()?.takeIf { it.isNotEmpty() },
            status = if (req.asDraft || source == RecordSource.ai_generated) RecordStatus.draft else RecordStatus.active,
            source = source,
            documentIds = req.documentIds.distinct().take(1),
        )
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO assets (id, org_id, site_id, name, inventory_no, category, description, status, source, document_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { s ->
                s.setString(1, asset.id)
                s.setString(2, orgId)
                s.setString(3, asset.siteId)
                s.setString(4, asset.name)
                s.setString(5, asset.inventoryNo)
                s.setString(6, asset.category)
                s.setString(7, asset.description)
                s.setString(8, asset.status.name)
                s.setString(9, asset.source.name)
                s.setJson(10, asset.documentIds)
                s.executeUpdate()
            }
        }
        return asset
    }

    fun list(orgId: String, siteId: String? = null): List<Asset> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT * FROM assets WHERE org_id = ? AND (? IS NULL OR site_id = ?) ORDER BY name",
            ).use { s ->
                s.setString(1, orgId)
                s.setString(2, siteId)
                s.setString(3, siteId)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toAsset())
                    }
                }
            }
        }

    fun get(orgId: String, id: String): Asset =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT * FROM assets WHERE org_id = ? AND id = ?").use { s ->
                s.setString(1, orgId)
                s.setString(2, id)
                s.executeQuery().use { rs ->
                    if (!rs.next()) throw NoSuchElementException("Asset not found")
                    rs.toAsset()
                }
            }
        }

    fun update(orgId: String, id: String, req: UpdateAssetRequest): Asset {
        val current = get(orgId, id)
        req.documentIds?.let {
            require(it.size <= 1) { "at most one document" }
            requireDocumentsAvailable(orgId, it, id)
        }
        val updated = current.copy(
            name = req.name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
            inventoryNo = req.inventoryNo.patchNullable(current.inventoryNo),
            category = req.category.patchNullable(current.category),
            description = req.description.patchNullable(current.description),
            documentIds = req.documentIds?.distinct()?.take(1) ?: current.documentIds,
        )
        save(updated)
        return updated
    }

    fun move(orgId: String, id: String, siteId: String): Asset {
        require(siteId.isNotBlank()) { "siteId required" }
        val asset = get(orgId, id).copy(siteId = siteId)
        save(asset)
        return asset
    }

    fun confirm(orgId: String, id: String): Asset {
        val asset = get(orgId, id)
        require(asset.status == RecordStatus.draft) { "Only draft assets can be confirmed" }
        return asset.copy(status = RecordStatus.active).also(::save)
    }

    fun reject(orgId: String, id: String) {
        val asset = get(orgId, id)
        require(asset.status == RecordStatus.draft) { "Only draft assets can be rejected" }
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM assets WHERE org_id = ? AND id = ?").use { s ->
                s.setString(1, orgId)
                s.setString(2, id)
                s.executeUpdate()
            }
        }
    }

    fun delete(orgId: String, id: String) {
        get(orgId, id)
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM assets WHERE org_id = ? AND id = ?").use { s ->
                s.setString(1, orgId)
                s.setString(2, id)
                s.executeUpdate()
            }
        }
    }

    fun findByDocumentId(orgId: String, documentId: String, exceptAssetId: String? = null): Asset? =
        list(orgId).firstOrNull { it.id != exceptAssetId && documentId in it.documentIds }

    fun countOnSite(orgId: String, siteId: String): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM assets WHERE org_id = ? AND site_id = ?").use { s ->
                s.setString(1, orgId)
                s.setString(2, siteId)
                s.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun unlinkDocumentIds(orgId: String, id: String, documentIds: List<String>): Asset {
        require(documentIds.isNotEmpty()) { "documentIds required" }
        val asset = get(orgId, id)
        return asset.copy(documentIds = asset.documentIds.filter { it !in documentIds.toSet() }).also(::save)
    }

    fun exists(orgId: String, id: String): Boolean = runCatching { get(orgId, id) }.isSuccess

    private fun requireDocumentsAvailable(orgId: String, documentIds: List<String>, exceptAssetId: String?) {
        documentIds.forEach { documentId ->
            require(findByDocumentId(orgId, documentId, exceptAssetId) == null) {
                "document already bound to equipment"
            }
        }
    }

    private fun save(asset: Asset) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE assets SET site_id = ?, name = ?, inventory_no = ?, category = ?, description = ?, status = ?, source = ?, document_ids = ? WHERE org_id = ? AND id = ?",
            ).use { s ->
                s.setString(1, asset.siteId)
                s.setString(2, asset.name)
                s.setString(3, asset.inventoryNo)
                s.setString(4, asset.category)
                s.setString(5, asset.description)
                s.setString(6, asset.status.name)
                s.setString(7, asset.source.name)
                s.setJson(8, asset.documentIds)
                s.setString(9, asset.orgId)
                s.setString(10, asset.id)
                s.executeUpdate()
            }
        }
    }

    private fun java.sql.PreparedStatement.setJson(index: Int, value: List<String>) {
        setObject(index, PGobject().apply {
            type = "jsonb"
            this.value = jdbcJson.encodeToString(ListSerializer(String.serializer()), value)
        })
    }

    private fun java.sql.ResultSet.toAsset() = Asset(
        id = getString("id"),
        orgId = getString("org_id"),
        siteId = getString("site_id"),
        name = getString("name"),
        inventoryNo = getString("inventory_no"),
        category = getString("category"),
        description = getString("description"),
        status = RecordStatus.valueOf(getString("status")),
        source = RecordSource.valueOf(getString("source")),
        documentIds = jdbcJson.decodeFromString(getString("document_ids")),
    )
}

private fun String?.patchNullable(current: String?): String? = when {
    this == null -> current
    isBlank() -> null
    else -> trim()
}
