# Fax Trident

Spring Boot REST + WebSocket fax server with a JavaFX desktop client in the same JVM. JWT + form-login + optional Google OAuth2, Redis-backed rate limiting and a JTI allowlist for logout-aware tokens, PDFBox for PDF text extraction and ZXing for barcode generation, and a get-or-create contact model that survives concurrent unique-constraint races.

This README is meant for both operators (who want it deployed) and developers (who want to extend it). The quickstart is up front; the architecture and developer notes follow.

---

## Quickstart

### Prerequisites

- **JDK 21** (the project pins `java.version = 21`).
- **Maven 3.9+**.
- **Redis 6+** reachable on `localhost:6379` for local dev. Used for the JWT JTI allowlist, the rate-limit counters, the prediction cache, and the per-fax status keys.
- **PostgreSQL** for production. Dev runs on in-memory H2 so you can skip this until you switch profiles.

### Required environment

`JWT_SECRET` is the only secret the app refuses to start without. There is no default — the previous `your-secret-key` default was a CVE waiting to happen.

```sh
export JWT_SECRET="$(openssl rand -base64 48)"      # >= 32 bytes required for HS256
```

### Build and run (dev)

```sh
mvn clean package
java -jar target/fax-trident-1.0.0-SNAPSHOT.jar
```

The server listens on `:8080`. The JavaFX desktop client opens at the same time — they run in one process and share the Spring context. If you only want the server (e.g. inside a container), set `java.awt.headless=true`; the desktop UI will fail to initialize cleanly and the server keeps running.

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

`application-prod.yml` overrides `spring.jpa.hibernate.ddl-auto` to `validate` and turns off SQL logging — production schema changes should go through Flyway / Liquibase, not Hibernate auto-update.

### Docker

The included `Dockerfile` builds a multi-stage image and sets `SPRING_PROFILES_ACTIVE=prod` by default. No secrets are baked into the image — inject them at runtime:

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
| `jwt.validity` | no | `3600000` (1h) | Token TTL in ms. Same TTL is applied to the JTI allowlist entry. |
| `spring.datasource.*` | dev: no, prod: yes | H2 in-memory | Move to Postgres in prod via `application-prod.yml` or env. |
| `spring.data.redis.host` / `SPRING_DATA_REDIS_HOST` | no | `localhost` | Same for `port`, `timeout`, `database`. |
| `app.upload.dir` | no | `./uploads/` | Server-controlled directory for uploaded fax PDFs. Set to an **absolute path** in production. |
| `app.websocket.allowed-origins` | dev: no, prod: yes | `http://localhost:8080` (dev only) | Comma-separated. Prod profile fails fast if unset. |
| `app.websocket.client-url` | no | `ws://localhost:8080/fax-updates` | URL the desktop client connects to. |
| `oauth2.google.client-id` / `OAUTH2_GOOGLE_CLIENT_ID` | no | unset (OAuth disabled) | Both client-id and client-secret must be set together; setting only one fails fast. |
| `oauth2.google.client-secret` / `OAUTH2_GOOGLE_CLIENT_SECRET` | no | unset | See above. |
| `app.sound.startup` | no | `/sounds/trident-rise.wav` | Played on JavaFX boot. The shipped wav is a 3-byte placeholder — drop in a real one when you have one. |

---

## Architecture

```
+---------------------------- one JVM ----------------------------+
|                                                                 |
|  +---------- JavaFX desktop UI ----------+                      |
|  |  MainView -+                          |                      |
|  |            +-> FaxUpdateClient -------+----- WebSocket --+   |
|  |  PreviewPane                          |                  |   |
|  |  ThemeManager                         |                  |   |
|  +-----------------|---------------------+                  |   |
|                    |                                        |   |
|                    v (FaxEngineService etc.)                |   |
|                                                             |   |
|  +-------------- Spring Boot REST ---------------+          |   |
|  |  FaxController, AdminController, LoginController         |   |
|  |  FaxEngineService, SmartAssistService, FaxUploadService  |   |
|  |  PdfProcessingService, RateLimitAspect                   |   |
|  |  SecurityConfig (JWT + form login + OAuth2)              |   |
|  +----+-----------+-----------+-----------+-----------+-----+   |
|       |           |           |           |           |         |
|       v           v           v           v           v         |
|     JPA        Redis     WebSocket    PDFBox       ZXing        |
|   (H2/PG)   (Lettuce)   (server)                                |
|                                                                 |
+-----------------------------------------------------------------+
```

