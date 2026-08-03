# Asset create requires document

**Date:** 2026-08-03  
**Repo:** catalog-service  
**Status:** approved design

## Goal

Equipment (`Asset`) can only be created when the request includes at least one document id. Bare `POST /assets` without documents must fail with HTTP 400.

Canon: document → asset (see Demo org seed / product rule). This change enforces that on create in REST.

## Scope

**In**

- `JdbcAssetStore.create` validation
- Route tests for the new 400 path
- Fix existing tests that create assets without `documentIds`

**Out**

- `PATCH /assets/{id}` clearing documents
- `POST /assets/{id}/unlink-documents`
- `confirm` / draft lifecycle changes
- Existence check against document-service
- DB migration / backfill of existing assets with empty `document_ids`

## Behavior

| Request | Result |
|---------|--------|
| `documentIds` missing (defaults to `[]`) | **400** `document required` |
| `documentIds: []` | **400** `document required` |
| `documentIds: ["doc-1"]` | existing rules (availability, at most one) |
| `documentIds: ["a","b"]` | **400** `at most one document` (unchanged) |

Error mapping: `require { … }` → `IllegalArgumentException` → existing StatusPages → **400** plain text body.

## Implementation

In `JdbcAssetStore.create`, after name/siteId checks:

```kotlin
require(req.documentIds.isNotEmpty()) { "document required" }
```

Keep existing:

- `require(req.documentIds.size <= 1) { "at most one document" }`
- `requireDocumentsAvailable(...)`

No DTO or route changes.

## Tests

- New `AssetRoutesTest`: create without documents / empty list → 400 + body contains `document required`
- Update helpers/tests that currently POST assets without `documentIds` (scope, QR, persistence, reject/confirm cases) to pass a single document id

## Success criteria

- Create without documents always 400
- Create with one unbound document still 201
- PATCH/unlink behavior unchanged
