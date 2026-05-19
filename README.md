# Fax Trident

Spring Boot REST + WebSocket fax server and a JavaFX desktop client, in two Maven modules sharing a parent reactor (see `docs/adr/0001-decouple-javafx-from-spring-boot.md`). The desktop is Spring-free and talks to the server via the same REST + WebSocket surface any other client uses. Stateless JWT auth with a Redis-backed JTI allowlist for logout-aware tokens, Redis-backed rate limiting (per-user for authenticated endpoints, per-IP for login), Flyway-managed schema, PDFBox for PDF text extraction and ZXing for barcode generation, and a get-or-create contact model that survives concurrent unique-constraint races.

This README is meant for both operators (who want it deployed) and developers (who want to extend it). The quickstart is up front; architecture and developer notes follow.

---

## Quickstart

### Prerequisites

- **JDK 21** (`<java.version>21</java.version>` in `pom.xml`).
- **Maven 3.9+**.
- **Redis 6+** reachable on `localhost:6379` for local dev. Used for the JWT JTI allowlist, rate-limit counters, suggestion cache, and per-fax status keys.
- **PostgreSQL** for production. Dev runs on in-memory H2 so you can skip this until you switch profiles.

### Required environment

`JWT_SECRET` is the only secret the app refuses to start without. There is no default — the previous `your-secret-key` default was a CVE waiting to happen.

```sh
export JWT_SECRET="$(openssl rand -base64 48)"      # >= 32 bytes required for HS256
```

### Build and run (dev)

The repo is a Maven reactor with two modules:

| Module | Artifact | Purpose |
|---|---|---|
| `fax-trident-server` | `fax-trident-1.0.0-SNAPSHOT.jar` (Spring Boot fat jar) | REST + WebSocket service. |
| `fax-trident-desktop` | `fax-trident-desktop-1.0.0-SNAPSHOT.jar` | JavaFX client. Talks to the server over HTTP + WS. Spring-free. |

To build everything from the repo root:

```sh
mvn clean package
```

Then run the server:

```sh
java -jar fax-trident-server/target/fax-trident-1.0.0-SNAPSHOT.jar
```

The server listens on `:8080`. Flyway runs `db/migration/V1__initial_schema.sql` against the in-memory H2 on startup, then Hibernate validates the schema against the entity model. Server-only deploys (Docker, k8s) do not pull in JavaFX at all — the server module declares no openjfx dependency.

In a second terminal, run the desktop client:

```sh
mvn -pl fax-trident-desktop javafx:run
# or
java -jar fax-trident-desktop/target/fax-trident-desktop-1.0.0-SNAPSHOT.jar
```

A login dialog appears first. Enter the server URL (default `http://localhost:8080`), username, and password — on success the client mints a JWT via `POST /api/auth/login` and the main view opens. Per-machine preferences (server URL, last username, theme) are persisted under `${user.home}/.fax-trident/preferences.properties`.

To build only the server (no JavaFX deps fetched):

```sh
mvn -pl fax-trident-server -am package
```

### Build and run (production profile)

```sh
SPRING_PROFILES_ACTIVE=prod \
JWT_SECRET=... \
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/faxtrident \
SPRING_DATASOURCE_USERNAME=trident \
SPRING_DATASOURCE_PASSWORD=... \
SPRING_DATA_REDIS_HOST=... \
app_websocket_allowed-origins=https://app.example.com \
java -jar target/fax-trident-1.0.0-SNAPSHOT.jar
```

`application-prod.yml` overrides `show-sql` and `format_sql` to `false`. `ddl-auto: validate` lives in `application.yml` (not the prod overlay) because Flyway owns the schema in every environment now — Hibernate just checks the entity model matches what Flyway has applied. For existing prod databases that pre-date the Flyway adoption, `spring.flyway.baseline-on-migrate: true` inserts a baseline row at V1 on first startup and skips `V1__initial_schema.sql`.

### Docker

The included `Dockerfile` is multi-stage (Maven build, JRE runtime) and sets `SPRING_PROFILES_ACTIVE=prod` by default. The runtime base image is `eclipse-temurin:21-jre-jammy` — Ubuntu rather than Alpine because PDFBox needs fontconfig + a baseline of system fonts to render reliably. No secrets are baked into the image — inject at runtime:

