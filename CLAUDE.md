# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

JDK 21 and Maven 3.9+ required. The repo is a two-module Maven reactor (parent pom at the root).

```sh
mvn clean package                          # build everything
mvn verify                                 # compile + all tests (see JWT_SECRET note)
mvn -pl fax-trident-server -am package     # server only (skips JavaFX deps entirely)
mvn -pl fax-trident-server -Dtest=JwtSecurityIntegrationTest test   # single test class
mvn -pl fax-trident-server -Dtest='FaxControllerTest#someMethod' test  # single method
mvn -pl fax-trident-desktop javafx:run     # run the desktop client (server must be up)
mvn -pl fax-trident-desktop -am package -Pdist  # assemble jpackage input (target/installer-input/)
```

**Integration tests need `JWT_SECRET`** (≥32 bytes) in the environment or Spring context startup fails fast — CI uses a dummy value. Tests run on in-memory H2 (`application-test.yml`); no local Postgres or Redis needed (Redis is mocked in tests).

There is no linter configured.

## Architecture

Two JVMs, one wire contract (ADR-0001, `docs/adr/0001-*.md`):

- **`fax-trident-server`** — Spring Boot 3.5 REST + WebSocket service. The only deployable server artifact; the Dockerfile builds this module alone (`-pl fax-trident-server -am`).
- **`fax-trident-desktop`** — JavaFX client, **deliberately Spring-free**. Talks to the server over the same REST + WebSocket surface as any external client: JDK `HttpClient` for REST (`client/FaxApiClient`), Java-WebSocket for `/fax-updates` (`ui/FaxUpdateClient`). Anything Spring-shaped appearing in this module is a regression — challenge it.

### Auth model (the part that touches everything)

Stateless JWT only — no sessions, no CSRF, no form login. `POST /api/auth/login` returns a bearer token; every other request (including the WebSocket handshake GET) must carry `Authorization: Bearer <jwt>`.

- All JWT machinery lives as **inner classes of `config/SecurityConfig`**: `JwtTokenProvider` (mint/validate/revoke) and `JwtTokenFilter` (servlet filter). There are no separate `Jwt*.java` files.
- Every token's `jti` is registered in a **Redis allowlist** (`jwt:jti:<uuid>`, TTL = token TTL). A well-signed token with a revoked/missing jti is rejected — logout is real. Tests mock `RedisTemplate` and flip `hasKey` per scenario.
- Roles ride in the token's `roles` claim as full strings (`ROLE_USER`); the filter must use `.authorities(...)`, never `.roles(...)` (which rejects the `ROLE_` prefix).

### WebSocket endpoints

Two registrations of the same broadcast handler in `config/WebSocketConfig`:

- `/fax-updates` — raw RFC 6455, used by the desktop and any non-browser client. **Must not be wrapped in SockJS** — a SockJS path rejects plain upgrades at its root (this was a real regression).
- `/fax-updates-sockjs` — SockJS variant for browsers needing fallback transports.

Both require an authenticated handshake (JWT filter + `WebSocketSecurityInterceptor`). The desktop re-reads its token supplier on every reconnect attempt so re-login rotation is honored. `WebSocketAuthIntegrationTest` covers this seam with real handshakes on a random port — extend it when touching either side.

### Other server invariants

- **Uploads**: clients never send file paths. `POST /api/fax/uploads` (multipart, 25 MiB cap, PDF magic-byte validation) returns an opaque `uploadId` consumed by the send endpoints.
- **Schema**: Flyway owns it (`db/migration/`); Hibernate runs `ddl-auto: validate` everywhere. Schema drift fails startup — new columns need a migration, not an entity edit alone.
- **Rate limiting**: `@RateLimit` is a working AOP annotation (`ratelimit/RateLimitAspect`) backed by Redis, keyed per-user or per-IP (login).
- **Redis pattern lookups**: use `SCAN` via try-with-resources, never `KEYS`.
- Fax status updates fan out via a Redis pub/sub channel (`fax-updates`) to the WS broadcast handler.

### Test patterns

Integration tests use a slim in-test `@SpringBootConfiguration` (`TestApp`) that excludes DataSource/JPA/Flyway/Redis auto-config and mocks `RedisTemplate` — see `JwtSecurityIntegrationTest` (MockMvc) and `WebSocketAuthIntegrationTest` (real container, RANDOM_PORT). Follow that pattern rather than booting the full app. The desktop module has no test infrastructure beyond JUnit on the classpath.

## CI and releases (ADR-0002, `docs/adr/0002-*.md`)

- `ci.yml` — whole-reactor `mvn verify` + Docker build smoke on every push/PR. Keep it whole-reactor; path-filtered per-module CI reintroduced a half-built-state failure mode this repo has been burned by (AUDIT §0.1).
- `docker-publish.yml` — pushes `ghcr.io/richeyworks/fax-trident` (`sha-<short>` on main; semver + `latest` on tags).
- `package-desktop.yml` — 3-OS jpackage matrix (`.msi`/`.dmg`/`.deb`), manual or via release. JavaFX enters installers as jmods on the module path; the `dist` profile therefore **excludes `org.openjfx` jars** from the classpath input. Icons in `fax-trident-desktop/packaging/`.
- `release.yml` — pushing `vX.Y.Z` does everything: sets the reactor version in-workflow, verifies, builds installers, creates the GitHub Release. **Poms stay `-SNAPSHOT` on main**; never commit a release version. SemVer is keyed to the REST+WS contract.

Line endings are enforced by `.gitattributes` (LF in repo and working tree for source); don't fight it.

## Key documents

- `AUDIT.md` — the single source of truth for security findings, operator actions, and open follow-ups. Close-out conventions live there; update it when finishing follow-up work.
- `docs/adr/` — architecture decisions. The two-module split (0001) and build/release infrastructure (0002) are both Accepted; align changes with them or write a superseding ADR.
- `README.md` — operator-facing quickstart, config table (env vars, fail-fast requirements like `JWT_SECRET` and prod `app.websocket.allowed-origins`), API surface, release process.
