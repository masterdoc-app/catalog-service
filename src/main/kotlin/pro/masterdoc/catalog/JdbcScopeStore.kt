package pro.masterdoc.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.postgresql.util.PGobject
import javax.sql.DataSource

class JdbcScopeStore(private val dataSource: DataSource) {
    private val json = Json

    fun get(orgId: String, userId: String): UserScope =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT user_id, org_id, site_ids, asset_ids FROM user_scopes WHERE org_id = ? AND user_id = ?").use { s ->
                s.setString(1, orgId)
                s.setString(2, userId)
                s.executeQuery().use { rs ->
                    if (!rs.next()) return@use UserScope(userId, orgId)
                    rs.toScope()
                }
            }
        }

    fun put(orgId: String, userId: String, req: PutUserScopeRequest): UserScope {
        val scope = UserScope(userId, orgId, req.siteIds.distinct(), req.assetIds.distinct())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO user_scopes (org_id, user_id, site_ids, asset_ids) VALUES (?, ?, ?, ?) ON CONFLICT (org_id, user_id) DO UPDATE SET site_ids = EXCLUDED.site_ids, asset_ids = EXCLUDED.asset_ids",
            ).use { s ->
                s.setString(1, orgId)
                s.setString(2, userId)
                s.setJson(3, scope.siteIds)
                s.setJson(4, scope.assetIds)
                s.executeUpdate()
            }
        }
        return scope
    }

    fun covers(orgId: String, userId: String, asset: Asset): Boolean {
        val scope = get(orgId, userId)
        return (scope.siteIds.isNotEmpty() || scope.assetIds.isNotEmpty()) &&
            (asset.id in scope.assetIds || asset.siteId in scope.siteIds)
    }

    fun candidates(orgId: String, asset: Asset): List<String> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT user_id, org_id, site_ids, asset_ids FROM user_scopes WHERE org_id = ? ORDER BY user_id").use { s ->
                s.setString(1, orgId)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val scope = rs.toScope()
                            if (scope.siteIds.contains(asset.siteId) || scope.assetIds.contains(asset.id)) add(scope.userId)
                        }
                    }
                }
            }
        }

    fun filterAllowed(orgId: String, userId: String, assets: List<Asset>): List<Asset> =
        assets.filter { covers(orgId, userId, it) }

    private fun java.sql.PreparedStatement.setJson(index: Int, value: List<String>) {
        setObject(index, PGobject().apply {
            type = "jsonb"
            this.value = json.encodeToString(ListSerializer(String.serializer()), value)
        })
    }

    private fun java.sql.ResultSet.toScope() = UserScope(
        userId = getString("user_id"),
        orgId = getString("org_id"),
        siteIds = json.decodeFromString(getString("site_ids")),
        assetIds = json.decodeFromString(getString("asset_ids")),
    )
}