```sh
docker build -t fax-trident .
docker run \
  -e JWT_SECRET=... \
  -e SPRING_DATASOURCE_PASSWORD=... \
  -e app_websocket_allowed-origins=https://app.example.com \
  -p 8080:8080 fax-trident
```

---

## Configuration

| Property / Env var | Required | Default | Notes |
|---|---|---|---|
| `jwt.secret` / `JWT_SECRET` | yes | — (fail-fast) | HS256 signing key, ≥ 32 bytes. `openssl rand -base64 48`. |
| `jwt.validity` | no | `3600000` (1h) | Token TTL in ms. Same TTL applied to the JTI allowlist entry. |
| `spring.datasource.*` | dev: no, prod: yes | H2 in-memory | Move to Postgres in prod via env (`SPRING_DATASOURCE_URL`, etc.). |
| `spring.data.redis.host` / `SPRING_DATA_REDIS_HOST` | no | `localhost` | Same for `port`, `timeout`, `database`. |
| `spring.flyway.*` | no | enabled, baseline-on-migrate, baseline-version=1 | See [`application.yml`](src/main/resources/application.yml). Migrations live in `src/main/resources/db/migration/`. |
| `app.upload.dir` | no | `./uploads/` | Server-controlled directory for uploaded fax PDFs. Set to an **absolute path** in production. |
| `app.websocket.allowed-origins` | dev: no, prod: yes | `http://localhost:8080` (dev only) | Comma-separated. Prod profile fails fast if unset. |
| `app.websocket.client-url` | no | `ws://localhost:8080/fax-updates` | URL the desktop client connects to. |
| `server.forward-headers-strategy` | no | unset | Set to `native` (or `framework`) when behind a trusted reverse proxy so per-IP rate limiting on `/api/auth/login` keys on the real client, not the proxy. |
| `app.sound.startup` | no | `/sounds/trident-rise.wav` | Played on JavaFX boot. Shipped wav is a 3-byte placeholder. |

---

## Architecture

```
+----- fax-trident-desktop JVM ------+        +----- fax-trident-server JVM -----+
|                                    |        |                                  |
|  FaxTridentDesktop (JavaFX main)   |        |  FaxTridentServer (Spring Boot)  |
|       │                            |        |                                  |
|       ├─ LoginDialog ──────────────+──HTTP──+→  POST /api/auth/login → JWT     |
|       │                            |        |                                  |
|       ├─ FaxApiClient ─────────────+──HTTP──+→  /api/fax/uploads (multipart)   |
|       │   (JDK java.net.http)      |        |    /api/fax/send                 |
|       │                            |        |    (bearer JWT on every call)    |
|       │                            |        |                                  |
|       ├─ FaxUpdateClient ──────────+──WS────+→  /fax-updates broadcast         |
|       │   (org.java-websocket)     |        |                                  |
|       │                            |        |  AuthController, FaxController,  |
|       ├─ MainView                  |        |  AdminController                 |
|       ├─ PreviewPane (PDFBox)      |        |  FaxEngineService                |
|       ├─ ThemeManager              |        |  ContactSuggestionService        |
|       └─ DesktopPreferences        |        |  FaxUploadService                |
|         (~/.fax-trident/...)       |        |  PdfProcessingService            |
|                                    |        |  RateLimitAspect                 |
|  No Spring on this side.           |        |  SecurityConfig (stateless JWT)  |
|                                    |        |    │       │       │       │     |
+------------------------------------+        |    v       v       v       v     |
                                              |   JPA    Redis    WS    PDFBox/  |
                                              |  (Flyway- (Lettuce)     ZXing    |
                                              |   managed)                       |
                                              +----------------------------------+
```

The desktop and the server are independent processes. Previously they shared one JVM and the desktop UI `@Autowired` server beans directly; that coupling was removed in ADR-0001 (`docs/adr/0001-decouple-javafx-from-spring-boot.md`). The desktop now authenticates over `POST /api/auth/login` and treats the server like any other API consumer.

The WebSocket client (`FaxUpdateClient`) is owned by the desktop. Lifecycle is explicit — created by `FaxTridentDesktop.start(Stage)` after login succeeds, closed by `FaxTridentDesktop.stop()`. Reconnect uses exponential backoff capped at 30s.

The PDF preview pane (`PreviewPane`) still uses PDFBox, but locally on the desktop's own copy of the file — no network call. After upload + send succeed, status updates come back via the WebSocket broadcast.

### Auth model

Stateless JWT only. There is one path in and one path out.

