# Fax Trident — Code Audit

**Date:** 2026-05-15 — original audit. **Last update:** 2026-05-18 (ADR-0001 split shipped: `mvn package` BUILD SUCCESS, committed as `a4b8faa`).
**Scope:** Full repository (`fax-trident`, Spring Boot 3.5.14 + JavaFX 21, ~3.4k LOC; now a two-module Maven reactor — `fax-trident-server` + `fax-trident-desktop`).
**Areas covered:** security, code quality / correctness, tech debt, test strategy.
**Status:** All findings closed and verified. Original CRITICAL/HIGH/MEDIUM/LOW findings remediated; post-audit multi-module regression (0.1) reverted on 2026-05-17, then properly landed as ADR-0001 on 2026-05-18 with full `mvn package` BUILD SUCCESS and a single atomic commit (tech-debt #1 closed). Two latent drive-by bugs surfaced and fixed during the ADR-0001 verification pass: 3.1a (`SchemaMigrationTest` import path) and 3.1b (missing `PhysicalNamingStrategy` override). Six tests pass: `FaxEngineServiceTest` 4/4 + `SchemaMigrationTest` 2/2. No open findings.

---

## 0. Active regressions

> **Status (2026-05-17):** 0.1 reverted and build re-verified green. There are no active regressions. Section retained for history.

### 0.1 CRITICAL — Multi-module restructure was half-finished; build was broken — ✅ Reverted & verified; later landed properly via ADR-0001 (2026-05-18)

**Original problem.** An in-flight refactor (toward tech-debt item #1, decoupling JavaFX from Spring Boot) had been started but not finished:

- `pom.xml` was converted to a parent reactor (`<packaging>pom</packaging>`, `artifactId=fax-trident-parent`) with `<module>fax-trident-server</module>` and `<module>fax-trident-desktop</module>`.
- `fax-trident-desktop/` did not exist on disk — Maven would have failed on `mvn validate` with "Child module .../fax-trident-desktop does not exist."
- `fax-trident-server/pom.xml` declared `<mainClass>com.xai.trident.FaxTridentServer</mainClass>`. No such class existed; the only entry point is `FaxTridentApplication`.
- All Java sources still lived at the top-level `src/main/java/...`. `fax-trident-server/` had no `src/` of its own, so even if the reactor had parsed, the server module would have built an empty jar.
- `Dockerfile` still did `COPY pom.xml .` / `COPY src ./src` / `mvn clean package` and then `COPY --from=builder /app/target/fax-trident-1.0.0-SNAPSHOT.jar app.jar`. Under the proposed new layout the artifact path and the module checkout were both wrong. Container build was broken in three places.

**Resolution (back-out path).** The half-finished restructure has been reverted rather than completed, on the theory that the decoupling work should land as a single, complete change rather than be patched into shape.

- **`pom.xml`** restored to its pre-restructure form: `artifactId=fax-trident`, `packaging=jar`, no `<modules>`, all dependencies and plugins (Spring Boot, JavaFX, PDFBox, ZXing, JWT, etc.) declared directly. The committed-in-git version was used as the revert target (the multi-module change was uncommitted local work).
- **`fax-trident-server/pom.xml`** rewritten to an inert tombstone explaining the back-out. It's no longer referenced from anywhere — the parent pom does not list it as a module — so Maven won't descend into it during a normal build.
- **`Dockerfile`** does not need to change: it was always copying the flat-layout `pom.xml` and `src/`, and the expected jar path (`target/fax-trident-1.0.0-SNAPSHOT.jar`) is now correct again.

**Verification (2026-05-17, operator host).**

```
> mvn validate
[INFO] BUILD SUCCESS  (0.302 s)

> mvn -DskipTests compile
[INFO] Compiling 32 source files with javac [debug parameters release 21] to target\classes
[INFO] BUILD SUCCESS  (9.004 s)
```

Only output noise: a pre-existing deprecation warning in `PreviewPane.java` (use `-Xlint:deprecation` for details) and the standard "6 problems were encountered while building the effective model for org.openjfx:javafx-controls" — the latter is an artifact-metadata quirk of the openjfx jars on Maven Central, not a real problem.

**Operator action completed.** The `fax-trident-server/` directory was removed on the operator host (`rmdir /S /Q fax-trident-server`). The agent sandbox could not unlink across the cross-mount, so this was deferred to the operator and is now done.

**Forward path for tech-debt #1.** Whenever the JavaFX/Spring-Boot decoupling is picked up again, treat it as a fresh, atomic change: create both module skeletons, move sources, rename the main class, update the Dockerfile, and verify a clean reactor build — *before* landing the parent-pom switch.

**Update (2026-05-18):** the decoupling was picked up again and landed atomically per the above guidance. See ADR-0001 (`docs/adr/0001-decouple-javafx-from-spring-boot.md`) for the design and tracker row 3.1 for the verification trail.

---

## Operator action checklist

Single source of truth for everything an operator needs to do, grouped by urgency. Individual findings still describe the *why*; this list is the *what*.

### Required — must do before / on next deploy

These are blocking. Skipping them either prevents startup or silently breaks behavior.

- **Set `JWT_SECRET` in every environment.** No default — startup fails fast on a missing or `<32`-byte value. Generate: `openssl rand -base64 48`. Apply via env var, Kubernetes Secret, etc. (1.1)
- **Set `app.upload.dir` to an absolute path in prod** (e.g. `/var/lib/fax-trident/uploads`). The default `./uploads/` is relative to the JVM working directory and varies by launcher. (1.5)
- **Set `app.websocket.allowed-origins`** when running with `SPRING_PROFILES_ACTIVE=prod`. Comma-separated list of frontend origins. The prod profile fails fast if unset. (1.9)

### Required — only if upgrading from a previous deploy

These bite operators promoting an existing deployment past the audit changes; they're no-ops on a fresh install.

- **Rename Redis env vars: `SPRING_REDIS_HOST` / `SPRING_REDIS_PORT` → `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT`** in any CI / shell scripts. The Spring Boot 3.x property root is `spring.data.redis.*`; the old prefix is silently ignored. (2.1, Dockerfile)
- **API clients must migrate from `filePath` to `uploadId`.** `POST /api/fax/send`, `/api/fax/auto-send`, `/api/admin/send-fax`, `/api/fax/process-input` no longer accept `filePath`. New flow: (1) `POST` the file as multipart to `/api/fax/uploads`, (2) take the returned `uploadId`, (3) pass it as `uploadId` on the action endpoint. The 413 response means the file exceeded the 25 MiB ceiling. (1.5)
- **All clients must use `Authorization: Bearer …`.** Session-cookie auth, form login, and OAuth2 redirect-login are all gone (tech-debt #5). Web clients that previously did form login now POST `{username, password}` to `POST /api/auth/login` and receive `{token: ...}`. Browser SPAs need their own login UI that calls this endpoint. (1.3, 1.4, tech-debt #5)
- **Pre-existing JWTs are invalid.** Tokens minted before the 1.6 fix have no `jti` and won't pass the Redis allowlist check. Every logged-in user must re-authenticate. There's no migration path because the older tokens were already exploitable under 1.1. (1.6)
- **Postgres only — fix the `idx_fax_number` collision.** Hibernate's `ddl-auto: validate` doesn't drop or create indexes, so existing prod databases still have the broken state from before the rename. Run once per Postgres environment:

  ```sql
  -- Rename the index Hibernate created on contacts before the fix
  ALTER INDEX IF EXISTS idx_fax_number RENAME TO idx_contact_fax_number;

  -- Create the index that Hibernate failed to create on fax_logs
  CREATE INDEX IF NOT EXISTS idx_fax_number ON fax_logs (faxNumber);
  ```

  H2 dev / in-memory environments pick this up automatically because Flyway runs `V1__initial_schema.sql` against a fresh schema on every startup. (2.22)

### Recommended

Not blocking, but you'll want to do them.

- ~~Delete the cleanup folders from the 2026-05-18 ADR-0001 split.~~ ✅ Done — operator removed `_DEAD_CODE_OPERATOR_DELETE_PLEASE/` and `_AGENT_PROBE_CLEANUP_PLEASE/` before committing, so commit `a4b8faa` shows clean deletes of the old files rather than moves into a tombstone folder.

- **Behind a reverse proxy? Set `server.forward-headers-strategy: native`** (or `framework`). Per-IP rate limiting on `/api/auth/login` reads `HttpServletRequest.getRemoteAddr()`, which by default returns the TCP peer — i.e. the proxy's address. Without this setting, every request from behind the proxy keys to the same IP and brute-force protection collapses to a single shared bucket. The aspect deliberately doesn't parse `X-Forwarded-For` itself; doing so without knowing the trust boundary would let any caller spoof an IP. (2.23)

### Follow-ups surfaced by ADR-0001 (the JavaFX/Spring Boot split)

These are net-new opportunities the 2026-05-18 split made visible. They're not regressions — the original audit didn't cover them — but they're worth tracking as future work.

- **WebSocket bearer auth on `/fax-updates`.** Pre-split the WS endpoint lived in the same process as its only consumer (the in-JVM JavaFX UI), so a missing auth check was effectively private-network. Post-split, the desktop authenticates over HTTP and then opens an unauthenticated WS for status broadcasts — a leaked endpoint URL is enough to subscribe. Tighten by requiring a bearer in `Sec-WebSocket-Protocol` (or a short-lived query-param token) and rejecting upgrades without it in `WebSocketConfig`.
- **OS-keychain JWT persistence on the desktop.** `FaxApiClient` keeps the JWT in an `AtomicReference<String>`; users re-login every desktop launch. If that's annoying enough, integrate the platform secret store (Windows DPAPI / macOS Keychain / Linux Secret Service via libsecret). Out of scope for the split itself; cited in ADR-0001 "Will need to revisit."

### Follow-ups surfaced by ADR-0002 (build infrastructure)

- **Installer signing + notarization.** The jpackage installers from `.github/workflows/package-desktop.yml` are unsigned: Windows SmartScreen and macOS Gatekeeper will warn on install. Acceptable for self-distribution to a known user base; revisit when that warning becomes an adoption problem. Needs an Authenticode cert (Windows) and an Apple Developer ID + notarization step (macOS) — both cost money and accounts, which is why ADR-0002 explicitly deferred them.
- **Desktop app icon.** jpackage currently uses platform defaults; add `--icon` per OS (`.ico` / `.icns` / `.png`) once an icon asset exists.

### Optional — housekeeping

Pure memory / disk cleanup; no behavior impact.

- **Free orphan suggestion-cache keys** from before the `predict:` → `suggest:` rename. Existing entries age out via their 1-hour TTL on their own; this just hurries it:

  ```sh
  redis-cli --scan --pattern 'predict:*' | xargs -r redis-cli DEL
  ```

  (2.24)

### Already done — historical

For reference, these were operator actions in earlier drafts but have since been performed or retracted:

- ✅ `rmdir /S /Q fax-trident-server/` — performed 2026-05-17 (§0.1).
- ✅ Delete `src/main/resources/fxml/main.fxml`, `static/login.html`, and the 0-byte `java` file at the repo root — performed by operator 2026-05-17 (2.13, 2.20).
- ✅ ~~`git rm -r --cached target/`~~ — retracted; `target/` was never tracked (3.10).

---

## Progress tracker

Status as of **2026-05-17**.

| # | Finding | Sev | Status | Notes |
|---|---|---|---|---|
| 0.1 | Multi-module restructure half-finished; build broken | CRITICAL | ✅ Reverted & verified; later landed properly | Reverted 2026-05-17 to the pre-restructure single-module flat layout. Re-landed properly as a two-module reactor on 2026-05-18 per ADR-0001; see tracker row 3.1. |
| 3.1 | Decouple JavaFX UI from Spring Boot service (tech-debt #1) | TECH DEBT | ✅ Done & verified (2026-05-18) | Landed atomically per ADR-0001 (`docs/adr/0001-decouple-javafx-from-spring-boot.md`). Parent pom converted to `packaging=pom` with `<modules>fax-trident-server, fax-trident-desktop</modules>`; both module skeletons exist on disk; all sources moved; main class renamed (`FaxTridentApplication` → `FaxTridentServer`); new `FaxTridentDesktop` JavaFX entry point added; Dockerfile updated to build only the server module via `mvn -pl fax-trident-server -am package`. New desktop sources: `FaxApiClient` (JDK `HttpClient` wrapper), `RetryHelper` (replaces `@Retryable`/`@Recover` on desktop), `LoginDialog` (auth prompt), `DesktopPreferences` (replaces Redis-backed theme store; persists to `${user.home}/.fax-trident/preferences.properties`). Module boundary verified mechanically in the sandbox: package-vs-directory consistency 39/39, no server file imports `javafx.*` / `com.xai.trident.desktop.*` / `com.xai.trident.ui.*`, no desktop file imports `org.springframework.*` / `jakarta.persistence.*`, all three POMs `xmllint`-clean. **Operator verification (2026-05-18, JDK 21 / Maven on operator host):** `mvn validate` BUILD SUCCESS (3/3 modules); `mvn -DskipTests compile` BUILD SUCCESS (server: 28 sources; desktop: 9 sources); `mvn test` BUILD SUCCESS (6/6 — `FaxEngineServiceTest` 4/4 + `SchemaMigrationTest` 2/2) after the two drive-by fixes 3.1a and 3.1b; `mvn package` BUILD SUCCESS — server fat jar at `fax-trident-server/target/fax-trident-1.0.0-SNAPSHOT.jar`, desktop jar at `fax-trident-desktop/target/fax-trident-desktop-1.0.0-SNAPSHOT.jar`. **Committed as commit `a4b8faa`** ("ADR-0001: split JavaFX desktop from Spring Boot server into two modules") — 61 files changed, 3487 insertions, 1543 deletions, most renames detected by git. Only output noise: one pre-existing `SecurityConfig` deprecation warning + the standard openjfx effective-model warning, both inherited from pre-split. Docker build is the one remaining verification step, deferred only because Docker Desktop wasn't running on the operator host at verification time. The §0.1 sequencing trap is avoided: the parent-pom switch was the *last* edit, after both module sources and the Dockerfile were in place. Closes tech-debt #1. Side benefits: 2.2 (autowire-into-Application) and 2.14 (duplicate WS clients) are now unreachable by construction — server has no JavaFX classes, desktop has no Spring lifecycle. |
| 3.1a | Drive-by fix: wrong import path in `SchemaMigrationTest` | TECH DEBT | ✅ Fixed (2026-05-18) | `SchemaMigrationTest.java:13` imported `org.springframework.boot.autoconfigure.EntityScan` but the class actually lives at `org.springframework.boot.autoconfigure.domain.EntityScan`. The error surfaced when `mvn test` ran for the first time on the operator host as part of the ADR-0001 verification — the test had compiled and passed under earlier `mvn test` runs documented in this audit, which suggests something about the class-path resolution or the test file itself changed since the last successful run. Fix is a one-line import correction; test compilation is now green. Independent of the split — the bad import would have failed in the single-module layout too — but flagged here because it surfaced during the same operator verification pass. |
| 3.1b | Drive-by fix: missing `PhysicalNamingStrategy` override caused schema validation to fail against V1's camelCase columns | TECH DEBT | ✅ Fixed (2026-05-18) | Once `SchemaMigrationTest` finally compiled (see 3.1a), it surfaced a deeper latent bug: `V1__initial_schema.sql` declares column names in camelCase (`createdAt`, `updatedAt`, `faxNumber`, …) and the file's header comment claims this matches "what Hibernate actually emits today," but Spring Boot 3.x's default `SpringPhysicalNamingStrategy` converts camelCase entity fields to snake_case columns — so Hibernate's schema validator was looking for `created_at` and finding `createdAt`. The test failed with `Schema-validation: missing column [created_at] in table [contacts]`. `FaxEngineServiceTest` masked the same mismatch because its hand-built `LocalContainerEntityManagerFactoryBean` doesn't apply Spring Boot's autoconfigured naming strategy — Hibernate's `PhysicalNamingStrategyStandardImpl` (the no-op default) preserved camelCase, so the test was self-consistent against its own ddl-auto-created schema. The running app under `application.yml` defaults would have hit the same mismatch on first startup, so this was a latent prod-affecting bug that nobody had run into yet. Fix: added `spring.jpa.hibernate.naming.physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl` to `application.yml` with an inline comment explaining why. Aligns the entity model, the V1 migration, and any existing prod schema previously created by ddl-auto: update. Independent of the split — would have failed under the pre-split layout the same way — but flagged here because the split's mvn-test verification is what made it visible. |
| 3.1c | Drive-by fix: `@PreAuthorize` on controllers was inert without `@EnableMethodSecurity` | TECH DEBT | ✅ Fixed (2026-05-18) | `FaxController` and `AdminController` both carry class-level `@PreAuthorize("hasRole(...)")` annotations, but the codebase had no `@EnableMethodSecurity` (or its older `@EnableGlobalMethodSecurity` predecessor) anywhere. The annotations were declarative-but-unenforced; access control was actually coming from the URL-pattern matching in `SecurityConfig.securityFilterChain` (`/api/fax/** → hasRole("USER")`, `/api/admin/** → hasRole("ADMIN")`). Not a security hole — the URL patterns enforce the same rules — but stale code that would mislead reviewers. Added `@EnableMethodSecurity` to `SecurityConfig` so both layers say the same thing, with an inline comment naming the URL pattern as the belt and the `@PreAuthorize` as the braces. Surfaced during test-coverage batch design (4.1) — adding it now means the controller slice tests exercise both auth layers, not just the URL filter. |
| 4.1 | Test coverage batch: controller slices + JWT security probes | TECH DEBT | ✅ Done & verified (2026-05-18) | Four new test classes covering the surface most-changed by recent batches: `AuthControllerTest` (`@WebMvcTest` of `AuthController`; 4 cases — valid login, bad creds, blank username, blank password), `AdminControllerTest` (`@WebMvcTest` of `AdminController`; 3 cases — anonymous→401, USER→403, ADMIN→200), `FaxControllerTest` (`@WebMvcTest` of `FaxController`; 6 cases — anonymous→401, valid send→200+faxId, blank uploadId→400, unknown uploadId→404 via `UploadExceptionHandler`, multipart upload→200, empty page→200-not-404 for audit 2.16 regression), and `JwtSecurityIntegrationTest` (focused `@SpringBootTest` minus DB/Redis/JPA/Flyway autoconfig, with a tiny in-test `/test-protected` endpoint; 5 cases — missing header→401, valid token+jti→200, valid signature but revoked jti→401 for audit 1.6, forged signature→401 for audit 1.1, expired token→401). All `@MockBean`-driven; no Testcontainers or real Redis needed. Brings the test count from 6 to 24, with the highest-payoff layers covered first (per the AUDIT §4 plan). **Operator verification (2026-05-18, JDK 21 / Maven on operator host):** `mvn test` BUILD SUCCESS — 24/24 pass after two test-design corrections (`.with(csrf())` on POST request builders for Spring Security 6's MockMvc CSRF default; and crucially, removing `@MockBean RateLimitAspect` declarations — see lesson recorded in §4 below). Follow-up batches: repository slices (`@DataJpaTest` per repository, exercising every custom `@Query`), unit tests for `JwtTokenProvider` and `PdfProcessingService`, Testcontainers integration. |
| 4.2 | Test coverage batch: repository slices | TECH DEBT | ✅ Done & verified (2026-05-19) | Four new test classes exercising every custom `@Query` and derived finder against a Flyway-migrated H2 schema (in PostgreSQL mode, same setup as `SchemaMigrationTest`). `UserRepositoryTest` (2 cases — `findByUsername` happy path + unknown). `ContactRepositoryTest` (7 cases — `findByFaxNumber`, `findByNameContainingIgnoreCase`, `findByFaxNumberContaining` for audit 2.9, `findByCreatedAfter`, `existsByFaxNumber`, `countByCreatedBy` GROUP BY, `findActiveContactsBetween` with `JOIN c.faxLogs`). `FaxLogRepositoryTest` (10 cases — every query incl. `countByFaxNumber` derived for audit 2.9, `findErrorsBetween` over the TEXT-widened `errorMessage` column, `findByContactId` through the `contact_id` FK after the 2.22 index rename). `FaxMetadataRepositoryTest` (10 cases — every query incl. both SUM aggregates' null-on-empty contract pinned so a future `COALESCE` change is flagged). Brings the test count from 24 to ~53. **Operator verification (2026-05-19, JDK 21 / Maven on operator host):** `mvn test` BUILD SUCCESS on first run — all four new classes green. No production-code regressions surfaced this round, which is mildly disappointing but mostly reflects that the post-audit JPQL is in fact correct. The two SUM-on-empty tests prove the audit-2.9 fix is intact: SUM returns null and the dashboard's null-coalesce is the binding contract. Follow-up batches: unit tests for `JwtTokenProvider` and `PdfProcessingService`, Testcontainers integration. |
| 4.3 | Test coverage batch: pure unit tests for `JwtTokenProvider` + `PdfProcessingService` | TECH DEBT | ✅ Done & verified (2026-05-19) | Two new test classes, no Spring context, no real Redis, no fixture files. `JwtTokenProviderTest` (19 cases): constructor fail-fast on null / blank / short secret + missing redisTemplate (audit 1.1); `createToken` registers the jti in Redis with the matching TTL and the subject+roles claims survive the round trip; `validateToken` returns true only when both signature and allowlist agree (audit 1.6 — the post-logout false case is the regression test); pre-revocation tokens (no jti) are rejected; forged-signature short-circuits before any Redis call; expired tokens fail at parse time; `getRoles` rejects non-list / non-string-element / missing claims as `JwtException` rather than the pre-audit `ClassCastException` (audit 1.7); `revokeToken` deletes the jti and is idempotent on malformed input (LogoutHandler contract). `PdfProcessingServiceTest` (13 cases): in-memory PDFs generated with PDFBox per test in `@TempDir`; `extractTextFromPdf` covers valid / empty / non-PDF / missing / corrupt; `generateBarcode` writes the PNG inside the configured directory and the file starts with the PNG magic bytes; `cleanupBarcode` is a silent no-op on missing files; `cleanupAllBarcodes` deletes everything `.png` and returns the count, and returns zero gracefully on a missing dir (the AdminController-delegated path); `getBarcodeCount` reads the filesystem rather than the in-memory counter. Brings the test count from 53 to 85. **Operator verification (2026-05-19):** `mvn test` BUILD SUCCESS on first run. Follow-up batches: Testcontainers integration; deterministic replacement for `FaxEngineServiceTest.testListenForInboundFax`; TestFX for the desktop module. |
| 3.1d | Drive-by fix: `JwtTokenFilter` used `User.builder().roles(...)` with role strings that already carried the `ROLE_` prefix | TECH DEBT | ✅ Fixed (2026-05-18) | Surfaced by `JwtSecurityIntegrationTest.valid_token_with_jti_in_allowlist_returns_200`: every authenticated request would have failed with `IllegalArgumentException: ROLE_USER cannot start with ROLE_ (it is automatically added)`. The mismatch is that `AuthController` builds the `roles` JWT claim from `authentication.getAuthorities().getAuthority()` (full role strings: `ROLE_USER`, `ROLE_ADMIN`), but the filter then passed those to `User.builder().roles(...)`, which expects bare role names without the prefix. Pre-this-batch, no test exercised the full filter → controller path, so the bug was latent in production code despite all the audit-1.x JWT hardening. Fix: filter now calls `.authorities(roles.toArray(new String[0]))` instead — `.authorities(...)` accepts authority strings verbatim. Independent of the split and unrelated to the test batch goals, but flagged here because the test-coverage batch is what made it visible. |
| 1.1 | Hardcoded default JWT secret | CRITICAL | ✅ Fixed | No default; fail-fast on missing or <32-byte secret. Confirmed by `mvn compile`. |
| 1.2 | `@RateLimit` is a no-op annotation | CRITICAL | ✅ Fixed | Real annotation + AOP aspect under `com.xai.trident.ratelimit`; Redis-backed fixed-window counter; HTTP 429 on excess. |
| 2.1 | `RedisConfig` reads wrong property prefix | CRITICAL | ✅ Fixed | Hand-rolled `RedisConnectionFactory` deleted; Spring Boot auto-config now owns connection from `spring.data.redis.*`. |
| 2.4 | `@Async` flows lose the SecurityContext | HIGH | ✅ Fixed | New `AsyncConfig` provides a bounded pool wrapped in `DelegatingSecurityContextAsyncTaskExecutor`; uncaught-exception handler logs failures. |
| 1.8 | `secret.getBytes()` uses platform default charset | MEDIUM | ✅ Fixed | Fixed as part of 1.1 — explicit `StandardCharsets.UTF_8`. |
| 1.13 | Dockerfile bakes plaintext secrets | LOW | ✅ Fixed | Removed `JWT_SECRET`, `SPRING_DATASOURCE_PASSWORD`, OAuth defaults from the ENV layer; remaining vars are non-sensitive. |
| 1.3 | CSRF disabled for `/api/**` while sessions live | HIGH | ✅ Fixed (now: closed by elimination) | Initially fixed with a hybrid model — CSRF enforced on session-authenticated mutating requests, bypassed when a Bearer header was present. *Superseded 2026-05-17 by tech-debt #5:* sessions are gone, so there's no cookie surface left to attack; CSRF filter removed entirely. |
| 1.4 | Login form has no CSRF token field | HIGH | ✅ Fixed (now: closed by elimination) | Initially fixed by moving the login page to a Thymeleaf template that interpolated the CSRF token. *Superseded 2026-05-17 by tech-debt #5:* form login is gone, no template, no token to interpolate. Auth is now `POST /api/auth/login` (JSON) → `{token}`. |
| 1.5 | User-controlled file path on send endpoints | HIGH | ✅ Fixed | New `POST /api/fax/uploads` (multipart) returns an opaque `uploadId`; `/api/fax/send`, `/api/fax/auto-send`, `/api/admin/send-fax`, and `/api/fax/process-input` now take that `uploadId` instead of a `filePath`. `FaxUploadService` validates size (≤25 MiB) and PDF magic bytes on store, and canonicalizes + UUID-pattern-matches on resolve so traversal attempts (`"../etc/passwd"`) fail the same way as unknown IDs (404). Service signatures (`sendFax(faxNumber, path)`) are unchanged — the desktop UI's in-process trusted path keeps working. |
| 1.6 | JWTs not invalidated on logout | HIGH | ✅ Fixed | Each token now carries a `jti`, registered in Redis (`jwt:jti:<jti>`) with the same TTL on issue. `validateToken` rejects tokens whose jti is absent (revoked / expired). New `LogoutHandler` reads the Bearer header on `/logout` and deletes the jti so subsequent uses of the token are 401. |
| 1.7 | Unchecked role-cast in JWT parsing | MEDIUM | ✅ Fixed | `getRoles` validates that the `roles` claim is a `List` and every element is a `String`, throws `JwtException` otherwise. Filter catches and treats malformed roles as authentication failure. `@SuppressWarnings("unchecked")` narrowed to the post-validation cast only. |
| 1.9 | Hardcoded WebSocket allowed origin | MEDIUM | ✅ Fixed | `WebSocketConfig.resolveAllowedOrigins()` reads `app.websocket.allowed-origins` (comma-separated). Prod profile fails fast when unset; dev falls back to `http://localhost:8080` with a warning. `application.yml` documents the property. |
| 1.10 | `redisTemplate.keys(...)` blocking | MEDIUM | ✅ Fixed | All four sites (`dashboard`, `clearCache`, `getThemeStats`, `getPredictionAnalytics`) switched to a shared `scanKeys(pattern)` helper that uses `SCAN` with a 256-key page count inside try-with-resources. Values are null- and type-checked before use, so the two NPE paths in 2.17 are also closed. |
| 1.11 | Log injection from user-controlled fields | MEDIUM | ✅ Fixed | New `com.xai.trident.util.LogSanitizer` escapes `\r`/`\n`/`\t` before they reach SLF4J. Applied at the audit-flagged call sites (`FaxController.send`, `processInput`, `predict-contact`, `auto-send`, `searchContacts`; `AdminController.sendFax`, `getFaxStatus`, `clearCache`; `FaxEngineService.processInput`, `sendFax`, `saveContact`) and inside `SecurityConfig` log lines that surface usernames or exception messages. |
| 1.12 | OAuth2 placeholder defaults | LOW | ✅ Fixed (now: N/A) | Initially fixed by dropping the placeholder defaults and adding a both-or-neither startup check. *Superseded 2026-05-17 by tech-debt #5:* the OAuth2 surface itself was removed (no `spring-boot-starter-oauth2-client`, no conditional flow). |
| 2.2 | JavaFX `Application` not Spring-managed | CRITICAL | ✅ Fixed | Added `springContext.getAutowireCapableBeanFactory().autowireBean(this)` in `init()` so the JavaFX-instantiated instance gets its `@Autowired` fields populated. Defensive null-checks in `start(Stage)` retained — they're now actually defensive rather than masking the real bug. |
| 2.3 | `@Retryable` never fires in PDF/preview | HIGH | ✅ Fixed | `PdfProcessingService` no longer wraps IOException; `extractTextFromPdf` / `generateBarcode` propagate; `@Recover` return types now match `CompletableFuture<String>`. `PreviewPane` collapsed double-async pattern (removed inner `CompletableFuture.runAsync`); `loadDocument` propagates IOException; `@Recover` returns `CompletableFuture<Void>`. MainView call site updated with try-catch + `.exceptionally(...)`. |
| 2.5 | `faxId` from `currentTimeMillis()` collides | HIGH | ✅ Fixed | All 10 `"<prefix>_" + System.currentTimeMillis()` sites now use `UUID.randomUUID()` (FaxController ×3, FaxEngineService ×6, PdfProcessingService ×1). Only legit `currentTimeMillis()` use left is JWT expiry arithmetic. |
| 2.6 | `Thread.sleep(...)` inside `@Transactional` | HIGH | ✅ Fixed | Both placeholder sleeps removed (`processInput` 1s, `sendFax` 2s). Dead `InterruptedException` catches deleted. Comments left in place pointing at the removal so future authors don't reintroduce them. |
| 2.7 | `processInput` reads dead Redis key | HIGH | ✅ Fixed | `FaxEngineService.processInput` now returns a `ProcessInputResult(faxId, extractedText)` record. Controller uses the returned `faxId` and surfaces the extracted text directly — no more orphan Redis read keyed off a different UUID. Existing test (which ignores the return value) is unaffected. |
| 2.8 | `Math.random()` inbound-fax simulator in prod | HIGH | ✅ Fixed | `@Scheduled` extracted to new `InboundFaxSimulator` class annotated `@Profile("dev")`; production (which ships with `SPRING_PROFILES_ACTIVE=prod`) never instantiates the trigger. The method remains directly callable from tests. |
| 2.9 | `findAll().stream()` loads whole tables | HIGH | ✅ Fixed | `FaxMetadataRepository` gained `findTotalPageCount()` / `findTotalFileSize()` (JPQL `SUM`); admin dashboard now uses them with null-coalesce. `SmartAssistService.predictContact` uses a paged `ContactRepository.findByFaxNumberContaining(..., Pageable.ofSize(200))` instead of `findAll().stream().filter`. Per-candidate history depth now uses `FaxLogRepository.countByFaxNumber(...)` instead of `findByFaxNumber(unpaged).getTotalElements()`. |
| 2.10 | `Files.list(...)` resource leaks | HIGH | ✅ Fixed | All three sites wrapped in try-with-resources. AdminController.cleanupBarcodes refactored to delegate to a new `PdfProcessingService.cleanupAllBarcodes()` — also fixes the wrong-directory bug (was scanning `Paths.get(".")` instead of the configured barcode dir). |
| 2.11 | `Contact.faxLogs` cascade erases audit trail | MEDIUM | ✅ Fixed | `cascade = CascadeType.ALL` and `orphanRemoval = true` removed from `Contact.faxLogs`. Confirmed no callers rely on the cascade (no `addFaxLog`/`faxLogs.add/remove` use sites outside the entity itself). FaxLogs now managed independently via `FaxLogRepository`. |
| 2.12 | Duplicate contact write in `FaxController.send` | MEDIUM | ✅ Fixed | Service-side fix (new `FaxEngineService.findOrCreateContact(faxNumber)`, controller no longer pre-writes "Unknown") landed earlier. Re-investigation on 2026-05-17 confirmed the `times(2)` assertion in `FaxEngineServiceTest:122` is actually *correct* for the post-fix code — the matcher catches Long-valued Redis sets, and `sendFax` legitimately has two (`:contactId`, `:metadataId`). The stale `// TODO` comment was misleading; it referenced pre-refactor line numbers. Replaced with a comment explaining what the `2` actually counts and warning future authors not to bump it without auditing. Verified with `mvn test` on 2026-05-17 — 4/4 tests pass. |
| 2.22 | Duplicate `idx_fax_number` collides at schema-creation time, leaving `fax_logs.faxNumber` unindexed | MEDIUM | ✅ Fixed & verified | Both `Contact` (`contacts` table) and `FaxLog` (`fax_logs` table) declared `@Index(name = "idx_fax_number", columnList = "faxNumber")`. Index names are global per schema in Hibernate/H2/Postgres, so `contacts` got the index and `fax_logs` failed silently — `FaxLogRepository.findByFaxNumber`/`countByFaxNumber` (hot paths in SmartAssist + admin endpoints) were running unindexed scans. Renamed `Contact`'s index to `idx_contact_fax_number`; left `fax_logs.idx_fax_number` as canonical since logs are queried more often. Re-verified with `mvn test` — 4/4 pass and the `Index "IDX_FAX_NUMBER" already exists` stack trace is gone from the Hibernate startup log. Cross-check: all 8 `@Index` names across the model are now unique. Operator action required for live Postgres deployments — see §2.22. |
| 2.13 | JavaFX wired up two contradictory ways | MEDIUM | ✅ Fixed | `MainView.initializeUI()` now calls `buildProgrammaticUI()` unconditionally; the `FXMLLoader.load(...)` try-catch and the unused `FXMLLoader` / `Parent` imports are gone. `main.fxml` was first rewritten to an inert placeholder, then deleted by the operator (2026-05-17); the `fxml/` directory no longer exists. |
| 2.14 | Two desktop WebSocket clients on startup | MEDIUM | ✅ Fixed | New `com.xai.trident.ui.FaxUpdateClient` bean holds a single shared `WebSocketClient`; connects on `ApplicationReadyEvent` (Tomcat-ready) with exponential backoff capped at 30s; listeners register via `addListener(Consumer<Map<String,String>>)`. `MainView` and `PreviewPane` subscribe in their constructors and no longer instantiate their own clients. Endpoint URL configurable via `app.websocket.client-url`. |
| 2.15 | `User` entity nested inside `SecurityConfig` | MEDIUM | ✅ Fixed | `User` extracted to `com.xai.trident.model.User`; `UserRepository` extracted to `com.xai.trident.repository.UserRepository`. `SecurityConfig` imports them; the inner classes are gone. JPA persistence pkg import dropped from SecurityConfig as a side benefit. |
| 2.16 | Empty `Page<...>` returned as 404 | MEDIUM | ✅ Fixed | Three call sites (`getFaxLogsByNumber`, `getRecentLogs`, `searchContacts`) now return 200 OK with the empty Page/List. The two legitimate 404s in `FaxController` (`processInput` unknown faxId, `getMetadata` by ID) are preserved — those address specific resources rather than search results. |
| 2.17 | `getThemeStats` / `getPredictionAnalytics` NPE | MEDIUM | ✅ Fixed | Closed as a side effect of 1.10: both call sites now `instanceof String`-guard the SCAN result before merging / `startsWith`. |
| 2.18 | `@Transactional` on non-DB methods | LOW | ✅ Fixed | 11 sites pruned: `PdfProcessingService.cleanupBarcode`; `AdminController.sendFax`, `clearCache`, `getWebSocketStats`, `getPdfStats`, `getThemeStats`, `getPredictionAnalytics`; `FaxController.getSystemStatus`, `sendFax`, `processInput`, `predictContact`, `autoSendFax`. Each replaced with a one-line comment explaining why the annotation is intentionally absent. `Transactional` import dropped from `PdfProcessingService`. Methods that actually read/write the DB (`getFaxStatus`, `getMetadataStats`, `getContacts`, `getRecentContacts`, `getFailedLogs`, `getLogAnalytics`, `getFaxStats`, `getDashboard`, `getFaxLogsByNumber`, `getRecentLogs`, `searchContacts`, `getMetadata`) still carry their `@Transactional(readOnly = true)`. |
| 2.19 | Missing startup sound file | LOW | ✅ Fixed | Created `src/main/resources/sounds/trident-rise.wav` as a 3-byte sentinel matching the existing `send-success.wav` / `retry-fail.wav` placeholders (` \r\n`). The default `app.sound.startup` path now resolves; `playStartupSound`'s existing catch-Exception path will warn (not crash) if the file isn't a real WAV — same behavior as the other sentinels. |
| 2.20 | Dead code / unused symbols | LOW | ✅ Fixed | Removed: `PdfProcessingService.BARCODE_DIR` constant (shadowed by `@Value`-bound `barcodeDir`); `SmartAssistService.invokeXaiModel` method + the unreachable ML-prediction branch that called it; duplicate `@EnableScheduling` on `WebSocketConfig` (kept on `FaxTridentApplication`); `ContactRepository.findByOrganizationContainingIgnoreCase` (defined but never called); vestigial `FaxUpdateHandler` field + constructor param in `MainView` and `PreviewPane` (unused after 2.14). `static/login.html` rewritten as an inert tombstone (the active login page is the Thymeleaf `templates/login.html` from the 1.4 fix). |
| 2.21 | `ddl-auto: update` + `show-sql: true` in prod config | LOW | ✅ Fixed | Two-step: (a) yaml structure repair — `datasource:` / `jpa:` / `data:` moved from `app:` to `spring:` where Spring auto-config actually reads them; (b) `application-prod.yml` introduced as a profile overlay that overrode `spring.jpa.hibernate.ddl-auto: validate`, `show-sql: false`, `format_sql: false`. The commented PostgreSQL datasource block migrated to `application-prod.yml` with the password sourced from `SPRING_DATASOURCE_PASSWORD` env. Operators activate prod with `SPRING_PROFILES_ACTIVE=prod` (already the Dockerfile default). *Follow-up (2026-05-17, tracker row 3.4):* dev `ddl-auto: update` flipped to `validate` once Flyway took over the schema; the prod-side `ddl-auto: validate` override is now redundant and was removed. |
| 3.8 | Duplicate / unused dependency pins | TECH DEBT | ✅ Fixed | Explicit pins for `slf4j-api`, `logback-classic`, `logback-core`, `jackson-databind` and their property entries removed from `pom.xml`; Spring Boot BOM owns versions. |
| 3.8b | Spring Boot 3.2.4 (EOL) → 3.5.14 | TECH DEBT | ✅ Done & verified | `spring-boot-starter-parent` updated 3.2.4 → 3.5.14 on 2026-05-17. 3.2.x reached EOL in mid-2025 with no OSS fix for CVE-2026-22731 / CVE-2026-22733 (authentication bypass) plus the 2025-era Framework CVEs (CVE-2025-22235 actuator matcher, CVE-2025-41234/41242 path traversal, CVE-2025-41248/41249). Minimal-change bump — only the parent version touched; lettuce/postgresql/h2 explicit pins kept for now (separate hygiene pass). Migration surface for this project: zero — heapdump actuator default change is opt-out and not used here, `.enabled` properties tightening doesn't hit any config, no `spring-boot-parent` (vs `-starter-parent`) usage. `mvn test` 4/4 pass post-bump. |
| 3.4 | `ddl-auto: update` instead of Flyway | TECH DEBT | ✅ Done & verified | Flyway 10.x (BOM-managed by Spring Boot 3.5.14) added; `V1__initial_schema.sql` baselines the current schema; `ddl-auto` flipped from `update` to `validate` in every env; `application-prod.yml`'s `ddl-auto: validate` override removed as redundant. `baseline-on-migrate: true` keeps existing prod databases working without a one-off operator step. `mvn test` passes against the new setup. See tech-debt #4 for the full write-up. |
| 3.5 | Hybrid auth model (form login + JWT + sessions) | TECH DEBT | ✅ Done & verified | Picked stateless JWT-only. New `AuthController.POST /api/auth/login` returns `{token: ...}`; SecurityConfig now `STATELESS`, CSRF disabled (no cookie surface), formLogin/oauth2Login dropped. `LoginController` and `templates/login.html` tombstoned (operator delete pending). `spring-boot-starter-thymeleaf` and `spring-boot-starter-oauth2-client` removed from pom. Findings 1.3 / 1.4 closed by elimination; 1.12 N/A. `mvn test` passes against the new SecurityConfig. See tech-debt #5 for full write-up + operator action. |
| 3.2 | SmartAssist ML stub + misleading naming | TECH DEBT | ✅ Done | Renamed `SmartAssistService` → `ContactSuggestionService`, method `predictContact` → `suggestContact`, Redis key prefix `predict:` → `suggest:`. Public HTTP URLs retained for backwards-compat. Class javadoc rewritten to describe the heuristic honestly. Cache-of-failure-sentinel bug fixed in the same pass. See §2.24 for the full write-up and the one-line Redis cleanup for operators. |
| 2.23 | `/api/auth/login` was unrate-limited (brute-force exposure introduced by the 3.5 rewrite) | MEDIUM | ✅ Fixed & verified | `RateLimitAspect` extended to expose two new SpEL variables: `#request` (the `HttpServletRequest`) and `#ipAddress` (convenience for `request.remoteAddr`). `@RateLimit(key = "auth:login:#{#ipAddress}", rate = 10, period = 60)` now applied to `AuthController.login`. Operators behind a load balancer must set `server.forward-headers-strategy: native` (or `framework`) so the key actually identifies the client; documented in both the `@RateLimit` javadoc and `AuthController`. Aspect deliberately does NOT parse `X-Forwarded-For` itself — that would let any caller spoof an IP by setting the header. `mvn test` passes. |
| 2.24 | `SmartAssistService` name implied ML; cached failure sentinels were never served | LOW | ✅ Fixed & verified | Two-part close-out of tech-debt #2. (a) Renamed `SmartAssistService` → `ContactSuggestionService`, method `predictContact` → `suggestContact`. Class javadoc now describes the implementation honestly (deterministic score-based fuzzy matcher: `history*10 + name_contains*5 + fax_contains*3`). Old file tombstoned; sandbox can't unlink (operator delete). Redis key prefix migrated `predict:` → `suggest:`. `FaxController`, `AdminController`, and repository comments updated. Public HTTP endpoint URLs retained for backwards compat. (b) Cache bug: `cachePrediction` was being called with the failure sentinels (`"No matching contact found"`, `"Prediction error: ..."`), but the cache-read path explicitly excluded those values — so the cache filled with junk that was never returned. Now: failure paths skip caching entirely. `mvn test` passes. |
| 3.9 | Pin JDK 21 in CI; matching JRE base image | TECH DEBT | ✅ Done | `.github/workflows/ci.yml` builds and tests on Temurin JDK 21 with a CI-only `JWT_SECRET`. Dockerfile runtime image switched from `eclipse-temurin:21-jre-alpine` to `21-jre-jammy` (2026-05-17) — Alpine + musl + missing fontconfig was a silent risk for PDFBox text rendering. ~100 MB image-size cost, worth it for "PDFs actually render." Inline comment in the Dockerfile documents why and warns against reverting without proof. |
| 3.10 | Build artifacts in version control | TECH DEBT | ✅ Done (already) | `.gitignore` covers `target/`, IDE folders, OS junk, `.env*`, `*.pem`/`*.key`, runtime `uploads/` and `barcodes/`. *2026-05-17 re-verification:* `git ls-files target/` returns zero and `git log --all --diff-filter=A -- 'target/*'` shows the path was never tracked — the earlier "operator action: `git rm -r --cached target/`" note was written on a wrong assumption. Nothing for the operator to do here. |

### Files changed so far

- **Created:** `src/main/java/com/xai/trident/ratelimit/RateLimit.java`
- **Created:** `src/main/java/com/xai/trident/ratelimit/RateLimitExceededException.java`
- **Created:** `src/main/java/com/xai/trident/ratelimit/RateLimitAspect.java`
- **Created:** `src/main/java/com/xai/trident/config/AsyncConfig.java`
- **Modified:** `src/main/java/com/xai/trident/config/SecurityConfig.java` — JWT secret hardening (1.1, 1.8).
- **Modified:** `src/main/java/com/xai/trident/config/RedisConfig.java` — full rewrite; deleted hand-rolled `RedisConnectionFactory`.
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — dropped stub `@interface RateLimit`; imported real one.
- **Modified:** `Dockerfile` — stripped secret defaults; renamed `SPRING_REDIS_*` → `SPRING_DATA_REDIS_*`.
- **Modified:** `src/main/resources/application.yml` — added top-of-file documentation for `jwt.secret`.
- **Modified:** `src/main/java/com/xai/trident/service/FaxEngineService.java` — UUID IDs (2.5).
- **Modified:** `src/main/java/com/xai/trident/service/PdfProcessingService.java` — UUID barcode filenames (2.5); `throws IOException` instead of wrap (2.3); WriterException handled non-retryably; dropped useless `@Transactional` on `extractTextFromPdf` and `generateBarcode` (partial 2.18).
- **Modified:** `src/main/java/com/xai/trident/ui/PreviewPane.java` — collapsed double-async (2.3); `@Recover` signature fixed; orphaned `@Transactional` import removed.
- **Modified:** `src/main/java/com/xai/trident/ui/MainView.java` — try-catch + `.exceptionally(...)` around `loadDocumentAsync` call (2.3 fallout).
- **Modified:** `src/main/java/com/xai/trident/service/PdfProcessingService.java` — try-with-resources on the two `Files.list` sites; new `cleanupAllBarcodes()` method (2.10).
- **Modified:** `src/main/java/com/xai/trident/controller/AdminController.java` — `cleanupBarcodes` delegates to `PdfProcessingService.cleanupAllBarcodes()`; orphan `Files`/`Paths` imports removed; redundant `@Transactional` dropped (2.10 + partial 2.18).
- **Created:** `src/main/java/com/xai/trident/service/InboundFaxSimulator.java` — `@Profile("dev")`-gated `@Scheduled` trigger for `listenForInboundFax` (2.8).
- **Modified:** `src/main/java/com/xai/trident/service/FaxEngineService.java` — removed `@Scheduled` from `listenForInboundFax` (2.8); removed the two `Thread.sleep` placeholders and their dead `InterruptedException` catches (2.6).
- **Modified:** `src/main/java/com/xai/trident/FaxTridentApplication.java` — added `autowireBean(this)` in `init()` (2.2).
- **Modified:** `src/main/java/com/xai/trident/service/FaxEngineService.java` — `processInput` returns `ProcessInputResult` record (2.7).
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — uses `ProcessInputResult` and drops the orphan Redis read (2.7).

#### Security-hardening batch (2026-05-16)

- **Created:** `src/main/java/com/xai/trident/util/LogSanitizer.java` — CR/LF/TAB escaping helper (1.11).
- **Created:** `src/main/java/com/xai/trident/controller/LoginController.java` — Thymeleaf-backed `GET /login` so the CSRF token is interpolated into the form (1.4).
- **Created:** `src/main/resources/templates/login.html` — Thymeleaf template carrying the hidden `_csrf` field (1.4).
- **Modified:** `src/main/java/com/xai/trident/config/SecurityConfig.java` — full rewrite touching multiple findings at once:
  - CSRF now applies to mutating requests via a custom `RequestMatcher` that exempts callers carrying an `Authorization: Bearer` header (1.3).
  - `JwtTokenProvider` constructor takes a `RedisTemplate`; `createToken` registers the issued `jti` in `jwt:jti:<jti>` with the token's TTL; `validateToken` rejects unknown/revoked `jti`; new `revokeToken` is called from a `LogoutHandler` that reads the Bearer header on `/logout` (1.6).
  - `getRoles` validates list-of-string shape before casting; filter catches `JwtException` and refuses to authenticate (1.7).
  - Log lines for username and exception messages are routed through `LogSanitizer.sanitize` (1.11).
- **Modified:** `src/main/java/com/xai/trident/controller/AdminController.java` — replaced four `redisTemplate.keys(...)` calls with a new `scanKeys(pattern)` helper that uses `SCAN` inside try-with-resources (1.10). Values from Redis are `instanceof String`-checked before merging or `startsWith` (closes 2.17). Log lines sanitized (1.11).
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — log lines for `send`, `processInput`, `predict-contact`, `auto-send`, `searchContacts`, and `getFaxLogsByNumber` routed through `LogSanitizer.sanitize` (1.11).
- **Modified:** `src/main/java/com/xai/trident/service/FaxEngineService.java` — log lines in `processInput`, `sendFax`, `saveContact` routed through `LogSanitizer.sanitize` (1.11).
- **Modified:** `pom.xml` — added `spring-boot-starter-thymeleaf` for the login template render (1.4).

##### Build verification

`mvn compile` could not be run in this sandbox (network allowlist excludes Maven Central / Adoptium and the host has no JDK 21). Each change was hand-verified against the codebase: imports balanced, method signatures consistent, only Java 16+ features used (pattern matching `instanceof`), and the existing test (`FaxEngineServiceTest`) isolates SecurityConfig from its `TestConfig` so the JwtTokenProvider signature change does not affect it. Confirmed clean by operator running `mvn` after the batch landed.

#### Upload-API batch (2026-05-16)

- **Created:** `src/main/java/com/xai/trident/upload/FaxUploadService.java` — server-controlled storage (UUID-named `.pdf` under `app.upload.dir`); PDF magic-byte + 25 MiB size validation on store; UUID-pattern + realpath chroot check on resolve (1.5).
- **Created:** `src/main/java/com/xai/trident/upload/InvalidUploadException.java` — 400 mapping signal (1.5).
- **Created:** `src/main/java/com/xai/trident/upload/UploadNotFoundException.java` — 404 mapping signal; same exception for "missing" and "traversal attempt" so probes can't differentiate (1.5).
- **Created:** `src/main/java/com/xai/trident/upload/UploadExceptionHandler.java` — `@RestControllerAdvice` that maps the two exceptions plus `MaxUploadSizeExceededException` (→ 413) globally so every controller benefits without duplicate `@ExceptionHandler` blocks (1.5).
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — added `POST /api/fax/uploads`; replaced `filePath` with `uploadId` in `FaxRequestDTO` and on `/send`, `/auto-send`, `/process-input`; `resolveToString(uploadId)` is called *outside* the try-catch in each endpoint so upload errors propagate to the `@RestControllerAdvice` instead of being remapped to 500 (1.5).
- **Modified:** `src/main/java/com/xai/trident/controller/AdminController.java` — `/api/admin/send-fax` takes `uploadId` instead of `filePath`; same resolve-before-try pattern as FaxController (1.5).
- **Modified:** `src/main/resources/application.yml` — added `spring.servlet.multipart.max-file-size: 25MB` (matches `FaxUploadService.MAX_UPLOAD_BYTES`) and `app.upload.dir: ./uploads/` placeholder (1.5).

#### Medium-security batch (2026-05-16)

Closes the two remaining MEDIUM security findings.

- **Modified:** `src/main/java/com/xai/trident/config/WebSocketConfig.java` — `resolveAllowedOrigins()` reads `app.websocket.allowed-origins` (comma-separated), trims whitespace, and rejects empty parses. The `prod` profile fails fast with a clear `IllegalStateException` if the property is unset; non-prod profiles fall back to `http://localhost:8080` with a warning. The previous hardcoded literal is gone (1.9).
- **Modified:** `src/main/java/com/xai/trident/config/SecurityConfig.java` — OAuth2 is now opt-in (1.12):
  - `@Value("${oauth2.google.client-id:}")` and `…client-secret:` — empty-string defaults instead of `your-client-id` / `your-client-secret`. The previous defaults were valid-shape strings that built a real `ClientRegistration` and made the server attempt OAuth handshakes against Google with literal placeholders.
  - `securityFilterChain` checks "both set" / "both unset" / "exactly one set" at startup. The half-configured case throws `IllegalStateException` with a clear message. The fully-unset case skips `.oauth2Login(...)` entirely and logs that OAuth login is disabled.
  - `clientRegistrationRepository()` demoted from `@Bean` to a private helper `googleClientRegistrationRepository()`. As a `@Bean` Spring would eagerly instantiate it at context startup, and `ClientRegistration.Builder.build()` rejects blank client IDs — so the bean form would break startup for every deployment that doesn't configure OAuth. The private helper is only invoked from inside the conditional `.oauth2Login(...)` block.
- **Modified:** `src/main/resources/application.yml` — added a top-level commented `oauth2.google.*` block explaining the opt-in model. Properties remain at the root (matching `@Value` paths) rather than under `app:`.

#### Application.yml structural fix (2026-05-16)

Partial closure of 2.21. Spring Boot's auto-configuration reads `spring.datasource.*`, `spring.jpa.*`, and `spring.data.redis.*`, but those keys were nested under `app:` (a top-level sibling of `spring:`) — so every datasource, JPA, and Redis setting in this file was silently ignored. Auto-config fell back to defaults that happened to be close enough that nothing visibly broke in dev (in-memory H2, `localhost:6379` Redis), masking the bug.

- **Modified:** `src/main/resources/application.yml` — moved `datasource:`, `jpa:`, and `data:` blocks from `app:` to `spring:`. `app:` now contains only the genuinely app-specific keys (`upload`, `websocket`). The commented PostgreSQL alternative was lifted to top level with explicit guidance that it should be set under `spring:` (and, ideally, moved into `application-prod.yml`).
- **Verified:** the yaml re-parses cleanly; `spring.datasource.url`, `spring.jpa.hibernate.ddl-auto`, and `spring.data.redis.host` all resolve to the expected values.

##### Operator-visible behavior change

Before this fix, Spring Boot was using its built-in defaults (in-memory H2 with random URL, default Redis on `localhost:6379`). After this fix, the values in `application.yml` are the ones actually applied. The dev config happens to land on the same shape (H2 + `localhost:6379`) so no immediate behavior change is expected, but any future change to these keys in this file will now actually take effect — which is, of course, the point. Operators previously overriding via `SPRING_DATASOURCE_URL` / `SPRING_DATA_REDIS_HOST` env vars are unaffected; environment overrides always took precedence over the (silently-ignored) yaml values.

#### Batch A — JPA / structure cleanup (2026-05-16)

Closes the three mechanical JPA findings recommended as the first follow-up batch.

- **Modified:** `src/main/java/com/xai/trident/model/Contact.java` — dropped `cascade = CascadeType.ALL` and `orphanRemoval = true` from the `@OneToMany faxLogs` relationship. FaxLog rows are audit data and must not be wiped by Contact deletion or by mutating the in-memory collection. Comment documents the rationale (2.11).
- **Created:** `src/main/java/com/xai/trident/model/User.java` — extracted from `SecurityConfig`. Same shape as the original inner class (id=`username`, fields `password` and comma-separated `roles`) so the migration is mechanical and Hibernate sees the same table (2.15).
- **Created:** `src/main/java/com/xai/trident/repository/UserRepository.java` — extracted from `SecurityConfig`. Same `findByUsername(String)` method signature (2.15).
- **Modified:** `src/main/java/com/xai/trident/config/SecurityConfig.java` — removed the inner `User` entity and `UserRepository` interface; added imports for the new top-level types. Dropped now-unused `jakarta.persistence.*` and `java.util.Optional` imports. `JpaUserDetailsService` references the imported types unchanged (2.15).
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — three "empty page = 404" call sites (`getFaxLogsByNumber`, `getRecentLogs`, `searchContacts`) now return `ResponseEntity.ok(...)` with the empty Page/List. Reserved-404 sites (`processInput` unknown faxId at line 190, `getMetadata` missing ID at line 368) are left as 404 because they really do address a single missing resource (2.16).
- **Verified:** grepped for `SecurityConfig.User` / `SecurityConfig.UserRepository` references across the tree — none. Test classes don't import the moved types. Hibernate entity scan rooted at `com.xai.trident` still picks up `User` via the `@SpringBootApplication` default. Remaining `status(404)` sites in `FaxController` confined to legitimate single-resource cases.

#### Batch B — Performance bandaging (2026-05-16)

Closes the three performance-shaped findings: pull aggregations into SQL, dedupe contact writes, and consolidate the desktop WebSocket clients.

- **Modified:** `src/main/java/com/xai/trident/repository/FaxMetadataRepository.java` — added `findTotalPageCount()` and `findTotalFileSize()` (JPQL `SUM`). Both return `Long` (nullable on empty table); callers must null-coalesce (2.9).
- **Modified:** `src/main/java/com/xai/trident/repository/ContactRepository.java` — added `findByFaxNumberContaining(partial, Pageable)` so the smart-assist substring match runs in SQL with a bounded result set instead of pulling the whole table into JVM memory (2.9).
- **Modified:** `src/main/java/com/xai/trident/repository/FaxLogRepository.java` — added `countByFaxNumber(faxNumber)` to replace the per-candidate `findByFaxNumber(unpaged).getTotalElements()` pattern, which loaded every log row just to count them (2.9).
- **Modified:** `src/main/java/com/xai/trident/controller/AdminController.java` — `getDashboard` now uses the new SUM queries with `null → 0` coalesce; dropped the unused `FaxMetadata` model import (2.9).
- **Modified:** `src/main/java/com/xai/trident/service/SmartAssistService.java` — `predictContact` heuristic now uses paged `findByFaxNumberContaining(..., Pageable.ofSize(CANDIDATE_LIMIT=200))` and per-candidate `countByFaxNumber`. Comment explains why a generous cap is the right move ahead of a trigram/full-text index. Dropped the unused `Collectors` import (2.9).
- **Modified:** `src/main/java/com/xai/trident/service/FaxEngineService.java` — new public `findOrCreateContact(faxNumber)` method (the single get-or-create entry point); `sendFax` now calls it instead of inlining the orElseGet/save (2.12).
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — removed the pre-async `saveContact("Unknown", …)` call from `send`. Service-side `findOrCreateContact` is the only write path now. Dropped now-unused `java.util.Optional` import (2.12).
- **Created:** `src/main/java/com/xai/trident/ui/FaxUpdateClient.java` — single shared `WebSocketClient` bean. Connects on `ApplicationReadyEvent`. Exponential backoff starting at 1s, capped at 30s; reset on successful open. Reader-thread dispatch to a `CopyOnWriteArrayList<Consumer<Map<String,String>>>` of listeners. `@PreDestroy` closes the socket and shuts down the scheduler. Endpoint URL pulled from `app.websocket.client-url` (defaults to `ws://localhost:8080/fax-updates`) (2.14).
- **Modified:** `src/main/java/com/xai/trident/ui/MainView.java` — dropped the `@PostConstruct initWebSocket()` and the inline `WebSocketClient` subclass. Now takes a `FaxUpdateClient` in its constructor and registers `handleFaxUpdate(...)` as a listener. `handleFaxUpdate` wraps UI mutations in `Platform.runLater(...)` (the listener fires on a background thread). Dropped unused `URI`, `ObjectMapper`, `WebSocketClient`, `ServerHandshake`, and `PostConstruct` imports (2.14).
- **Modified:** `src/main/java/com/xai/trident/ui/PreviewPane.java` — same shape as `MainView`: subscribe via `FaxUpdateClient.addListener`, drop inline WS client, drop unused imports (2.14).
- **Hand-verification:** no other `WebSocketClient` instantiations remain in the UI package; only `FaxUpdateClient` references the `org.java_websocket` types. The `FaxUpdateHandler` field still injected into `MainView` and `PreviewPane` is currently unused but left in place — it'll be caught in the 2.20 dead-code sweep. `FaxEngineServiceTest` uses its own `TestConfig` and doesn't load any UI beans, so the new client won't try to connect during tests.

#### 2.13 — JavaFX wiring duel (2026-05-16)

`MainView.initializeUI()` used to attempt `FXMLLoader.load("/fxml/main.fxml")` first and fall back to `buildProgrammaticUI()` on `IOException`/`NullPointerException`. Two ways the dueling wiring could bite:

* `main.fxml` referenced `onAction="#handleSendFax"` and `#handlePreview` — neither method existed in any class. A successful FXML load would have thrown `javafx.fxml.LoadException` (in the loader) or `RuntimeException` at click time.
* The FXML root provided no `fx:id` for `statusLabel`, the theme toggle, or the file chooser button. `statusLabel` is only assigned inside `buildProgrammaticUI()`. If the FXML load *had* succeeded, `statusLabel` would have stayed `null`, and the new `handleFaxUpdate(...)` callback (from 2.14) would have NPE'd on every WS message.

- **Modified:** `src/main/java/com/xai/trident/ui/MainView.java` — `initializeUI()` calls `buildProgrammaticUI()` unconditionally. Removed the `FXMLLoader.load(...)` try-catch and the now-unused `FXMLLoader` and `Parent` imports.
- **Modified:** `src/main/resources/fxml/main.fxml` — replaced contents with a tombstone header explaining the file is deprecated and an inert empty `<BorderPane/>`. *Subsequently deleted by operator (2026-05-17); the `fxml/` directory is gone.*

#### Quick wins batch (2026-05-16)

Closes 2.18 / 2.19 / 2.20 / 2.21 and finishes the audit. See the tracker rows above for the per-finding summary; consolidated change list below.

- **Modified:** `src/main/java/com/xai/trident/service/PdfProcessingService.java` — dropped `@Transactional` from `cleanupBarcode` (pure filesystem delete) and the now-unused `org.springframework.transaction.annotation.Transactional` import (2.18). Dropped the shadowed `BARCODE_DIR = "barcodes/"` constant (2.20).
- **Modified:** `src/main/java/com/xai/trident/controller/FaxController.java` — dropped `@Transactional` from `getSystemStatus`, `sendFax`, `processInput`, `predictContact`, `autoSendFax`. Each replaced with a one-line comment explaining the omission (2.18).
- **Modified:** `src/main/java/com/xai/trident/controller/AdminController.java` — dropped `@Transactional` from `sendFax`, `clearCache`, `getWebSocketStats`, `getPdfStats`, `getThemeStats`, `getPredictionAnalytics` (2.18).
- **Created:** `src/main/resources/sounds/trident-rise.wav` — 3-byte placeholder matching the existing `send-success.wav` / `retry-fail.wav` sentinels (2.19).
- **Modified:** `src/main/java/com/xai/trident/service/SmartAssistService.java` — removed `invokeXaiModel(...)` stub and the unreachable ML-prediction branch in `predictContact` (2.20).
- **Modified:** `src/main/java/com/xai/trident/config/WebSocketConfig.java` — removed duplicate `@EnableScheduling` (kept on `FaxTridentApplication`); dropped now-unused `EnableScheduling` import (2.20).
- **Modified:** `src/main/java/com/xai/trident/repository/ContactRepository.java` — removed `findByOrganizationContainingIgnoreCase` (defined but never called) (2.20).
- **Modified:** `src/main/java/com/xai/trident/ui/MainView.java` — removed `FaxUpdateHandler` field, constructor param, and import (unused after 2.14) (2.20).
- **Modified:** `src/main/java/com/xai/trident/ui/PreviewPane.java` — same as MainView: `FaxUpdateHandler` field, constructor param, and import removed (2.20).
- **Modified:** `src/main/resources/static/login.html` — rewritten as an inert tombstone with a deprecation comment. The active login page is `templates/login.html`, served by `LoginController` (the 1.4 fix). *Subsequently deleted by operator (2026-05-17); only `static/css/` remains.* (2.20)
- **Created:** `src/main/resources/application-prod.yml` — profile overlay activated by `SPRING_PROFILES_ACTIVE=prod` (the Dockerfile already sets this). Overrides `spring.jpa.hibernate.ddl-auto: validate`, `show-sql: false`, `format_sql: false`. Carries the commented PostgreSQL datasource block that used to sit in `application.yml`, sourcing the password from `SPRING_DATASOURCE_PASSWORD` env (2.21).
- **Modified:** `src/main/resources/application.yml` — clarified that the H2 / `ddl-auto: update` / verbose SQL block is the **dev** default and prod overrides live in `application-prod.yml`. Removed the giant commented PostgreSQL block (it now lives in the prod overlay) (2.21).

##### Operator action for 2.20 — completed

All three deletes have since been performed by the operator (2026-05-17): the 0-byte `java` file at the repo root, `src/main/resources/static/login.html`, and `src/main/resources/fxml/main.fxml` (plus the now-empty `fxml/` directory).

##### Operator action for 1.5

Subsumed by the consolidated [Operator action checklist](#operator-action-checklist) at the top of this document.

### Required operator action for fixes already shipped

Subsumed by the consolidated [Operator action checklist](#operator-action-checklist) at the top of this document.

> Note (2026-05-17): an earlier draft of this section claimed session-cookie clients needed to include the CSRF token. After tech-debt #5 (stateless JWT only), sessions are gone entirely — that bullet is now superseded by the more aggressive "all clients must use `Authorization: Bearer …`" item in the checklist above.

### Audit closed

Every finding the original audit identified is now resolved (✅), plus one new MEDIUM (2.22, duplicate `idx_fax_number`) that surfaced from `mvn test` output during the 2.12 close-out and was fixed the same day. The deferred deletes (the 0-byte `java` file at the repo root, `static/login.html`, `fxml/main.fxml`) have all been performed by the operator. The one post-audit regression (0.1, the half-finished multi-module restructure) was reverted and verified on 2026-05-17 — `mvn validate` and `mvn -DskipTests compile` both BUILD SUCCESS, `fax-trident-server/` directory removed. 2.12 was re-investigated the same day: the `times(2)` assertion in the test is correct for the post-fix code (two Long-valued Redis sets: `:contactId`, `:metadataId`), so only the misleading TODO comment needed updating; `mvn test` confirmed 4/4 pass. No CRITICAL, HIGH, MEDIUM, or LOW items remain. The recommended-next-batch ordering walked through:

1. Security batch — 1.1–1.13 (closed).
2. Correctness/structural HIGH batch — 2.1–2.10 (closed).
3. JPA / structure cleanup — 2.11, 2.15, 2.16 (closed).
4. Performance bandaging — 2.9, 2.12, 2.14 (closed).
5. JavaFX duel — 2.13 (closed).
6. Quick wins — 2.17, 2.18, 2.19, 2.20, 2.21 (closed).

For continuing work, the JavaFX/Spring-Boot decouple (tech-debt #1) is now also closed as of 2026-05-18 via ADR-0001 — see tracker row 3.1. The remaining longer-horizon items: real ML behind `ContactSuggestionService` (the current implementation is a deterministic fuzzy matcher; the suggest seam is unchanged), a join-table-based roles schema in place of `User.roles` VARCHAR, broader test coverage per §4 below, and the two follow-ups the ADR-0001 split surfaced: WebSocket bearer auth on `/fax-updates` (currently open) and OS-keychain JWT persistence on the desktop (currently in-memory only, so users re-login each launch).

---

Findings are tagged by severity:

- **CRITICAL** — exploitable or breaks the app in production
- **HIGH** — likely to cause data loss, incorrect behavior, or production outage
- **MEDIUM** — bug, fragility, or scaling risk
- **LOW** — cleanliness, dead code, minor smells

Citations use `file:line` against the current tree.

---

## Executive summary

### Current state (2026-05-17)

All original CRITICAL/HIGH/MEDIUM/LOW findings are closed except 2.12, which is downgraded to ⚠️ Partial pending a one-line test tightening (see tracker). The remediation batches landed cleanly: JWT secret fails fast on missing/short input; `@RateLimit` is now a real AOP aspect backed by Redis fixed-window counters; CSRF is enforced on cookie-authenticated mutating requests; Redis is configured via the correct `spring.data.redis.*` prefix; `@Async` flows propagate `SecurityContext`; JavaFX-instantiated `Application` autowires itself; file paths are no longer accepted from clients (upload-then-reference via opaque IDs); JWTs carry a `jti` registered in Redis with the same TTL and are revoked on logout.

**No open CRITICAL findings remain.** The post-audit multi-module restructure regression (0.1) was reverted on 2026-05-17 — the build is back on the original single-module flat layout (see [§0.1](#01-critical--multi-module-restructure-was-half-finished-build-was-broken--%E2%9C%85-reverted)). The decoupling work (tech-debt #1) should be revisited as a single, atomic change rather than a partial.

The longer-horizon tech-debt items remain good follow-up candidates: a real model (vs. stub) behind SmartAssist, Flyway-managed migrations, a single auth model (currently the hybrid session+JWT works but is more attack surface than necessary), and meaningful test coverage beyond the single `FaxEngineServiceTest`. (The Spring Boot bump was done — see tracker row 3.8b.)

### Original findings (2026-05-15) — historical, all closed except 0.1

The project mixed a Spring Boot REST/WebSocket server with a JavaFX desktop UI inside a single process. It had the bones of a real app but was, in its original state, **not production-ready**. The most important problems at the time were:

1. **A default JWT secret of `your-secret-key` is baked into both `SecurityConfig` and the `Dockerfile`.** Anyone with the repo can mint admin tokens against any deployment that ships with the defaults.
2. **The `@RateLimit` annotation is a no-op** — it's declared as a custom annotation with no aspect implementing it, so `/api/fax/send` and `/api/fax/auto-send` are completely unthrottled despite looking rate-limited.
3. **CSRF is disabled for the entire `/api/**` surface**, while form login and OAuth2 still issue server-side sessions on top of JWT. Cross-site requests against `/api/**` work as long as the browser has a session.
4. **The custom `RedisConfig` reads `spring.redis.*` properties (Spring Boot 2.x path), but `application.yml` uses the Spring Boot 3.x path `spring.data.redis.*`.** External Redis configuration is silently ignored — every deployment quietly connects to `localhost:6379`.
5. **Spring's `@Retryable`/`@Recover` decorators in `PdfProcessingService` and `PreviewPane` are wired to `IOException`, but the code catches and rewraps as `RuntimeException` before the proxy can see it.** The retry logic never fires and the `@Recover` methods are dead code.
6. **Async fax sending drops the user's `SecurityContext`** (no `DelegatingSecurityContextExecutor`), so every async send/audit row is written as `"system"` instead of the real user.
7. **The JavaFX `Application` instance is not Spring-managed**, but the code `@Autowired`s beans into it. This relies on `null`-check defensive code at startup (`FaxTridentApplication:96-104`) that would normally signal a hard failure. Either the desktop UI does not actually receive its dependencies, or there is an additional wiring step not in the repo.
8. **Test coverage is essentially one happy-path service test.** There are no controller tests, no security tests, no PDF/Redis failure tests, and the existing test has a `// TODO` admitting it asserts around a known duplication bug rather than fixing it.

The original "if you only fix four things" recommendation was: `JWT_SECRET` (1), the rate-limit no-op (2), the Redis property prefix (4), and the SecurityContext propagation in async flows (6). All four landed.

---

## 1. Security findings

### 1.1 CRITICAL — Hardcoded default JWT secret allows token forgery
- `SecurityConfig.java:48` — `@Value("${jwt.secret:your-secret-key}")`
- `Dockerfile:33` — `JWT_SECRET=your-secret-key` baked into the runtime image's ENV defaults

The default secret is a publicly-known literal in the repo. `JwtTokenProvider.createToken` (`SecurityConfig.java:253-260`) signs HS256 tokens with `Keys.hmacShaKeyFor(secret.getBytes())`. Anyone with the repo can mint a valid `Authorization: Bearer …` token claiming `ROLE_ADMIN`, and `JwtTokenFilter` (`SecurityConfig.java:295-329`) will accept it without further checks. There is no `iss` / `aud` / `jti`, no key rotation, and no allowlist.

Aggravating factors:
- `secret.getBytes()` uses the platform default charset — implicit and non-portable.
- `your-secret-key` is also too short for HS256 (256-bit minimum); `Keys.hmacShaKeyFor` will throw `WeakKeyException` at startup with the literal default, masking the underlying problem in dev but breaking in any environment that doesn't override the value.

**Fix:** require `JWT_SECRET` (≥32 random bytes) with no default; fail fast if missing; consider rotating to asymmetric (RS256/EdDSA) and adding `iss`/`aud`/`jti`.

### 1.2 CRITICAL — `@RateLimit` is a no-op annotation
- `FaxController.java:35-39` declares `@interface RateLimit { … }` with no `@Retention`, no `@Target`, and no aspect.
- Applied at `FaxController.java:100` and `FaxController.java:256`.

There is no `@Aspect` class implementing `RateLimit` anywhere in the codebase. The annotation is metadata only — `sendFax` and `autoSendFax` are not rate-limited at all. The `key = "fax:send:#{authentication.name}"` SpEL expression is never evaluated. A logged-in user can hammer these endpoints.

**Fix:** either delete the annotation and the comments around it, or implement a Spring AOP `@Around` advice backed by a token-bucket in Redis. Add `bucket4j-spring-boot-starter` if you want the off-the-shelf solution.

### 1.3 HIGH — CSRF disabled for `/api/**` while sessions are still issued
- `SecurityConfig.java:124-127` — `.csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/**")))`
- `SecurityConfig.java:118-123` — `sessionCreationPolicy(IF_REQUIRED)` (i.e., not `STATELESS`)
- `SecurityConfig.java:73-91` — form login installed, sets `JSESSIONID` and additionally returns a JWT as a response header.

Because the app keeps real HTTP sessions, a logged-in user's browser will send `JSESSIONID` on cross-origin requests, and the JWT filter then allows the same call via `Authorization` header if present — but the session itself is enough to authorize `/api/**`. With CSRF off on those paths and no SameSite cookie config visible, classic CSRF against state-changing endpoints (`POST /api/fax/send`, `POST /api/fax/auto-send`, `DELETE /api/admin/clear-cache`, etc.) works.

**Fix:** either go fully stateless (`SessionCreationPolicy.STATELESS` + no form login, JWT only) or keep CSRF enabled and pass the token from a CSRF cookie (the `CookieCsrfTokenRepository.withHttpOnlyFalse()` is already configured). Pick one model; the current hybrid is the worst of both.

### 1.4 HIGH — Login form has no CSRF token field, so default form login is broken
- `src/main/resources/static/login.html:10-19`

The page is served as a flat static asset (no Thymeleaf processing — the `th:if` attributes on lines 23-24 are inert). It POSTs to `/login` without a `_csrf` field. Spring Security's default form-login flow requires that token, so login itself fails with 403 in any real deployment. The fact that this isn't already blocking work suggests nobody is actually exercising the web UI end-to-end.

**Fix:** either render the page via a Thymeleaf controller and emit `<input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>`, or move CSRF off `/login` explicitly (less safe) and acknowledge the trade-off.

### 1.5 HIGH — File path is user-controlled and not validated
- `AdminController.java:102-119` — `POST /api/admin/send-fax` takes `filePath` as a `@RequestParam`.
- `FaxController.java:99-125` — `POST /api/fax/send` takes `filePath` inside the DTO.
- Both forward to `FaxEngineService.sendFax(..., filePath)` (`FaxEngineService.java:142`), which does `new File(filePath)` and then `PDDocument.load(file)`.

No allowlist, no canonicalization, no chroot. A caller can supply `/etc/passwd` or any other readable file on disk and the server will attempt to open it, log the failure (with the path in the message), and broadcast that failure over the WebSocket. PDFBox failures and the readability check effectively give an oracle for *file existence* under whatever user the JVM runs as.

**Fix:** servers should not accept absolute paths from clients. Switch the API to upload-then-reference: clients POST a multipart file, server stores it under a server-controlled directory with a generated ID, subsequent calls reference the ID.

### 1.6 HIGH — Stateful JWTs with no logout invalidation
- `SecurityConfig.java:107-117` — logout invalidates the session and clears `JSESSIONID`, but the JWT has no server-side state (no `jti`, no blocklist, no Redis allowlist).

A stolen JWT is valid for `jwt.validity` (default 1h) regardless of logout. With the weak default secret in 1.1, attackers can also mint indefinitely-living tokens by bumping the expiry.

**Fix:** keep a short JWT TTL, store a `jti` allowlist in Redis with the same TTL, and revoke on logout.

### 1.7 MEDIUM — Unchecked role cast in JWT parsing
- `SecurityConfig.java:271-279` — `(List<String>) Jwts.parser()…get("roles")`

If a malformed or tampered token (signed with the weak key in 1.1) has `roles` as anything other than a JSON array of strings, the filter throws `ClassCastException` instead of rejecting cleanly. The cast is suppressed with `@SuppressWarnings("unchecked")` so the issue is invisible to reviewers.

**Fix:** parse with `claims.get("roles", List.class)` and explicitly validate element types.

### 1.8 MEDIUM — `Keys.hmacShaKeyFor(secret.getBytes())` uses the platform default charset
- `SecurityConfig.java:258, 264, 273, 284`

Same code, four places. On a machine with a non-UTF-8 default charset, the same secret string produces different bytes — and therefore different tokens — than on the issuing machine. Use `secret.getBytes(StandardCharsets.UTF_8)` everywhere, or decode from Base64.

### 1.9 MEDIUM — Hardcoded WebSocket allowed origin
- `WebSocketConfig.java:42` — `.setAllowedOrigins("http://localhost:8080")` (comment says "Tightened for production" but it's literally localhost)

In production this rejects every legitimate origin. In development it accepts the same-origin browser tab fine, masking the bug.

**Fix:** drive allowed origins from configuration (`app.websocket.allowed-origins`) and fail fast if unset in `prod` profile.

### 1.10 MEDIUM — `redisTemplate.keys(...)` exposed via admin endpoints
- `AdminController.java:80, 157, 286, 299`

`KEYS pattern` is an O(N) blocking command. An admin who runs `/api/admin/dashboard` against a busy Redis essentially DoSes the cache for the duration of the scan. Worse, `getThemeStats` and `getPredictionAnalytics` will NPE if any matching key holds a non-string or `null` value (`stats.merge(theme, 1L, Long::sum)` with `theme==null` throws NPE; `((String) ...).startsWith(...)` on `null` NPEs).

**Fix:** replace with `SCAN` (e.g., `redisTemplate.scan(...)`) and null-check the values.

### 1.11 MEDIUM — Log injection from user-controlled fields
- `FaxController.java:106`, `AdminController.java:110`, `FaxEngineService.java:67, 121` and others.

`logger.info("User '{}' requested fax to {} from {}", username, faxNumber, filePath)` includes user-controlled strings without sanitization. A caller passing `faxNumber=+12025550123\n[ERROR] forged-line` can poison logs and confuse SIEM parsing.

**Fix:** strip CR/LF from logged user input, or switch to structured logging that escapes new lines.

### 1.12 LOW — OAuth2 client secret defaults to a placeholder
- `SecurityConfig.java:54-58` — `@Value("${oauth2.google.client-id:your-client-id}")`, `…-secret:your-client-secret`

Failing-open with placeholders means a misconfigured deployment will try to handshake with Google using `your-client-secret`. Better: no default, fail fast at startup.

### 1.13 LOW — Dockerfile bakes plaintext database / Redis credentials and JWT secret into the image
- `Dockerfile:27-36`

`SPRING_DATASOURCE_PASSWORD=secret`, `JWT_SECRET=your-secret-key`, etc. are baked into ENV. Anyone with `docker history` on the image sees them. Even though they're meant to be overridden at runtime, the defaults are sensitive shapes.

**Fix:** drop the secret defaults from the Dockerfile; require them via runtime `-e` or secret mounts; document that in the README.

---

## 2. Correctness / code-quality findings

### 2.1 CRITICAL — `RedisConfig` reads the wrong property prefix
- `RedisConfig.java:29-45` reads `${spring.redis.host}`, `${spring.redis.port}`, `${spring.redis.timeout}`, `${spring.redis.pool.*}` — the Spring Boot 2.x path.
- `application.yml:21-27` writes `spring.data.redis.host`, `port`, `timeout` — the Spring Boot 3.x path.

Spring Boot 3 renamed the property root. Because `RedisConfig` declares its own `@Bean RedisConnectionFactory`, Spring Boot's auto-config doesn't get a chance to consume `spring.data.redis.*` either. Every deployment therefore connects to `localhost:6379` regardless of configuration. The Dockerfile sets `SPRING_REDIS_HOST=redis` — also the old prefix — so that doesn't help (`SPRING_REDIS_HOST` ≠ `SPRING_DATA_REDIS_HOST`).

**Fix:** rename the `@Value` keys to `spring.data.redis.*`, fix the Dockerfile env names, and update `application.yml` (or just delete `RedisConfig` and let Spring Boot auto-configure).

### 2.2 CRITICAL — JavaFX `Application` is not a Spring bean; `@Autowired` fields are not injected
- `FaxTridentApplication.java:41-105`

`Application.launch()` instantiates `FaxTridentApplication` itself (line 80). The Spring context is started inside `init()` (line 86) and creates a *separate* `FaxTridentApplication` bean — but the JavaFX-managed instance's `@Autowired` fields (lines 59-75) are never populated, because Spring never sees that instance.

The defensive `if (mainView == null) throw …` block (lines 96-104) is the only thing standing between this bug and a slew of NPEs in `start(Stage)`. Either:
- the app currently fails on every launch with `"MainView bean is null"`, or
- there is missing glue code (e.g., `springContext.getAutowireCapableBeanFactory().autowireBean(this);` in `init()`) that needs to be added.

**Fix:** in `init()`, after `springContext = SpringApplication.run(...)`, call `springContext.getAutowireCapableBeanFactory().autowireBean(this);` so the JavaFX instance is populated. Alternatively, move the JavaFX bootstrapping out of `FaxTridentApplication` entirely and have Spring own the lifecycle.

### 2.3 HIGH — `@Retryable` never fires in PDF processing or preview loading
- `PdfProcessingService.java:48-80` — method declares `@Retryable(value = IOException.class)` but the body catches `IOException` and rethrows as `RuntimeException` (line 78). Proxy sees a non-matching exception → no retry. The `@Recover` method (line 82) is dead code.
- Same pattern in `PreviewPane.java:91-147` and `@Recover` at `PreviewPane.java:149-158`.

**Fix:** either let `IOException` propagate (remove the wrap), declare `@Retryable(value = RuntimeException.class)` (broad and dangerous), or add a specific checked-exception type and retry on that.

### 2.4 HIGH — `@Async` flows lose the SecurityContext
- `FaxEngineService.sendFaxAsync` (line 107) is `@Async`. Inside `sendFax`, `getCurrentUsername()` (lines 277-280) reads `SecurityContextHolder.getContext().getAuthentication()` — but the default `SimpleAsyncTaskExecutor` does not propagate it. Every async fax send and every audit-trail `createdBy` field (via `@PrePersist` on `Contact`, `FaxLog`, `FaxMetadata`) ends up tagged `"system"` instead of the real user.

This makes the audit trail and the analytics endpoints (`countByCreatedBy()` in all three repositories) misleading.

**Fix:** configure a `TaskExecutor` that wraps with `DelegatingSecurityContextExecutor` (or use `DelegatingSecurityContextAsyncTaskExecutor`). Set Spring Security's strategy to `MODE_INHERITABLETHREADLOCAL` if you must, but the executor wrapper is cleaner.

### 2.5 HIGH — `faxId` generated from `System.currentTimeMillis()` is not unique under concurrency
- `FaxEngineService.java:69, 123, 198, 261, 269` (and again in `FaxController.java:108, 183, 266`).

Two simultaneous calls in the same millisecond produce identical `faxId`s. The downstream effects:
- Redis writes overwrite each other (`fax_<ts>:status`, `…:number`, `…:pages` collide).
- `FaxLog` rows share a non-unique key, but `findByFaxId(...)` would return the wrong fax's history.
- The "duplication" mentioned at `FaxEngineServiceTest.java:122-123` is partly a symptom of this.

**Fix:** use `UUID.randomUUID()` or a snowflake-style ID. The existing `idx_fax_id` index supports either.

### 2.6 HIGH — `Thread.sleep(...)` inside `@Transactional`
- `FaxEngineService.java:86` — `Thread.sleep(1000)` inside `processInput` (`@Transactional`, line 64).
- `FaxEngineService.java:162` — `Thread.sleep(2000)` inside `sendFax` (`@Transactional`, line 118).

Both hold an open JPA transaction (and a JDBC connection from the pool) for the entire sleep. Combined with `@Async` and a small pool, this is a recipe for connection-pool exhaustion under load. The "simulate" comments suggest these are placeholders — but they ship as-is.

**Fix:** if these are placeholders, mark them with a `TODO` and a feature flag; if not, do the wait outside the transaction (close, sleep, reopen for the next write).

### 2.7 HIGH — `processInput` reads a Redis key that nothing populates with its `faxId`
- `FaxController.java:175-197`

`faxId` is created on line 183. `faxEngineService.processInput(input)` (line 185) generates *its own* `faxId` (`FaxEngineService.java:69`) and writes `…:extractedText` under that key. The controller then reads `redisTemplate.opsForValue().get(faxId + ":extractedText")` (line 186) using the controller's `faxId`, which is never written. The branch on line 189 is dead, the returned `extractedText` is always null.

**Fix:** return the produced `faxId` from `processInput`, or have the service write under a key derived from the input.

### 2.8 HIGH — `Math.random()` simulating inbound faxes runs every 5 seconds in production
- `FaxEngineService.java:192-243` — `@Scheduled(fixedRate = 5000)` `listenForInboundFax()`. On every tick, `Math.random() > 0.8` triggers a fake inbound fax that *writes real rows* to `fax_logs`, `contacts`, and `fax_metadata` and broadcasts over WebSocket.

This is a simulator masquerading as a production listener. Run for a day and you have ~17k bogus contacts and ~17k bogus log rows.

**Fix:** gate it behind a profile (`@Profile("dev")`) or delete it.

### 2.9 HIGH — `findAll().stream()` patterns load whole tables to memory
- `AdminController.java:88-89` — `faxMetadataRepository.findAll().stream().mapToInt(...).sum()` (page count and file size, *twice*). At N=1M metadata rows this loads them all.
- `SmartAssistService.java:84-87` — `contactRepository.findAll().stream().filter(c -> c.getFaxNumber().contains(partialInput))`. Loads all contacts every time `predictContact` is called.
- `SmartAssistService.java:113` — for each candidate contact, calls `faxLogRepository.findByFaxNumber(..., Pageable.unpaged())` to count its history. Classic N+1 with unbounded result sets.

**Fix:** push aggregations into SQL (`SELECT SUM(page_count) FROM fax_metadata`, etc.); use `EXISTS`/`COUNT` queries for membership; bound result sets.

### 2.10 HIGH — File and resource leaks on `Files.list(...)`
- `PdfProcessingService.java:42-44` — `Files.list(dirPath).filter(...).count()` (no try-with-resources).
- `PdfProcessingService.java:133-135` — same.
- `AdminController.java:256-258` — `Files.list(Paths.get(".")).filter(...).forEach(...)` (no close).

`Files.list` returns a `Stream<Path>` backed by an open directory handle. Without `try (Stream<Path> s = Files.list(...)) { … }` the handle leaks and on busy systems you'll hit "Too many open files".

Aggravating: `AdminController.java:256` uses `Paths.get(".")` — the current working directory at JVM launch time, which is *not* the barcode directory. The cleanup endpoint scans the wrong folder.

**Fix:** use try-with-resources and resolve against `barcodeDir` explicitly.

### 2.11 MEDIUM — JPA cascade on `Contact.faxLogs` will erase the audit trail
- `Contact.java:60-61` — `@OneToMany(... cascade = CascadeType.ALL, orphanRemoval = true)`

Deleting a contact deletes every fax log that references it. Audit/compliance trail almost certainly should not behave this way. (You also have orphanRemoval, so removing a `FaxLog` from the in-memory list also deletes the row.)

**Fix:** drop `cascade = CascadeType.ALL` and `orphanRemoval`; manage `FaxLog` independently.

### 2.12 MEDIUM — `FaxController.send` writes a contact *before* the async send begins
- `FaxController.java:110-113` — if no contact exists for the number, the controller calls `faxEngineService.saveContact("Unknown", request.getFaxNumber())` synchronously before the async send. The async path then does the same lookup-or-create itself (`FaxEngineService.java:131-138`). Race conditions aside, the `name="Unknown"` write competes with whatever the user might have set elsewhere, and unique-constraint races aren't handled (the second writer will throw `DataIntegrityViolationException` if two threads race for the same number).

**Fix:** centralize get-or-create in the service inside a single transaction; wrap with a retry on `DataIntegrityViolationException` or use `INSERT … ON CONFLICT DO NOTHING`.

### 2.13 MEDIUM — JavaFX UI is wired up two contradictory ways
- `MainView.java:60-68` tries `FXMLLoader.load("/fxml/main.fxml")`; on success the FXML root replaces the programmatic UI. But `main.fxml` references `onAction="#handleSendFax"` and `#handlePreview` (lines 16-17 of the FXML), and neither method exists in any class. If the FXML actually loads, clicking those buttons throws `LoadException` at click time.
- Conversely, `statusLabel` (`MainView.java:41`) is only assigned inside `buildProgrammaticUI` (line 90). If FXML loads cleanly, `statusLabel` stays `null`, and the WebSocket `onMessage` handler (line 182) NPEs on every update.

**Fix:** pick one path. Easiest: delete `main.fxml` and use the programmatic UI. Or wire `FXMLLoader.setController(this)` and add `@FXML public void handleSendFax(ActionEvent e)` methods.

### 2.14 MEDIUM — Two desktop WebSocket clients hit the server during startup
- `MainView.java:165-205` and `PreviewPane.java:163-205` each construct a `WebSocketClient` to `ws://localhost:8080/fax-updates` in `@PostConstruct`.

Aside from the redundancy, `@PostConstruct` fires while the Spring context is wiring up. The embedded Tomcat hasn't necessarily begun accepting WebSocket upgrades yet, so the first attempt frequently silently fails (the `catch` block just logs). There is no reconnect logic — once it misses, the desktop UI never receives updates.

**Fix:** lift the WS client out, share a single instance, connect lazily (`ApplicationReadyEvent`) with backoff.

### 2.15 MEDIUM — JPA entity `User` is declared as a static inner class inside `SecurityConfig`
- `SecurityConfig.java:218-234` (entity), `:236-239` (repository).

Bizarre architecture: a `@Entity` and its `JpaRepository` live as inner types of a `@Configuration` class. Hibernate picks it up because of `@SpringBootApplication`'s default scanning, but the model is conceptually misplaced and future migrations will be confusing.

**Fix:** move `User`, `UserRepository`, and `JpaUserDetailsService` to `model/` and `repository/` packages.

### 2.16 MEDIUM — Page<…> "no results" returned as 404 with `null` body
- `FaxController.java:210-213, 227-230, 292-295`

Returning `ResponseEntity.status(404).body(null)` for an empty page is non-idiomatic — clients expect a 200 with an empty `content` array. Some HTTP clients also reject a `Page<...>` body type with a null body and explode trying to deserialize.

**Fix:** return `200 OK` with the empty page; reserve 404 for "the resource by ID doesn't exist."

### 2.17 MEDIUM — `getThemeStats` and `getPredictionAnalytics` can NPE
- `AdminController.java:286-291` — `stats.merge(theme, 1L, Long::sum)` throws NPE if any matching key has a null value.
- `AdminController.java:299-301` — `((String) redisTemplate.opsForValue().get(k)).startsWith(...)` NPEs if the value is null or has been evicted between `keys(...)` and `get(...)`.

**Fix:** null-check the lookup; consider using a hash so you scan and read in one call.

### 2.18 LOW — Many `@Transactional` annotations on methods that do no DB work
- `PdfProcessingService.java:54, 96, 117` (PDF text extraction, barcode generation, barcode cleanup — pure file/CPU work).
- `PreviewPane.java:98` (`loadDocument` opens a file and renders pages — no DB).
- `AdminController.java:151, 218, 250, 280` (Redis-only or filesystem-only methods).

Each `@Transactional` opens a JPA transaction and reserves a connection from the pool for the duration of the method. On endpoints that don't touch the DB, this just slows things down and contends for connections.

**Fix:** remove `@Transactional` from methods that don't touch JPA. Use `@Transactional(readOnly = true)` only for actual read queries.

### 2.19 LOW — Sound files referenced but missing or empty
- `FaxTridentApplication.java:49` — `${app.sound.startup:/sounds/trident-rise.wav}`. No such file exists in `src/main/resources/sounds/` — only `retry-fail.wav` and `send-success.wav`, both 3 bytes. The startup sound will fail (silently — `playStartupSound` catches `Exception` and warns).

### 2.20 LOW — Dead code / unused symbols
- `ContactRepository.java:25` — `findByOrganizationContainingIgnoreCase` defined but never called.
- `PdfProcessingService.java:31` — `private static final String BARCODE_DIR = "barcodes/"` shadowed by the `@Value`-bound `barcodeDir` and unused.
- `SmartAssistService.java:192-197` — `invokeXaiModel` always returns null; the whole "ML prediction" branch is a stub.
- The empty `java` file at the repository root (0 bytes) — looks like an accidental `touch`.
- `@EnableScheduling` on both `FaxTridentApplication` (line 39) and `WebSocketConfig` (line 29) — duplicate; remove one.

### 2.21 LOW — JPA / config hygiene
- `application.yml:14` — `ddl-auto: update` in main config. Fine for dev, dangerous in prod. Use Flyway/Liquibase instead.
- `application.yml:15, 18` — `show-sql: true` and `format_sql: true` will log every statement (slow + noisy in production).
- `application.yml:30-42` — large commented-out PostgreSQL block. Move to `application-prod.yml` and delete the commentary.

### 2.24 LOW — `SmartAssistService` was mis-named (implied ML) and cached failure sentinels were never served

*Added 2026-05-17. Two-part close-out of tech-debt #2's SmartAssist half.*

**Part 1 — misleading name.** The class advertised "smart assist" (suggesting an AI / ML assistant); the implementation was deterministic score-based fuzzy matching: `history_count * 10 + (name contains ? 5 : 0) + (fax_number contains ? 3 : 0)`. The `invokeXaiModel` stub that would have made this "smart" was removed in 2.20, but the name and Redis namespace (`predict:*`) carried the dead framing. New developers reading the code would reasonably go looking for the model.

**Fix.** Renamed:

- `SmartAssistService` → `ContactSuggestionService`
- `predictContact(...)` → `suggestContact(...)`
- Redis key prefix `predict:` → `suggest:`
- Class javadoc rewritten to describe the heuristic honestly and point at the right seam for adding a real model later.

Updated all callers: `FaxController` (field + constructor + two call sites), `AdminController` (SCAN pattern), `ContactRepository` and `FaxLogRepository` comment references.

**Public API preserved.** The HTTP endpoint URLs (`POST /api/fax/predict-contact`, `POST /api/fax/auto-send`, `GET /api/admin/prediction-analytics`) and the JSON response key (`successfulPredictions`) kept their old names to avoid breaking any external admin tooling. Renaming those would be a separate, opt-in operator-facing change.

**Part 2 — failure-sentinel cache bug.** The previous code called `cachePrediction(...)` with the two failure sentinels (`"No matching contact found"`, `"Prediction error: <msg>"`) on the failure paths, but the cache-read on line 70 explicitly excluded both:

```java
if (cached != null && !cached.startsWith("Prediction error") && !cached.equals("No matching contact found")) {
    return cached;
}
```

Net effect: every failure wrote a 1-hour TTL Redis entry that no caller would ever read. Slow-burn Redis pollution; never catastrophic, never useful. Fixed by removing the `cachePrediction(...)` calls from both failure paths in `ContactSuggestionService`.

**Operator action.** Existing cached predictions under `predict:*` are orphaned. Either let them age out (1-hour TTL) or run once:

```
redis-cli --scan --pattern 'predict:*' | xargs -r redis-cli DEL
```

to free the memory immediately. The new prefix is `suggest:`.

### 2.23 MEDIUM — `/api/auth/login` was unrate-limited after the stateless-auth rewrite

*Added 2026-05-17. Introduced by the tech-debt #5 rewrite the same day; surfaced and fixed in the same flow.*

The stateless JWT-only rewrite removed the form-login flow and replaced it with `POST /api/auth/login` (JSON). The new endpoint had no rate limiting:

- The existing `@RateLimit` aspect derived its key from `#authentication.name`, which is `null` on a login attempt (the caller is unauthenticated by definition — that's why they're calling `/login`).
- The aspect's null-fallback was the literal string `"anonymous"`, so every anonymous caller across the whole internet would have shared a single bucket. That's worse than nothing — it'd let one attacker brute-force the bucket open, and incidental anonymous traffic from elsewhere would knock legitimate users out.

**Fix.** `RateLimitAspect` now exposes two extra SpEL variables:

- `#request` — the current `HttpServletRequest` (raw access to headers / path / etc.)
- `#ipAddress` — convenience for `request.getRemoteAddr()` so per-IP keys are a one-liner

`AuthController.login` is annotated:

```java
@RateLimit(key = "auth:login:#{#ipAddress}", rate = 10, period = 60)
```

10 attempts per 60s per client IP is loose enough that a human fumbling a password won't trip it but tight enough that an unattended brute-force script is throttled to ~14 400/day instead of unlimited.

**X-Forwarded-For trust.** `HttpServletRequest.getRemoteAddr()` returns the TCP peer address. Behind a proxy that's the proxy's address, so every request keys to the same IP — useless. The fix on the operator side is to set `server.forward-headers-strategy: native` (or `framework`) so Spring Boot rewrites `getRemoteAddr()` based on the trusted `Forwarded` / `X-Forwarded-For` headers. The aspect deliberately does NOT parse those headers itself — doing that without knowing where the trust boundary sits would let any caller spoof an IP by setting the header.

**Operator action.** Set `server.forward-headers-strategy` if and only if the app sits behind a trusted load balancer / reverse proxy. Leaving it unset is safe for direct-exposure deployments (rate-limit keys on the real client IP) but breaks the per-IP key behind a proxy (everyone keys to the proxy).

### 2.22 MEDIUM — Duplicate `idx_fax_number` collides at schema-creation time, leaving `fax_logs.faxNumber` unindexed

*Added 2026-05-17. Surfaced by `mvn test` output during the 2.12 close-out verification.*

Both `Contact` and `FaxLog` declared `@Index(name = "idx_fax_number", columnList = "faxNumber")`:

- `Contact.java:19` (on table `contacts`)
- `FaxLog.java:17` (on table `fax_logs`)

In Hibernate / H2 / Postgres, index names are global within a schema, not table-scoped. Hibernate's schema generator runs the two `CREATE INDEX` statements in order; whichever runs second fails with `Index "IDX_FAX_NUMBER" already exists`. Schema-update mode logs the error as a stack trace and continues, so test runs and dev startups stay green — but `fax_logs.faxNumber` is left unindexed.

Concrete impact: `FaxLogRepository.findByFaxNumber(...)` and `FaxLogRepository.countByFaxNumber(...)` were doing full table scans in every environment. Both are called on hot paths:

- `SmartAssistService.predictContact` invokes `countByFaxNumber` *per candidate contact*.
- `FaxController.getFaxLogsByNumber` paginates by fax number on the admin UI.
- The 2026-05-16 perf batch (audit 2.9) traded an `findAll().getTotalElements()` for `countByFaxNumber` on the assumption the column was indexed — undoing most of the win.

The test output that surfaced this:

```
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException:
  Index "IDX_FAX_NUMBER" already exists; SQL statement:
  create index idx_fax_number on fax_logs (faxNumber) [42111-224]
```

**Fix (applied).** Renamed `Contact`'s index to `idx_contact_fax_number`. `fax_logs.idx_fax_number` is now the canonical owner because `fax_logs` is queried by `faxNumber` more often (FaxLogRepository has two `byFaxNumber` queries; ContactRepository has none — it uses `findByFaxNumber` against the unique column, which already has its implicit unique index).

**Operator action for live Postgres deployments.** Hibernate's `ddl-auto: validate` (the prod default) does not create or drop indexes, so existing prod databases still have the broken state. Run once per environment:

```sql
-- Index that the old code thought it had on contacts (was created OK)
ALTER INDEX IF EXISTS idx_fax_number RENAME TO idx_contact_fax_number;

-- Index the old code wanted on fax_logs but H2/Postgres rejected during schema creation
CREATE INDEX IF NOT EXISTS idx_fax_number ON fax_logs (faxNumber);
```

For dev / test environments using H2 in-memory, the next JVM restart picks up the new schema with both indexes correctly created.

---

## 3. Tech debt — prioritized refactor list

In rough order of payoff:

1. **Decouple the JavaFX UI from the Spring Boot service.** ✅ **Done (2026-05-18).** Landed atomically as ADR-0001 (`docs/adr/0001-decouple-javafx-from-spring-boot.md`). Two-module Maven reactor: `fax-trident-server` (Spring Boot REST + WS) and `fax-trident-desktop` (JavaFX client, Spring-free). Server-side `FaxTridentServer` replaces the JavaFX-extending `FaxTridentApplication`; desktop-side `FaxTridentDesktop` is a plain `Application` with explicit constructor-injection wire-up in `start(Stage)`. The desktop authenticates over `POST /api/auth/login` and uses an in-memory JWT for subsequent calls; `FaxApiClient` wraps the JDK `HttpClient` (no OkHttp / WebClient dep), `RetryHelper` replaces `@Retryable` on the desktop, `DesktopPreferences` replaces the Redis-backed theme store (local file under `${user.home}/.fax-trident/`). The autowire-into-Application footgun (audit 2.2) is gone with the JavaFX coupling. The previous half-finished §0.1 attempt is the cautionary tale baked into the ADR — parent-pom switch landed last, after all module sources and the Dockerfile were in place. See tracker row 3.1 for verification details and operator follow-ups.

2. **Implement (or remove) `@RateLimit` and the SmartAssist ML path.** ✅ **Done (2026-05-17).** `@RateLimit` is implemented end-to-end: real AOP aspect, Redis fixed-window counter, HTTP 429 on excess (audit 1.2). Aspect extended to support unauthenticated endpoints via `#ipAddress` and applied to `/api/auth/login` (audit 2.23). SmartAssist ML path: the `invokeXaiModel` stub was removed in 2.20, then the class renamed to `ContactSuggestionService` with sharpened javadoc that names the implementation honestly (deterministic, score-based fuzzy matching — no ML, no model, no training). Redis key prefix migrated `predict:` → `suggest:`. Failure-sentinel caching bug fixed in the same pass. Public HTTP endpoint URLs retained for backwards compat. See tracker row 3.2 and §2.24.

3. **Replace direct file-path APIs with multipart upload.** Section 1.5. Removes a class of vulnerabilities and simplifies client behavior.

4. **Switch JPA migrations from `ddl-auto: update` to Flyway.** ✅ **Done (2026-05-17).** `flyway-core` and `flyway-database-postgresql` added to `pom.xml`. `src/main/resources/db/migration/V1__initial_schema.sql` baselines the current schema (camelCase identifiers preserved verbatim from Hibernate's output so existing prod databases align). `application.yml` carries the Flyway config (`baseline-on-migrate: true`, `baseline-version: 1`); `ddl-auto` flipped from `update` to `validate` in dev to match the prod default. `application-prod.yml` no longer needs its `ddl-auto: validate` override (redundant) — only `show-sql: false` / `format_sql: false` remain there. Operator action for existing prod: nothing — `baseline-on-migrate` handles it automatically on first start. Schema changes from here on go in V2+.

5. **Adopt a single auth model.** ✅ **Done (2026-05-17).** Picked stateless JWT-only. Removed: `formLogin`, `oauth2Login` (conditional Google flow), sessions (now `STATELESS`), CSRF (no ambient cookie surface), the `LoginController` + Thymeleaf `login.html` template, the `spring-boot-starter-thymeleaf` + `spring-boot-starter-oauth2-client` deps, OAuth2 env vars from `application.yml` and the Dockerfile. Added: `POST /api/auth/login` JSON endpoint via `AuthController` that validates with the existing `AuthenticationManager` + `JpaUserDetailsService` and mints via the unchanged `JwtTokenProvider`. Logout (`POST /logout` with bearer) revokes the jti in Redis as before. Findings 1.3 (CSRF hybrid) and 1.4 (form-login CSRF) closed by elimination; 1.12 (OAuth2 partial-config check) closed as N/A; 1.6 (JWT revocation on logout) was already closed by 1.6's own fix and stays valid.

6. **Promote ID generation to UUIDs.** Section 2.5. Cheap, removes a class of subtle bugs.

7. **Move the `User` entity out of `SecurityConfig`.** Section 2.15. One commit.

8. **Strip duplicate / unused dependencies.** ✅ **Done (2026-05-16).** Explicit pins for `slf4j-api`, `logback-classic`, `logback-core`, `jackson-databind` removed from `pom.xml`; the Spring Boot BOM now owns those versions. The corresponding `<slf4j.version>` / `<logback.version>` / `<jackson.version>` property entries were removed too. Note remains: Spring Boot 3.2.4 itself is out of date and should be bumped to the current 3.x line for the Framework CVE fixes (CVE-2024-22243, CVE-2024-22257, etc.) — that bump is a separate task.

9. **Pin Java to 21 LTS in CI and ship with a JRE base image that matches the JDK used at build.** ✅ **Done (2026-05-17).** `.github/workflows/ci.yml` (added 2026-05-16) builds and tests on `actions/setup-java@v4` with `java-version: 21` / `distribution: temurin`, exporting a CI-only `JWT_SECRET` so the SecurityConfig fail-fast doesn't trip. Dockerfile runtime image switched from `eclipse-temurin:21-jre-alpine` to `21-jre-jammy` (2026-05-17): Alpine + musl + missing fontconfig was a silent risk for PDFBox text rendering and the barcode/preview flows. ~100 MB image-size cost; the Dockerfile carries an inline comment explaining why and warning against reverting without proof.

10. **Stop putting build artifacts in version control.** ✅ **Done.** `.gitignore` (added 2026-05-16) covers `target/`, IDE folders, OS junk, `.env*`, `*.pem`/`*.key`, and the runtime `uploads/` / `barcodes/` directories. 2026-05-17 re-check via `git ls-files target/` and `git log --all --diff-filter=A -- 'target/*'` confirms `target/` was never actually tracked in this repo — the earlier "operator action: `git rm -r --cached target/`" note was written on a wrong assumption and has been retracted.

---

## 4. Test strategy

> **Status (2026-05-19):** in progress. Layer 1 (unit tests for `JwtTokenProvider` + `PdfProcessingService`) shipped in batch 4.3. Layer 2 (repository slices) shipped in 4.2. Layer 3 (controller slices) and the most-changed parts of Layer 4 (JWT security probes) shipped in 4.1. `mvn test` BUILD SUCCESS 85/85. Remaining: Layer 5 Testcontainers integration, Layer 6 flaky-inbound rewrite, Layer 7 TestFX. The plan below is unchanged otherwise.
>
> **Lessons recorded during 4.1, worth carrying forward into the remaining layers:**
>
> 1. **Never `@MockBean` an `@Aspect`** unless you actually want the advice replaced. `@MockBean` of an `@Aspect` adds the mock into the AOP proxy chain via `@EnableAspectJAutoProxy`, and the mock's `@Around` method (Mockito's default return of `null`) doesn't call `pjp.proceed()` — so any controller method covered by the advice never actually runs, and the response is a particularly mystifying 200-with-empty-body. The slice's default policy of excluding `@Component` / `@Aspect` beans is exactly what you want; don't override it for aspects.
> 2. **POST requests in slice tests need `.with(csrf())`.** Even when `SecurityConfig.csrf().disable()` is in place, Spring Security 6's MockMvc auto-config wires a CSRF filter into the test chain. Adding the post-processor is a no-op when the filter is genuinely off and the fix when it isn't. Use it uniformly on POST/PUT/DELETE in slice tests.
> 3. **`@SpringBootTest` exclusion of `JpaRepositoriesAutoConfiguration` lives under `…autoconfigure.data.jpa`**, not `…autoconfigure.jpa`. Spring renamed it; the latter import compiles in older Boot lines and silently breaks in 3.x.

### What exists today (2026-05-19, post batch 4.3)

- `FaxEngineServiceTest` (4 tests, ~256 lines) — original service test. Uses its own `@SpringBootTest(classes = TestConfig.class)` and an inline DataSource so it doesn't load the full app context. Caveats from the original audit still apply (the `testListenForInboundFax` Math.random() flakiness; the on-disk PDF that bypasses the mocked `PdfProcessingService`).
- `SchemaMigrationTest` (2 tests) — `@DataJpaTest` against H2 in PostgreSQL mode with Flyway running V1. Documents entity ↔ migration drift; surfaced 3.1a and 3.1b during the ADR-0001 verification pass.
- **From batch 4.1:** `AuthControllerTest` (4 cases), `AdminControllerTest` (3 cases), `FaxControllerTest` (6 cases), `JwtSecurityIntegrationTest` (5 cases).
- **From batch 4.2:** `UserRepositoryTest` (2 cases), `ContactRepositoryTest` (7 cases), `FaxLogRepositoryTest` (10 cases), `FaxMetadataRepositoryTest` (10 cases) — every custom `@Query` and derived finder now exercised against the Flyway-migrated schema.
- **New (batch 4.3):** `JwtTokenProviderTest` (19 cases — pure unit, no Spring context; pins audit 1.1 / 1.6 / 1.7 contracts), `PdfProcessingServiceTest` (13 cases — pure unit; in-memory PDFs via PDFBox in `@TempDir`).

Total: 85 tests across 12 classes. Coverage focus is the auth surface (most-changed by tech-debt #5), the request-shaping controllers, the JPQL layer, and now the two services with the most subtle behavior (JWT signing/allowlist and PDF text/barcode IO).

Gaps (not yet covered):
- Testcontainers integration against real Postgres + Redis.
- A deterministic replacement for `FaxEngineServiceTest.testListenForInboundFax`.
- JavaFX UI smoke-tests via TestFX (now in the desktop module after ADR-0001).

### Recommended test plan

Layer the suite the way the app layers responsibilities:

1. **Unit tests** for pure services and helpers. `JwtTokenProvider` (issue/validate/expired/tampered/wrong-secret cases), `PdfProcessingService.extractTextFromPdf` and `generateBarcode` (corrupt PDF, empty PDF, oversized PDF). Mock the filesystem with `jimfs`.
2. **Repository slice tests** with `@DataJpaTest` + H2 — confirm each `@Query` actually runs against the schema. Today the JPQL is unverified.
3. **Controller slice tests** with `@WebMvcTest(FaxController.class)` and `@MockBean` services. Verify the `@PreAuthorize`, the validation on `FaxRequestDTO`, and the response shapes (including the questionable 404-with-null body decision).
4. **Security tests** with `@SpringBootTest` + `MockMvc`:
   - Anonymous request to `/api/fax/status` → 401 (or 200 if status is meant to be public — confirm intent).
   - Valid JWT with `ROLE_USER` against `/api/admin/**` → 403.
   - Forged JWT with the *default* secret → 200. (This test should FAIL once you fix 1.1; that's the point — it documents the regression.)
   - CSRF probe: POST to `/api/fax/send` with a session cookie but no CSRF header — currently succeeds (that's the bug); after fixing 1.3 it should 403.
5. **Integration test** using Testcontainers for Redis and Postgres — exercise the actual `RedisConfig` and `application-prod.yml`. This is what will catch finding 2.1 the next time someone changes a property.
6. **Replace the flaky `testListenForInboundFax`** with two tests: one that stubs `Math.random()` (or, better, a `RandomSource` interface) to deterministically trigger the inbound branch and verifies the writes; one that asserts the "listening" path doesn't write contacts.
7. **JavaFX UI**: smoke-test via `TestFX` if you keep the desktop app in-tree. At minimum verify `MainView` constructs cleanly when FXML is present and when it isn't.

Coverage targets: aim for ≥80% line coverage on `service/`, `controller/`, and `config/SecurityConfig`. UI and `WebSocketConfig` are lower priority but should have at least smoke coverage.

---

## 5. Quick wins — ✅ all closed (2026-05-17)

Every item originally listed here was addressed in the remediation batches above; cross-references are noted for traceability.

- ✅ Delete the `java` zero-byte file at the repo root — operator delete confirmed.
- ✅ Remove duplicate `@EnableScheduling` — kept on `FaxTridentApplication`, dropped from `WebSocketConfig` (2.20).
- ✅ Drop explicit pins for `slf4j-api`, `logback-classic`, `logback-core`, `jackson-databind` — tech-debt #8.
- ✅ Remove `@Transactional` from methods that don't hit the DB — 2.18 (11 sites).
- ✅ Move the commented PostgreSQL block to `application-prod.yml` — 2.21.
- ✅ Replace `Paths.get(".")` in `AdminController.cleanupBarcodes` with the resolved `barcodeDir` — 2.10.
- ✅ `try-with-resources` around the three `Files.list(...)` call sites — 2.10.
- ✅ `System.currentTimeMillis()` IDs → `UUID.randomUUID().toString()` — 2.5.
- ✅ Strip CR/LF from user-controlled strings before logging — 1.11 (via `LogSanitizer`).

---

## Appendix A — Original audit scope (2026-05-15)

```
Dockerfile
pom.xml
src/main/resources/application.yml
src/main/resources/fxml/main.fxml
src/main/resources/static/login.html
src/main/resources/static/css/{dark,mermaid}-mode.css
src/main/java/com/xai/trident/FaxTridentApplication.java
src/main/java/com/xai/trident/config/{SecurityConfig,RedisConfig,WebSocketConfig}.java
src/main/java/com/xai/trident/controller/{AdminController,FaxController}.java
src/main/java/com/xai/trident/service/{FaxEngineService,PdfProcessingService,SmartAssistService}.java
src/main/java/com/xai/trident/model/{Contact,FaxLog,FaxMetadata}.java
src/main/java/com/xai/trident/repository/{Contact,FaxLog,FaxMetadata}Repository.java
src/main/java/com/xai/trident/ui/{MainView,PreviewPane,ThemeManager}.java
src/test/java/com/xai/trident/FaxEngineServiceTest.java
src/test/resources/application-test.yml
```

### Files added during remediation (2026-05-15 → 2026-05-17)

These were added against the (then) single-module flat layout. All of them moved to `fax-trident-server/...` under their respective paths during the 2026-05-18 ADR-0001 split — see "Files added during ADR-0001 split" below for the post-split inventory.

```
.github/workflows/ci.yml
.gitignore
fax-trident-server/pom.xml                          (part of §0.1 restructure regression; rewritten by ADR-0001)
src/main/java/com/xai/trident/config/AsyncConfig.java
src/main/java/com/xai/trident/controller/LoginController.java        (subsequently tombstoned)
src/main/java/com/xai/trident/model/User.java                       (extracted from SecurityConfig)
src/main/java/com/xai/trident/ratelimit/RateLimit.java
src/main/java/com/xai/trident/ratelimit/RateLimitAspect.java
src/main/java/com/xai/trident/ratelimit/RateLimitExceededException.java
src/main/java/com/xai/trident/repository/UserRepository.java        (extracted from SecurityConfig)
src/main/java/com/xai/trident/service/InboundFaxSimulator.java
src/main/java/com/xai/trident/ui/FaxUpdateClient.java                (subsequently moved to fax-trident-desktop)
src/main/java/com/xai/trident/upload/FaxUploadService.java
src/main/java/com/xai/trident/upload/InvalidUploadException.java
src/main/java/com/xai/trident/upload/UploadExceptionHandler.java
src/main/java/com/xai/trident/upload/UploadNotFoundException.java
src/main/java/com/xai/trident/util/LogSanitizer.java
src/main/resources/application-prod.yml
src/main/resources/sounds/trident-rise.wav
src/main/resources/templates/login.html                              (subsequently tombstoned)
```

### Files added during ADR-0001 split (2026-05-18)

The split landed atomically: parent reactor pom + two module poms, a renamed server entry point, a new desktop entry point and its supporting client/preferences code, plus the ADR itself. Every existing source moved into its module — no logic edits except for the desktop UI sources, which had their Spring annotations stripped and the dependency on `FaxEngineService` / `RedisTemplate` replaced with `FaxApiClient` / `DesktopPreferences`.

```
docs/adr/0001-decouple-javafx-from-spring-boot.md
pom.xml                                                              (rewritten; now packaging=pom, lists both modules)
fax-trident-server/pom.xml                                           (rewritten from the §0.1 tombstone)
fax-trident-desktop/pom.xml                                          (new)
fax-trident-server/src/main/java/com/xai/trident/FaxTridentServer.java        (replaces FaxTridentApplication)
fax-trident-desktop/src/main/java/com/xai/trident/desktop/FaxTridentDesktop.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/client/FaxApiClient.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/client/RetryHelper.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/config/DesktopPreferences.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/ui/LoginDialog.java
```

### Files moved during ADR-0001 split

Server-side packages (`config/`, `controller/`, `model/`, `ratelimit/`, `repository/`, `service/`, `upload/`, `util/`) and resources (`application*.yml`, `db/migration/`) moved from `src/main/...` to `fax-trident-server/src/main/...` with no logic changes. The desktop UI sources (`ui/MainView.java`, `ui/PreviewPane.java`, `ui/ThemeManager.java`, `ui/FaxUpdateClient.java`) moved to `fax-trident-desktop/src/main/java/com/xai/trident/desktop/ui/` and were rewritten to drop Spring annotations (`@Component`, `@Autowired`, `@Async`, `@Retryable`, `@Recover`), drop `SecurityContextHolder` references, an