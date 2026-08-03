# Asset create requires document Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject `POST /assets` when `documentIds` is missing or empty with HTTP 400 `document required`.

**Architecture:** Add one `require` in `JdbcAssetStore.create` (same pattern as name/siteId/`at most one document`). StatusPages already map `IllegalArgumentException` → 400. Update tests/helpers that create bare assets. No PATCH/unlink changes.

**Tech Stack:** Kotlin, Ktor 3, JUnit/kotlin.test, Testcontainers PostgreSQL, Gradle.

## Global Constraints

- Scope is **create only** — do not change `update` / `unlinkDocumentIds` / `confirm`.
- Error message must be exactly `document required` (plain text 400 body).
- Existing rules stay: at most one document; document already bound.
- Do not run full local Gradle suite unless needed for TDD on a single test class; prefer push → CI after commits (repo convention). Targeted `./gradlew test --tests …` is OK for red/green.
- UI copy rules N/A (API error string is internal/API, not user-facing names).

---

## File map

| File | Role |
|------|------|
| `src/main/kotlin/pro/masterdoc/catalog/JdbcAssetStore.kt` | Add `require(req.documentIds.isNotEmpty())` in `create` |
| `src/test/kotlin/pro/masterdoc/catalog/AssetRoutesTest.kt` | New reject tests + fix creates without docs |
| `src/test/kotlin/pro/masterdoc/catalog/AssetPersistenceTest.kt` | Pass `documentIds` into every `CreateAssetRequest` |
| `src/test/kotlin/pro/masterdoc/catalog/AssetQrRoutesTest.kt` | `createAsset` helper includes `documentIds` |
| `src/test/kotlin/pro/masterdoc/catalog/ScopeRoutesTest.kt` | Same helper fix |
| `src/test/kotlin/pro/masterdoc/catalog/ScopeFilterAssetsTest.kt` | Same helper fix |
| `src/test/kotlin/pro/masterdoc/catalog/SiteRoutesTest.kt` | Any bare `POST /assets` needs a document id |

---

### Task 1: Reject create without document (TDD)

**Files:**
- Modify: `src/main/kotlin/pro/masterdoc/catalog/JdbcAssetStore.kt` (`create`, after name/siteId requires)
- Modify: `src/test/kotlin/pro/masterdoc/catalog/AssetRoutesTest.kt`
- Test: `AssetRoutesTest`

**Interfaces:**
- Consumes: existing `CreateAssetRequest.documentIds: List<String>` (default `emptyList()`)
- Produces: `create` throws `IllegalArgumentException("document required")` when list empty

- [ ] **Step 1: Write the failing tests**

Add to `AssetRoutesTest.kt`:

```kotlin
@Test
fun createRejectsMissingDocumentIds() = withApplication {
    client.ensureSite("org-1")
    val create =
        client.post("/assets") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Компрессор","siteId":"site-1"}""")
        }
    assertEquals(HttpStatusCode.BadRequest, create.status)
    assertTrue(create.bodyAsText().contains("document required"))
}

@Test
fun createRejectsEmptyDocumentIds() = withApplication {
    client.ensureSite("org-1")
    val create =
        client.post("/assets") {
            header("X-Org-Id", "org-1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Компрессор","siteId":"site-1","documentIds":[]}""")
        }
    assertEquals(HttpStatusCode.BadRequest, create.status)
    assertTrue(create.bodyAsText().contains("document required"))
}
```

- [ ] **Step 2: Run tests to verify they fail for the right reason**

Run (from `catalog-service/`):

```bash
./gradlew test --tests 'pro.masterdoc.catalog.AssetRoutesTest.createRejectsMissingDocumentIds' --tests 'pro.masterdoc.catalog.AssetRoutesTest.createRejectsEmptyDocumentIds'
```

Expected: FAIL — create returns **201 Created** (or assertion fails on status), because empty `documentIds` is still allowed.

- [ ] **Step 3: Minimal implementation**

In `JdbcAssetStore.create`, immediately after `require(req.siteId.isNotBlank())` and **before** `require(req.documentIds.size <= 1)`:

```kotlin
require(req.documentIds.isNotEmpty()) { "document required" }
```

- [ ] **Step 4: Run the two new tests — expect PASS**

Same gradle command as Step 2. Expected: both PASS.

- [ ] **Step 5: Fix bare creates inside `AssetRoutesTest` so the class stays green**

Every `POST /assets` body that omits `documentIds` (and is expected to succeed) must include a **unique** id, e.g. `"documentIds":["doc-…"]`.

Known spots (grep `setBody` without `documentIds` in this file):

| Test / lines (approx) | Fix |
|----------------------|-----|
| `confirmNonDraftFails` | add `"documentIds":["doc-a"]` |
| `listScopedByOrg` | org-a → `doc-a`, org-b → `doc-b` |
| `rejectDraftSucceeds` | add `"documentIds":["doc-draft"]` |
| `rejectActiveFails` | add `"documentIds":["doc-active"]` |
| `patchRejectsDocumentAlreadyBoundToOtherAsset` | Second create: `"documentIds":["doc-second"]` (must not be `doc-shared`) |
| `getOtherOrgAssetNotFound` | add `"documentIds":["doc-secret"]` |
| AI draft/active cases without docs | add unique `documentIds` |
| patch description / name cases that create without docs | add unique `documentIds` |
| `move` / pump cases without docs | add unique `documentIds` |