- **Login**: `POST /api/auth/login` with `{"username":"...","password":"..."}` returns `{"token":"..."}`. The endpoint is rate-limited per client IP (10/min) so brute-force scripts get 429s after the first 10 misses.
- **Authenticated calls**: every protected endpoint expects `Authorization: Bearer <token>`. The JWT filter validates the signature, looks up the `jti` in the Redis allowlist, and populates `SecurityContext`. A revoked or expired `jti` is rejected even if the signature is valid.
- **Logout**: `POST /logout` with the same `Authorization` header removes the `jti` from Redis. Subsequent uses of the token return 401.

No HTTP sessions, no `JSESSIONID`, no CSRF filter, no form-login page. CSRF doesn't apply because there's no ambient cookie for an attacker to ride — JWTs travel only in the `Authorization` header, which a cross-site form can't set.

If you reintroduce OAuth2 later, it should mint a JWT on the OAuth callback rather than create a session, to keep the model uniform.

### Rate limiting

`@RateLimit(key = "...", rate = N, period = SECONDS)` on any controller method. `RateLimitAspect` is a Spring AOP `@Around` that uses a Redis fixed-window counter — at-most-`rate` calls per `period` seconds per resolved SpEL key. Exceeded calls throw `RateLimitExceededException`, which Spring maps to HTTP 429.

SpEL variables available in the key template:

- `#authentication` — the current Spring Security `Authentication`. Null for unauthenticated callers.
- `#request` — the current `HttpServletRequest`.
- `#ipAddress` — convenience for `request.getRemoteAddr()`. Use for unauthenticated endpoints; combine with `server.forward-headers-strategy: native` if behind a reverse proxy.
- Any controller-method parameter, by name.

Two patterns:

```java
// Authenticated, per-user
@RateLimit(key = "fax:send:#{authentication.name}", rate = 10, period = 60)
public ResponseEntity<?> send(...) { ... }

// Unauthenticated, per-IP (brute-force protection)
@RateLimit(key = "auth:login:#{#ipAddress}", rate = 10, period = 60)
public ResponseEntity<?> login(...) { ... }
```

### Schema migrations

Flyway owns the schema. `src/main/resources/db/migration/V1__initial_schema.sql` is the baseline; new changes go in `V2__<desc>.sql`, `V3__<desc>.sql`, etc. Both dev (H2) and prod (Postgres) run the same migrations on boot, then Hibernate validates the entity model against the resulting schema. Drift fails fast at startup instead of silently mis-applying writes.

For existing prod databases that pre-date Flyway, `spring.flyway.baseline-on-migrate: true` inserts a baseline row at V1 and skips `V1__initial_schema.sql` — V2+ run normally.

---

## Project layout

