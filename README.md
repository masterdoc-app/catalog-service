# catalog-service

Part of Equipment + Technologist (draft-only) epic. See `masterdoc/docs/superpowers/specs/2026-07-22-equipment-technologist-design.md`.

```bash
./gradlew test
./gradlew run
```

## Local configuration

- API port: `8091` (via `PORT` or compose)
- `DATABASE_URL`: defaults to
  `jdbc:postgresql://localhost:5432/catalog?user=catalog&password=catalog`

Start the API and PostgreSQL with:

```bash
cd deploy && docker compose up -d --build --wait
```

Compose runs `catalog-postgres` (Postgres 16) and `catalog-service`. The app
connects via `DATABASE_URL` pointing at `catalog-postgres`.

Integration tests use Testcontainers and are skipped when Docker is not installed.

## Deploy (VPS)

Pushes to `main` run tests and deploy Compose to `/opt/catalog-service`.

After the **first** Postgres deploy (empty database), seed demo equipment once:

```bash
gh workflow run seed-demo-assets.yml -f org_id=382715225649971203
```

Gateway reaches the service at `http://host.docker.internal:<port>`.