`createWithUnknownSiteFails` can stay without docs if the route still rejects unknown `siteId` **before** `assets.create` — keep that order; no change required unless the test starts hitting `document required` first (then add a doc id so the assertion stays `Unknown siteId`).

Do **not** change successful unlink/patch assertions that intentionally clear or replace documents after create.

- [ ] **Step 6: Run full `AssetRoutesTest`**

```bash
./gradlew test --tests 'pro.masterdoc.catalog.AssetRoutesTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/catalog/JdbcAssetStore.kt \
  src/test/kotlin/pro/masterdoc/catalog/AssetRoutesTest.kt
git commit -m "$(cat <<'EOF'
feat(assets): reject create without document

EOF
)"
```

---

### Task 2: Fix remaining create call sites

**Files:**
- Modify: `src/test/kotlin/pro/masterdoc/catalog/AssetPersistenceTest.kt`
- Modify: `src/test/kotlin/pro/masterdoc/catalog/AssetQrRoutesTest.kt`
- Modify: `src/test/kotlin/pro/masterdoc/catalog/ScopeRoutesTest.kt`
- Modify: `src/test/kotlin/pro/masterdoc/catalog/ScopeFilterAssetsTest.kt`
- Modify: `src/test/kotlin/pro/masterdoc/catalog/SiteRoutesTest.kt` (if it POSTs assets without docs)

**Interfaces:**
- Consumes: Task 1 validation (`documentIds` must be non-empty on create)
- Produces: all test helpers create assets with at least one document id

- [ ] **Step 1: Persistence — add `documentIds` to every `CreateAssetRequest`**

Use unique ids per asset in the same org when multiple assets share one test, e.g.:

```kotlin
CreateAssetRequest(
    name = "Компрессор",
    siteId = site.id,
    documentIds = listOf("doc-persist-1"),
)
```

For `asDraft = false` cases, keep `asDraft = false` and still pass `documentIds`.

Multiple assets in one test (e.g. Насос 1/2/3): use `doc-pump-1`, `doc-pump-2`, `doc-pump-3`.

- [ ] **Step 2: QR / Scope helpers — include documentIds**

Prefer a unique id via UUID in the helper:

```kotlin
private suspend fun io.ktor.client.HttpClient.createAsset(
    orgId: String,
    siteId: String,
    name: String = "Asset",
    active: Boolean,
    documentId: String = "doc-${java.util.UUID.randomUUID()}",
): Asset {
    val response = post("/assets") {
        header("X-Org-Id", orgId)
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name","siteId":"$siteId","asDraft":${!active},"documentIds":["$documentId"]}""")
    }
    return json.decodeFromString(response.bodyAsText())
}
```

Apply the same pattern to `ScopeRoutesTest` and `ScopeFilterAssetsTest` helpers (`asDraft`:false cases keep their current draft flag and add `documentIds`).

- [ ] **Step 3: `SiteRoutesTest` — any bare asset create**

If a test POSTs `/assets` without `documentIds`, add one (unique per asset).

- [ ] **Step 4: Run affected test classes**

```bash
./gradlew test --tests 'pro.masterdoc.catalog.AssetPersistenceTest' \
  --tests 'pro.masterdoc.catalog.AssetQrRoutesTest' \
  --tests 'pro.masterdoc.catalog.ScopeRoutesTest' \
  --tests 'pro.masterdoc.catalog.ScopeFilterAssetsTest' \
  --tests 'pro.masterdoc.catalog.SiteRoutesTest'
```

Expected: PASS.

- [ ] **Step 5: Commit + push**

```bash
git add src/test/kotlin/pro/masterdoc/catalog/AssetPersistenceTest.kt \
  src/test/kotlin/pro/masterdoc/catalog/AssetQrRoutesTest.kt \
  src/test/kotlin/pro/masterdoc/catalog/ScopeRoutesTest.kt \
  src/test/kotlin/pro/masterdoc/catalog/ScopeFilterAssetsTest.kt \
  src/test/kotlin/pro/masterdoc/catalog/SiteRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(assets): pass documentIds on create helpers

EOF
)"
git push origin HEAD
```

Then `gh run watch` the triggered CI until success (deploy on main).

---

## Spec coverage check

| Spec requirement | Task |
|------------------|------|
| Empty/missing `documentIds` → 400 `document required` | Task 1 |
| Validation in `JdbcAssetStore.create` | Task 1 |
| PATCH/unlink unchanged | Task 1 (explicit non-touch) |
| Fix tests that create without docs | Task 1 + Task 2 |
| One document + existing bind rules still work | covered by existing tests after Task 1 fixes |

## Self-review

- No placeholders.
- Message string consistent: `document required`.
- Helpers use unique document ids to avoid `document already bound`.
