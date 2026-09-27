# ares-core

Ares ASM Backend

## Stack

- Java 21 (LTS), Maven via bundled wrapper (`./mvnw`).
- Spring Boot 3.5.7 — Web, Security, Actuator, Validation, OAuth2 Resource Server.
- Persistence:
    - PostgreSQL via Spring Data JPA + HikariCP (migrations: Flyway). MongoDB was used for
      assets/detections/imports/knowledge-base before the Postgres+AQL migration; it's no longer
      part of the stack.
    - Redis via Spring Data Redis + Redisson (distributed locks, Streams as job queue).
- Storage: AWS SDK v2 S3 client (works with MinIO).
- Auth: JWT (HS256) — local + LDAP + OAuth2; TOTP for 2FA.
- API docs: springdoc-openapi (OpenAPI 3.1) served at `/v3/api-docs` and `/swagger-ui.html`.
- Logging: Log4j2.
- Tests: JUnit 5 + Testcontainers (PG).

## Layout

```
src/main/java/com/martecyber/ares/
├── AresApplication.java            Spring Boot entry point
├── config/                         Cross-cutting config (security, OpenAPI, datasources)
├── common/                         Shared utilities, errors, DTOs
├── auth/                           Login, refresh, TOTP, API tokens
├── users/                          PG-backed
├── organizations/                  PG-backed (client orgs)
├── teams/                          PG-backed
├── engagements/                    PG-backed
├── findings/                       PG-backed
├── permissions/                    PG-backed
├── audit/                          PG-backed audit log
├── licensing/                      PG-backed
├── assets/                         PG-backed
├── detections/                     PG-backed (deduplicated)
├── imports/                        PG-backed (raw payloads)
├── integrations/                   PG-backed (credentials + sync snapshots)
├── kb/                             PG-backed (CVE, EUVD, ATT&CK, CAPEC, OWASP, CWE)
├── files/                          MinIO/S3 + metadata in PG
├── jobs/                           Redis Streams queue + PG state
├── reporting/                      DOCX/PDF generation
└── health/                         Actuator extensions, /api/v1/ping
```

## Running

Requires a running Postgres, Redis, and S3-compatible store (e.g. MinIO) — point
`application-local.yml` at them, then:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,local
```

Endpoints once up:

- http://localhost:8888/actuator/health — liveness/readiness probes.
- http://localhost:8888/api/v1/ping — smoke test.
- http://localhost:8888/swagger-ui.html — interactive API docs.
- http://localhost:8888/v3/api-docs — OpenAPI JSON.

## Configuration

- Defaults: `src/main/resources/application.yml`.
- Profile overrides: `application-{dev,prod}.yml`.
- Local-only overrides (gitignored): `application-local.yml`. Activate with `SPRING_PROFILES_ACTIVE=dev,local`.

Key env vars (prod):

| Var | Purpose |
|---|---|
| `ARES_JWT_SECRET` | JWT signing secret (≥ 32 bytes) |
| `ARES_PG_URL` / `ARES_PG_USER` / `ARES_PG_PASSWORD` | Postgres |
| `ARES_REDIS_HOST` / `ARES_REDIS_PORT` / `ARES_REDIS_PASSWORD` | Redis |
| `ARES_S3_ENDPOINT` / `ARES_S3_ACCESS_KEY` / `ARES_S3_SECRET_KEY` | S3/MinIO |

## Tests

```bash
./mvnw test                 # unit
./mvnw verify               # + integration (spins up Testcontainers)
```

## Build

```bash
./mvnw -q package -DskipTests            # produces target/ares-core-<version>.jar
docker build -t ares/ares-core:dev .      # container image
```

## Migrations

Flyway runs on startup. SQL files: `src/main/resources/db/migration/V*__*.sql`.
Convention: `V<n>__<snake_case_description>.sql`. Never edit a shipped migration — add a new one.