The desktop UI and the server share a Spring `ApplicationContext`. JavaFX's `Application.launch()` instantiates `FaxTridentApplication` itself via reflection, so `init()` explicitly calls `springContext.getAutowireCapableBeanFactory().autowireBean(this)` to populate the JavaFX-managed instance's `@Autowired` fields — without that, the `start(Stage)` defensive null checks would trip every boot.

The desktop client talks to the server over an internal WebSocket (`/fax-updates`), not direct service calls. `FaxUpdateClient` is the single shared subscriber for `MainView` and `PreviewPane`; it connects on `ApplicationReadyEvent` (after Tomcat begins accepting upgrades) and reconnects with exponential backoff.

### Auth model

Hybrid — both surfaces work, and CSRF is enforced selectively:

- **Browser flow**: form login at `/login` (Thymeleaf-rendered to carry a `_csrf` token) issues a session cookie **and** returns a JWT in the `Authorization` response header. CSRF is enforced on every state-changing `/api/**` call that does **not** present a `Bearer` token.
- **Programmatic flow**: clients call `POST /login` once or use OAuth2, store the JWT, and send `Authorization: Bearer ...` on subsequent calls. The Bearer header tells the CSRF matcher to skip; the JWT filter rejects forged tokens downstream.
- **Logout**: revokes the JWT's `jti` from the Redis allowlist. The session cookie is invalidated too. Further use of the token returns 401.

### Rate limiting

`@RateLimit(key = "fax:send:#{authentication.name}", rate = 10, period = 60)` on the controller method. `RateLimitAspect` is a Spring AOP `@Around` that uses a Redis fixed-window counter — at-most-`rate` calls per `period` seconds per resolved SpEL key. Exceeded calls throw `RateLimitExceededException` which maps to HTTP 429.

---

## Project layout

```
src/main/java/com/xai/trident
├── FaxTridentApplication.java   JavaFX + Spring Boot entrypoint
├── config/
│   ├── SecurityConfig.java      JWT + form + OAuth2; CSRF matcher; logout handler
│   ├── RedisConfig.java         Pub/sub + RedisTemplate beans
│   ├── WebSocketConfig.java     /fax-updates handler; @Scheduled session cleanup
│   └── AsyncConfig.java         DelegatingSecurityContextAsyncTaskExecutor
├── controller/
│   ├── FaxController.java       /api/fax/**
│   ├── AdminController.java     /api/admin/**
│   └── LoginController.java     GET /login (Thymeleaf)
├── service/
│   ├── FaxEngineService.java    sendFax, processInput, findOrCreateContact
│   ├── PdfProcessingService.java text extraction + QR barcode generation
│   ├── SmartAssistService.java  contact prediction + auto-send
│   └── InboundFaxSimulator.java @Profile("dev") @Scheduled trigger
├── upload/
│   ├── FaxUploadService.java    multipart store, UUID + chroot resolve
│   └── UploadExceptionHandler.java 400 / 404 / 413 mapping
├── ratelimit/
│   ├── RateLimit.java           the @interface
│   └── RateLimitAspect.java     @Around advice, Redis fixed-window
├── model/
│   ├── Contact.java, FaxLog.java, FaxMetadata.java
│   └── User.java                Spring Security user record
├── repository/
│   ├── ContactRepository.java   incl. findByFaxNumberContaining for SmartAssist
│   ├── FaxLogRepository.java    incl. countByFaxNumber
│   ├── FaxMetadataRepository.java incl. findTotalPageCount / findTotalFileSize
│   └── UserRepository.java
├── ui/
│   ├── MainView.java            JavaFX BorderPane, programmatic build
│   ├── PreviewPane.java         PDF rendering with @Retryable + @Recover
│   ├── ThemeManager.java        Mermaid / dark themes
│   └── FaxUpdateClient.java     single shared WS client + listener registry
└── util/
    └── LogSanitizer.java        CR/LF/TAB escape for log lines
```

---

## API surface

All `/api/**` requires authentication. `/api/fax/**` is `ROLE_USER`; `/api/admin/**` is `ROLE_ADMIN`.

### Fax