```
pom.xml                                 parent reactor (packaging=pom)
docs/adr/
└── 0001-decouple-javafx-from-spring-boot.md   the split decision

fax-trident-server/
├── pom.xml
└── src/main/java/com/xai/trident
    ├── FaxTridentServer.java          Spring Boot entrypoint (no JavaFX)
    ├── config/
    │   ├── SecurityConfig.java        Stateless JWT, JWT filter + provider, role mapping
    │   ├── RedisConfig.java           RedisTemplate beans
    │   ├── WebSocketConfig.java       /fax-updates handler
    │   └── AsyncConfig.java           DelegatingSecurityContextAsyncTaskExecutor
    ├── controller/
    │   ├── AuthController.java        POST /api/auth/login (per-IP rate-limited)
    │   ├── FaxController.java         /api/fax/**
    │   └── AdminController.java       /api/admin/**
    ├── service/
    │   ├── FaxEngineService.java          sendFax, processInput, findOrCreateContact
    │   ├── PdfProcessingService.java      text extraction + QR barcode generation
    │   ├── ContactSuggestionService.java  heuristic contact suggestion + auto-send
    │   └── InboundFaxSimulator.java       @Profile("dev") @Scheduled trigger
    ├── upload/
    │   ├── FaxUploadService.java          multipart store, UUID + chroot resolve
    │   └── UploadExceptionHandler.java    400 / 404 / 413 mapping
    ├── ratelimit/
    │   ├── RateLimit.java                 the @interface
    │   └── RateLimitAspect.java           @Around advice; #authentication, #request, #ipAddress
    ├── model/
    │   ├── Contact.java, FaxLog.java, FaxMetadata.java
    │   └── User.java                      Spring Security user record
    ├── repository/
    │   ├── ContactRepository.java         incl. findByFaxNumberContaining for ContactSuggestion
    │   ├── FaxLogRepository.java          incl. countByFaxNumber
    │   ├── FaxMetadataRepository.java     incl. findTotalPageCount / findTotalFileSize
    │   └── UserRepository.java
    └── util/
        └── LogSanitizer.java              CR/LF/TAB escape for log lines

fax-trident-server/src/main/resources
├── application.yml                    dev defaults; jwt, datasource, jpa, flyway, redis, app
├── application-prod.yml               prod overrides (SQL logging off)
└── db/migration/
    └── V1__initial_schema.sql         Flyway baseline

fax-trident-desktop/
├── pom.xml
└── src/main/java/com/xai/trident/desktop
    ├── FaxTridentDesktop.java         JavaFX main; wires up the client manually (no Spring)
    ├── client/
    │   ├── FaxApiClient.java          REST client around java.net.http.HttpClient
    │   └── RetryHelper.java           replaces Spring Retry's @Retryable on the desktop side
    ├── config/
    │   └── DesktopPreferences.java    ~/.fax-trident/preferences.properties (replaces Redis-backed theme)
    └── ui/
        ├── LoginDialog.java           credentials prompt; calls FaxApiClient.login
        ├── MainView.java              BorderPane; send-fax via FaxApiClient (upload + send)
        ├── PreviewPane.java           PDF rendering with explicit retry on a background executor
        ├── ThemeManager.java          mermaid / dark themes; persists via DesktopPreferences
        └── FaxUpdateClient.java       single shared WS client + listener registry

fax-trident-desktop/src/main/resources
├── css/                               mermaid-mode.css, dark-mode.css
└── sounds/                            startup + status sound placeholders
```

---

## API surface

All `/api/**` endpoints require `Authorization: Bearer <token>`. `/api/fax/**` requires `ROLE_USER`; `/api/admin/**` requires `ROLE_ADMIN`.

### Auth

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` → `{token}`. Rate-limited 10/min per IP. Returns 401 on bad credentials, 429 on exceeded rate. |
| POST | `/logout` | With bearer header → revokes the JWT's `jti`. Returns 204. |

### Fax

| Method | Path | Notes |
|---|---|---|
| POST | `/api/fax/uploads` | multipart upload → returns `uploadId`. 25 MiB cap. |
| POST | `/api/fax/send` | `{faxNumber, uploadId}` → async send. Rate-limited 10/min/user. |
| POST | `/api/fax/process-input` | `?uploadId=...` → extract text. |
| POST | `/api/fax/auto-send` | `?partialInput=...&uploadId=...` → suggest contact and send. Rate-limited 5/min/user. |
| GET | `/api/fax/status` | Redis ping for liveness. |
| GET | `/api/fax/status/{faxId}` | Per-fax status from Redis + DB. |
| GET | `/api/fax/logs/by-number/{faxNumber}` | Paged. |
| GET | `/api/fax/logs/recent?start=...&end=...` | Time-range. |
| GET | `/api/fax/predict-contact?partialInput=...` | Heuristic suggestion. URL kept for backwards-compat; backend is `ContactSuggestionService`. |
| GET | `/api/fax/contacts/search?name=...` | Paged. |
| GET | `/api/fax/metadata/{id}` | Single resource (404 if missing). |

Empty pages return **200 with empty content**, not 404 — 404 is reserved for "no resource at this URL".

### Admin

| Method | Path | Notes |
|---|---|---|
| GET | `/api/admin/dashboard` | Aggregate counts via SQL `SUM` / `COUNT`. |
| POST | `/api/admin/send-fax` | `?faxNumber=...&uploadId=...`. |
| GET | `/api/admin/fax-status/{faxId}` | Admin-level status view. |
| DELETE | `/api/admin/clear-cache` | Clears `fax_*` keys via SCAN. |
| GET | `/api/admin/contacts`, `/contacts/recent` | Paged. |
| GET | `/api/admin/logs/failed`, `/logs/analytics` | |
| GET | `/api/admin/websocket-stats`, `/fax-stats`, `/pdf-stats`, `/metadata/stats`, `/theme-stats`, `/prediction-analytics` | Diagnostics. `/prediction-analytics` URL retained; SCAN pattern is `suggest:*`. |
| DELETE | `/api/admin/cleanup-barcodes` | Deletes barcode PNGs in the configured dir. |

### Open (no auth)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | See above. |
| POST | `/logout` | See above. |
| GET | `/actuator/health`, `/actuator/info` | For k8s / Docker probes. |

---

## Development

### Running tests

```sh
mvn test
```

Current test surface (24 tests across 6 classes, server module; last green `mvn test` 2026-05-18):

| Class | Style | What it proves |
|---|---|---|
| `FaxEngineServiceTest` | `@SpringBootTest` with a minimal `TestConfig` | Happy-path service behavior — send-fax, process-input, save-contact, inbound-listener wiring. |
| `SchemaMigrationTest` | `@DataJpaTest` + H2 in PostgreSQL mode, Flyway V1 applied | Entity ↔ migration drift; catches the kind of issue audit 3.1b was. |
| `AuthControllerTest` | `@WebMvcTest` slice | `POST /api/auth/login` — happy path, bad creds → 401, validation failures → 400. |
| `AdminControllerTest` | `@WebMvcTest` slice | Role gating on `/api/admin/**` — anonymous→401, USER→403, ADMIN→200. |
| `FaxControllerTest` | `@WebMvcTest` slice | `/api/fax/**` happy paths + audit-regression checks (1.5 upload, 2.16 empty-page-not-404). |
| `JwtSecurityIntegrationTest` | Minimal `@SpringBootTest` (no DB / Redis / Flyway autoconfig) | End-to-end JWT filter behavior — missing header, valid token, revoked jti (1.6), forged signature (1.1), expired token. |

`AUDIT.md` §4 has the remaining test plan: repository slice tests per `@Query`, unit tests for `JwtTokenProvider` and `PdfProcessingService`, Testcontainers integration against real Postgres + Redis, a deterministic replacement for the flaky `testListenForInboundFax`, and TestFX coverage for the desktop module.

### Adding an endpoint

1. Add the controller method.
2. If it touches the DB, add `@Transactional(readOnly = true)` or `@Transactional` as appropriate. If it doesn't, leave the annotation off — there's a one-line comment style for documenting "intentionally not transactional" already used throughout the controllers.
3. If it's user-mutating or an unauthenticated surface, give it a `@RateLimit` and let the AOP aspect handle 429s.
4. Sanitize any user-controlled string before logging it through `LogSanitizer.sanitize(...)`.

### Adding a schema change

1. Create `src/main/resources/db/migration/V<n>__<short_description>.sql` (one-up from the highest existing version).
2. Add / change entity fields to match.
3. `mvn test` and a local startup verify Flyway applies the migration and Hibernate `validate` is happy.
4. Production picks up the migration on next deploy — no operator SQL needed.

### Adding a Redis-backed feature

`RedisConfig` registers `RedisTemplate<String, Object>` with a string serializer for keys. For pattern lookups use `SCAN` via `redisTemplate.scan(...)` inside a try-with-resources — `KEYS` is O(N) and blocks the server.

### Profiles

- **default** — dev. H2 in-memory, Flyway runs against it, allowed-origins defaults to `localhost:8080`, verbose SQL logging.
- **prod** — `application-prod.yml`. SQL logging off; everything else inherits from the default. `app.websocket.allowed-origins` must be set.
- **dev** — explicitly activates the `InboundFaxSimulator` `@Scheduled` trigger that fakes inbound faxes. Production never runs it.

---

## Hardening summary

The codebase has been through a full security + correctness audit. The major changes (all closed; see `AUDIT.md` for the full trail):

- **Auth: stateless JWT only.** Form login, sessions, and CSRF are gone. The single entry point is `POST /api/auth/login` (JSON), returning a bearer token. Logout revokes the token's `jti` from the Redis allowlist. The hybrid model the audit found (form + JWT + sessions) created the original CSRF and session findings; eliminating the surface eliminates the class of bug.
- **JWT secret is required.** No default. Secrets shorter than 32 bytes fail startup. Tokens carry a `jti` registered in Redis with the same TTL — logout revocation is real, not advisory.
- **Real rate limiting, per-user AND per-IP.** `@RateLimit` is a working AOP-backed annotation, not the no-op `@interface` it used to be. The aspect exposes `#authentication`, `#request`, and `#ipAddress` so both authenticated and anonymous endpoints can throttle. `/api/auth/login` is per-IP-rate-limited as brute-force protection.
- **Multipart upload API.** Old `filePath` request parameters that let a caller name any file on disk are gone. `POST /api/fax/uploads` returns an opaque `uploadId`; the server stores under `app.upload.dir`, validates PDF magic bytes + size, and chroots the resolve.
- **Flyway-managed schema.** `V1__initial_schema.sql` baselines the entire current schema. `ddl-auto: validate` in every environment. Schema drift fails fast at startup.
- **Spring Boot 3.5.14** (was 3.2.4, EOL). Catches CVE-2026-22731 / CVE-2026-22733 (authentication bypass) and the 2025-era Framework CVEs.
- **`@Async` propagates `SecurityContext`** via `DelegatingSecurityContextAsyncTaskExecutor` — audit-trail `createdBy` reflects the actual user.
- **UUID fax IDs.** `System.currentTimeMillis()` IDs no longer collide under concurrency.
- **Contact get-or-create centralized.** One entry point in `FaxEngineService.findOrCreateContact(...)`. Concurrent unique-constraint races propagate as `DataIntegrityViolationException` and the `@Retryable` on `sendFaxAsync` retries.
- **Aggregations in SQL, not in the JVM.** `findAll().stream().mapToInt(...).sum()` patterns replaced with `SUM` / `COUNT` queries. Contact-suggestion substring search is paginated and capped.
- **Resource leaks closed.** `Files.list(...)` sites are all in try-with-resources. Spring Retry chains (`@Retryable` / `@Recover`) actually fire — the old code wrapped checked exceptions before the proxy could see them.
- **Inbound-fax simulator profile-gated.** `Math.random() > 0.8` no longer manufactures fake contacts in production — the `@Scheduled` trigger is `@Profile("dev")`.
- **Honest naming.** `SmartAssistService` → `ContactSuggestionService`. The class never had a model; the name implied otherwise.
- **Unique index names.** `idx_fax_number` collided between `contacts` and `fax_logs` (Hibernate/Postgres/H2 use a single global namespace), silently leaving `fax_logs.faxNumber` unindexed. Renamed to `idx_contact_fax_number` on `contacts`.
- **Structural cleanup.** `User` entity moved from `SecurityConfig` to `model/` + `repository/`. JavaFX FXML+programmatic duel resolved (programmatic-only). Single shared `FaxUpdateClient` replaces two `WebSocketClient`s. `application.yml` `datasource:` / `jpa:` / `data:` moved from `app:` to `spring:` where Spring auto-config actually reads them.
- **CI on JDK 21** (`.github/workflows/ci.yml`). Dockerfile runtime base is `21-jre-jammy` (was Alpine — musl + missing fontconfig was a silent risk for PDFBox).
- **`.gitignore`** covers `target/`, IDE folders, `.env*`, `*.pem`/`*.key`, and the runtime `uploads/` / `barcodes/` directories.

---

## Known follow-ups

Forward-looking work the audit identified but didn't itself cover:

- **Real ML behind `ContactSuggestionService`.** Today it's a deterministic score-based fuzzy matcher (`history*10 + name_contains*5 + fax_contains*3`). If you have a real model, the `suggestContact(...)` method is the right seam to plug it into — score against both and pick, or replace wholesale.
- **Single auth schema for roles.** `User.roles` is a comma-separated `VARCHAR`. A join table (`user_roles`) would let you query, index, and constrain roles properly.
- **Real test suite.** Two server-side tests today (`FaxEngineServiceTest`, `SchemaMigrationTest`). `AUDIT.md` §4 sketches the layered plan (security probes, controller slices, repo slices, Testcontainers integration). Highest-value remaining work.
- **WebSocket bearer auth.** `/fax-updates` accepts unauthenticated upgrades today. With the desktop now potentially connecting over an untrusted network, the WS endpoint should require a bearer token (see ADR-0001 follow-ups).
- **JWT persistence on the desktop.** In-memory only; users re-login on every desktop launch. Promote to OS keychain (Windows DPAPI / macOS Keychain / Linux Secret Service) if that becomes annoying enough.

---

## History

The 2026-05-18 ADR-0001 split (commit `a4b8faa`) was the last large structural change. Before that, the codebase was a single Maven module that ran the Spring Boot server and the JavaFX desktop UI in one JVM. See `docs/adr/0001-decouple-javafx-from-spring-boot.md` for the rationale and `AUDIT.md` for the full audit trail.