| Method | Path | Notes |
|---|---|---|
| POST | `/api/fax/uploads` | multipart upload → returns `uploadId`. 25 MiB cap. |
| POST | `/api/fax/send` | `{faxNumber, uploadId}` → async send. Rate-limited 10/min/user. |
| POST | `/api/fax/process-input` | `?uploadId=...` → extract text. |
| POST | `/api/fax/auto-send` | `?partialInput=...&uploadId=...` → predict contact and send. Rate-limited 5/min/user. |
| GET | `/api/fax/status` | Redis ping for liveness. |
| GET | `/api/fax/status/{faxId}` | Per-fax status from Redis + DB. |
| GET | `/api/fax/logs/by-number/{faxNumber}` | Paged. |
| GET | `/api/fax/logs/recent?start=...&end=...` | Time-range. |
| GET | `/api/fax/predict-contact?partialInput=...` | SmartAssist. |
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
| GET | `/api/admin/contacts`, `/contacts/recent` | |
| GET | `/api/admin/logs/failed`, `/logs/analytics` | |
| GET | `/api/admin/websocket-stats`, `/fax-stats`, `/pdf-stats`, `/metadata/stats`, `/theme-stats`, `/prediction-analytics` | Diagnostics. |
| DELETE | `/api/admin/cleanup-barcodes` | Deletes barcode PNGs in the configured dir. |

### Auth

| Method | Path | Notes |
|---|---|---|
| GET | `/login` | Thymeleaf-rendered login page with CSRF token. |
| POST | `/login` | Form login; returns `Authorization: Bearer ...` header + session cookie. |
| GET | `/oauth2/authorization/google` | Only if OAuth credentials are configured. |
| POST | `/logout` | Invalidates session and revokes the JWT JTI. |

---

## Development

### Running tests

```sh
mvn test
```

`FaxEngineServiceTest` is the existing service test. It uses its own `TestConfig` rather than loading the full app, so `SecurityConfig` isn't instantiated and the UI beans (including `FaxUpdateClient`) don't try to open WebSocket connections.

There's plenty of room for more tests — the AUDIT recommends `@WebMvcTest` slices for the controllers, `@DataJpaTest` for the repository JPQL, and `MockMvc` security tests for the CSRF / JWT / role matrix.

### Adding an endpoint

1. Add the controller method.
2. If it touches the DB, add `@Transactional(readOnly = true)` or `@Transactional` as appropriate. If it doesn't, leave the annotation off — there's a one-line comment style for documenting "intentionally not transactional" already used through the controllers.
3. If it's user-mutating, give it a `@RateLimit` and let the AOP aspect handle 429s.
4. Sanitize any user-controlled string before logging it through `LogSanitizer.sanitize(...)`.

### Adding a Redis-backed feature

`RedisConfig` registers `RedisTemplate<String, Object>` and a string serializer for keys. For pattern lookups use `SCAN` via `redisTemplate.scan(...)` inside a try-with-resources — `KEYS` is O(N) and blocks the server.

### Profiles

- **default** — dev. H2 in-memory, ddl-auto: update, verbose SQL logging, allowed-origins defaults to `localhost`.
- **prod** — `application-prod.yml`. ddl-auto: validate, no SQL logging, allowed-origins must be set.
- **dev** — explicitly activates the `InboundFaxSimulator` `@Scheduled` trigger that fakes inbound faxes. Production never runs it.

---

## Hardening summary

The codebase has been through a full security + correctness audit. The major changes the audit produced (all closed; see `AUDIT.md` for the full trail):

- **JWT secret is required**. No default. `JWT_SECRET` shorter than 32 bytes fails startup. Tokens carry a `jti` registered in a Redis allowlist with the same TTL — logout revokes the `jti`, so a stolen token's blast radius is bounded by `jwt.validity`. Charset on the secret bytes is now explicit UTF-8.
- **Real rate limiting**. `@RateLimit` is a working AOP-backed annotation, not the no-op `@interface` it used to be. Redis fixed-window counter; HTTP 429 on excess.
- **Multipart upload API**. The old `filePath` request parameters that let a caller name any file on the server's disk are gone. `POST /api/fax/uploads` returns an opaque `uploadId`; the server stores the file under `app.upload.dir`, validates PDF magic bytes and size on store, and chroots the resolve on use.
- **CSRF model**. CSRF is enforced on mutating `/api/**` calls that don't present a Bearer token. The token lives in an `XSRF-TOKEN` cookie (`HttpOnly=false`) for SPA reads. The login form is Thymeleaf-rendered and carries `_csrf`, so default form-login works.
- **OAuth2 is opt-in**. No placeholder defaults — partial configuration (only id or only secret) fails fast; both unset disables OAuth login cleanly. Login form and JWT still work.
- **CSRF/Session + Async fixes**. `@Async` flows now propagate the `SecurityContext` via `DelegatingSecurityContextAsyncTaskExecutor`, so audit-trail `createdBy` reflects the actual user. WebSocket allowed-origins is read from `app.websocket.allowed-origins` and the `prod` profile fails fast if unset.
- **Correct ID generation**. Every `faxId` is a UUID. `System.currentTimeMillis()` IDs no longer collide under concurrency.
- **Contact get-or-create centralized**. One entry point in `FaxEngineService.findOrCreateContact(...)`. Concurrent unique-constraint races propagate as `DataIntegrityViolationException` and the existing `@Retryable` on `sendFaxAsync` retries.
- **Aggregations in SQL, not in the JVM**. `findAll().stream().mapToInt(...).sum()` patterns replaced with `SUM` / `COUNT` queries. Smart-assist substring search is paginated and capped, not unbounded.
- **Resource leaks closed**. `Files.list(...)` sites are all in try-with-resources. Spring Retry chains (`@Retryable` / `@Recover`) actually fire — the old code wrapped checked exceptions as RuntimeException before the proxy could see them.
- **`Thread.sleep()` inside `@Transactional` removed**. The placeholder sleeps in `processInput` and `sendFax` are gone.
- **Inbound-fax simulator profile-gated**. `Math.random() > 0.8` no longer manufactures fake contacts in production — the `@Scheduled` trigger is `@Profile("dev")`.
- **Structural cleanup**. `User` entity moved out of `SecurityConfig` to `model/` + `repository/`. JavaFX FXML+programmatic duel resolved (programmatic UI is the only path now). Single shared `FaxUpdateClient` replaces the two `WebSocketClient`s `MainView` and `PreviewPane` used to spin up.
- **YAML structure repaired**. `spring.datasource.*` / `spring.jpa.*` / `spring.data.redis.*` were previously nested under `app:` and silently ignored by Spring auto-config; they now live under `spring:` where they belong. Production overrides moved to `application-prod.yml`.

---

## Known follow-ups

Forward-looking work not covered by the audit:

- **Split JavaFX desktop from the server module.** Today they share a JVM and `FaxTridentApplication` plays both roles. Splitting into `fax-trident-server` and `fax-trident-desktop` unblocks headless server deploys and removes the autowire-into-Application dance.
- **Real ML for SmartAssist.** `predictContact` currently uses a name+number heuristic with a Redis cache. Stub `invokeXaiModel` has been removed; reintroduce it when there's a real model to call.
- **Flyway / Liquibase migrations.** `ddl-auto` is `validate` in prod, which means schema changes are operator-managed. Adopt a migration tool.
- **Spring Boot upgrade.** The parent is `3.2.4`. Upgrade to the current `3.x` line for the Spring Framework CVE fixes (CVE-2024-22243, CVE-2024-22257, ...).
- **Test coverage.** One service test today. The audit's test-strategy section sketches what's missing — controller slices, repository slices, security tests, Testcontainers for an integration pass.
- **Dockerfile base image.** Runtime is `eclipse-temurin:21-jre-alpine`. Alpine + JDK 21 has a known glibc/musl issue with some native libraries — verify PDFBox renders correctly there, or switch to `21-jre-jammy`.

### Build hygiene (landed 2026-05-16)

- `.gitignore` covers `target/`, IDE folders, `.env*`, `*.pem`/`*.key`, and the runtime `uploads/` / `barcodes/` directories.
- `pom.xml` no longer pins `slf4j-api`, `logback-classic`, `logback-core`, `jackson-databind` — the Spring Boot BOM owns those versions.
- `.github/workflows/ci.yml` builds and tests on JDK 21 (Temurin) on every push and PR, exports a CI-only `JWT_SECRET` so context startup doesn't fail, and uploads surefire reports on test failure.

---

## Files pending manual cleanup

These artifacts were neutralized but not deleted because the closing environment didn't authorize file deletion. Safe to remove from the working tree at any time:

- `src/main/resources/fxml/main.fxml` (tombstoned, no longer loaded)
- `src/main/resources/static/login.html` (tombstoned, the active login is the Thymeleaf template)
- the 0-byte `java` file at the repo root (looks like an accidental `touch`)
- `target/` is now in `.gitignore`, but the existing tracked contents need `git rm -r --cached target/` to clear from history
