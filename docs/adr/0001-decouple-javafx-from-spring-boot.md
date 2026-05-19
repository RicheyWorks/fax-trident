# ADR-0001: Decouple JavaFX desktop UI from the Spring Boot server

**Status:** Accepted (2026-05-18)
**Date:** 2026-05-18
**Deciders:** Richmond (operator/owner). Author: this ADR.

> **Decisions locked (2026-05-18):** one repo / two modules; login dialog +
> in-memory JWT; local-file preferences (no Redis on desktop); hand-rolled
> retry on JavaFX `Task` (no Spring on desktop).

> Tracks `AUDIT.md` tech-debt #1. Supersedes the half-finished §0.1 multi-module
> restructure that was reverted on 2026-05-17.

## Context

`fax-trident` today is a single Maven module that produces a single fat-jar.
That jar boots one JVM with two responsibilities glued together:

1. A Spring Boot REST + WebSocket server (`/api/**`, `/fax-updates`).
2. A JavaFX desktop UI that runs in the same JVM and `@Autowired`s server-side
   beans directly (`FaxEngineService`, `RedisTemplate` via `ThemeManager`,
   `FaxLogRepository`).

The two halves are entangled at the bean level. `FaxTridentApplication`
`extends javafx.application.Application` *and* is annotated
`@SpringBootApplication`. JavaFX's `Application.launch()` reflectively
instantiates the class itself, so Spring never sees that instance — the
JavaFX-managed object is autowired manually via
`springContext.getAutowireCapableBeanFactory().autowireBean(this)` inside
`init()`. (Audit finding 2.2 fix.)

This causes concrete pain:

- **Headless deploys are awkward.** The server can't run without the JavaFX
  half initializing. `-Djava.awt.headless=true` papers over it but the UI
  beans still load.
- **No independent release cadence.** A server-only hotfix forces a desktop
  rebuild and vice versa.
- **The autowire-into-Application dance is a known footgun.** It works, but
  every new contributor has to be told why it's there.
- **Two WebSocket clients existed at one point** (audit 2.14). The fix
  collapsed them, but the underlying problem — desktop classes assumed
  same-JVM access to server beans — is unresolved.
- **A previous attempt (§0.1) was reverted** because it landed the
  parent-pom switch before the modules existed. The lesson recorded in
  AUDIT.md §0.1: *"treat it as a fresh, atomic change: create both module
  skeletons, move sources, rename the main class, update the Dockerfile,
  and verify a clean reactor build — before landing the parent-pom switch."*

The forces at play:

- Keep the existing REST API contract intact — external clients and the
  README documentation are stable.
- Don't introduce new security surface. The server already runs stateless
  JWT-only (audit 3.5); the desktop must authenticate the same way as any
  other client.
- Single repo is preferred (one source of truth, one PR per change).
- No new external dependencies on the desktop side unless required.

## Decision

Split the project into a Maven multi-module reactor with two modules in the
same git repository:

```
fax-trident/                       (parent pom, packaging=pom)
├── fax-trident-server/            Spring Boot REST + WebSocket server
└── fax-trident-desktop/           JavaFX client, talks to server over HTTP+WS
```

The desktop module is **Spring-free**. It uses plain JDK `HttpClient` and
the existing `Java-WebSocket` library directly. The contract between desktop
and server is the existing REST API (already documented in `README.md`) plus
the existing `/fax-updates` WebSocket — no new endpoints, no new wire format.

Land this in one atomic change: create both module skeletons, move sources,
rename main classes, update the Dockerfile, switch the parent pom, and
verify `mvn validate && mvn -DskipTests compile && mvn test` all green
before committing. Per the §0.1 lesson, no partial states.

### Wire-format ownership

| Concern | Owner | Notes |
|---|---|---|
| Auth | server | `POST /api/auth/login` (existing), `POST /logout` (existing). |
| File upload | server | `POST /api/fax/uploads` → `uploadId` (existing 1.5 flow). |
| Send fax | server | `POST /api/fax/send` with `{faxNumber, uploadId}` (existing). |
| Status broadcast | server | `/fax-updates` WebSocket (existing). |
| PDF rendering | desktop | PDFBox runs locally for preview (no network call). |
| Theme preference | desktop | Local on-disk file, `${user.home}/.fax-trident/preferences.properties`. **Drops the desktop's Redis dependency.** |

### Module responsibilities

**`fax-trident-server`** — current behavior, minus the JavaFX coupling:

- All of `controller/`, `service/`, `repository/`, `model/`, `config/`,
  `ratelimit/`, `upload/`, `util/`.
- New main class `com.xai.trident.FaxTridentServer extends Object` (was
  `FaxTridentApplication extends Application`). Drops all JavaFX
  annotations and the `init()` / `start(Stage)` / `stop()` methods.
- `WebSocketConfig` and `WebSocketConfig.FaxUpdateHandler` stay server-side
  (they handle inbound WS connections from clients, including the desktop).
- `application.yml`, `application-prod.yml`, Flyway migrations,
  CSS/sounds/static all stay on the server side **except** the CSS files
  that the desktop applies as JavaFX stylesheets — see below.
- Existing single test (`FaxEngineServiceTest`) moves under
  `fax-trident-server/src/test/`.

**`fax-trident-desktop`** — formerly `ui/` plus a new HTTP/WS client layer:

- `MainView`, `PreviewPane`, `ThemeManager`, `FaxUpdateClient` move here.
- New main class `com.xai.trident.desktop.FaxTridentDesktop extends Application`.
- New `client/FaxApiClient` — wraps `java.net.http.HttpClient`, implements
  `login(user, pass)`, `uploadPdf(File) → uploadId`, `sendFax(faxNumber, uploadId)`,
  `searchContacts(...)`, etc. Holds the JWT in a `volatile String`
  in-memory.
- New `ui/LoginDialog` — JavaFX dialog shown before `MainView`. Prompts
  username/password, calls `FaxApiClient.login`, stores the returned token.
- `FaxUpdateClient` stays in spirit but loses `@Component`,
  `@EventListener(ApplicationReadyEvent.class)`, and `@PreDestroy`. It's
  constructed by `FaxTridentDesktop.start(Stage)` after login succeeds and
  closed in `Application.stop()`.
- `ThemeManager` loses `@Autowired RedisTemplate`. Theme preference reads
  and writes a local Properties file. The current
  `redisTemplate.opsForValue().set("theme:" + username, ...)` line is
  replaced with `Files.writeString(prefsPath, ...)`.
- `PreviewPane` loses `@Async`, `@Retryable`, `@Recover`, `@Component`.
  The retry semantics are reimplemented as an explicit loop on a JavaFX
  `Task` (or a small dedicated executor), with at-most-3 attempts and a
  1s backoff — the exact behavior the Spring annotations encoded.
- Resources the desktop owns: the two CSS files
  (`mermaid-mode.css`, `dark-mode.css`), the three placeholder `.wav`
  files. They move from `fax-trident-server/src/main/resources/static/` /
  `sounds/` to `fax-trident-desktop/src/main/resources/` (no longer
  HTTP-served).

### File path changes (for review)

```
src/main/java/com/xai/trident/FaxTridentApplication.java
  → fax-trident-server/src/main/java/com/xai/trident/FaxTridentServer.java
    (renamed; JavaFX coupling stripped)
  + fax-trident-desktop/src/main/java/com/xai/trident/desktop/FaxTridentDesktop.java
    (new — pure JavaFX entry point)

src/main/java/com/xai/trident/ui/*                  → fax-trident-desktop/...
src/main/java/com/xai/trident/{controller,service,
  repository,model,config,ratelimit,upload,util}/*  → fax-trident-server/...
src/main/resources/application*.yml                  → fax-trident-server/...
src/main/resources/db/migration/*                    → fax-trident-server/...
src/main/resources/static/css/*-mode.css             → fax-trident-desktop/...
src/main/resources/sounds/*                          → fax-trident-desktop/...
```

New files:

```
fax-trident-desktop/src/main/java/com/xai/trident/desktop/client/FaxApiClient.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/client/RetryHelper.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/ui/LoginDialog.java
fax-trident-desktop/src/main/java/com/xai/trident/desktop/config/DesktopPreferences.java
```

## Options Considered

### Option A: Two-module reactor in one repo (RECOMMENDED)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium — atomic source move, but only mechanical changes. |
| Cost | Low — no new dependencies; HttpClient is JDK built-in. |
| Scalability | High — server can be deployed headless; desktop can ship independently as a thick client. |
| Team familiarity | High — Maven reactor is standard. |
| Build time | Slight increase (~10s) for two modules vs. one. |

**Pros:**
- Eliminates the autowire-into-Application dance entirely.
- Headless server: no `java.awt.headless=true`, no UI bean initialization.
- Independent releases: server hotfix ships without desktop rebuild.
- Single repo keeps the contract close to both consumers — no API
  versioning headache until the desktop actually needs to lag the server.
- Restores the original §0.1 intent without the half-finished mistakes.
- Easy to extract a shared `fax-trident-common` module later if DTO drift
  becomes a problem; not needed for the MVP split.

**Cons:**
- The desktop must now boot through a login dialog instead of running with
  ambient in-process auth. Adds one user-visible step.
- Theme preference loses its centralized Redis store (becomes per-machine).
  Acceptable — themes were never multi-device anyway.
- Spring AOP magic (`@Async`, `@Retryable`) replaced with hand-rolled
  retry/executor on the desktop. ~30 lines of additional code; explicit
  is better than magic for a 20% use case.
- The desktop crash story: if the server is unreachable, the desktop
  surfaces a connection error in the login dialog and exits. Today, it
  would have started up regardless because the server was in-process.

### Option B: Single module, but break JavaFX out of the `@SpringBootApplication` class

Keep one Maven module. Drop `extends Application` from
`FaxTridentApplication`. Move JavaFX bootstrap to a separate class invoked
from a CLI flag — server-only with `--server`, desktop-and-server with
`--with-ui`. Same JVM either way; UI still uses `@Autowired` server beans.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — class restructure only. |
| Cost | Very low — no new code. |
| Scalability | Same problem — server still drags JavaFX. |
| Team familiarity | High. |

**Pros:**
- Minimal change. One day of work.
- Doesn't change the deployment surface.

**Cons:**
- **Doesn't actually solve the problem.** Server still depends on JavaFX
  on the classpath at startup. Headless deploys still touch JavaFX
  initialization. The autowire-into-Application footgun stays.
- Doesn't unblock independent release cadence.
- The audit specifically flagged the "JavaFX UI shares JVM with Spring
  Boot service" coupling — this option doesn't break that coupling.

### Option C: Two repos (polyrepo)

Move `fax-trident-desktop/` into its own git repository with its own
release line. Share DTOs via a published jar.

| Dimension | Assessment |
|-----------|------------|
| Complexity | High — repo bootstrap, CI duplication, dependency version coordination. |
| Cost | Medium — new infra. |
| Scalability | High — full independence. |
| Team familiarity | Medium — adds operational overhead. |

**Pros:**
- Cleanest separation. Desktop can lag the server by N versions.
- Each repo has its own CI, its own release tags, its own issue tracker.

**Cons:**
- Premature for a ~3.4k-LOC codebase with one developer.
- API drift becomes a real risk — currently the desktop and server share
  literal types (no DTO module today; everything goes via JSON Map). A
  polyrepo forces a published DTO jar, and now we're on Nexus or Maven
  Central or GitHub Packages just to coordinate two modules.
- AUDIT.md §0.1 explicitly recommended atomic; polyrepo is the opposite
  of atomic.

## Trade-off Analysis

The decision really comes down to **Option A vs. Option B**.

Option B is half the change for almost none of the benefit. The audit's
core complaint about tech-debt #1 — *"they share a single JVM"* — is
unaddressed by B. Server deploys still load JavaFX. Desktop releases still
ship the server. The autowire dance stays.

Option A is the change the audit actually asked for. It costs:
- One login dialog (3 dozen lines of JavaFX).
- One HTTP client (~150 lines around JDK `HttpClient`).
- One retry helper (~30 lines, replacing `@Retryable` semantics).
- One preferences file reader (~20 lines, replacing Redis-backed themes).

Total new code: ~250 lines. Total moved code: zero behavior change in
either module. The atomic-change constraint is achievable in one commit.

Option C is a future option — if/when the desktop needs an independent
release cycle from the server, lifting the desktop module into its own
repo is a one-day operation from Option A's structure. Doing it now would
be premature optimization.

**Recommendation: Option A.**

## Consequences

### Becomes easier

- Running the server headless in Docker / Kubernetes — no JavaFX classes
  on the classpath, no `headless=true` workaround.
- Server hotfix releases — desktop module isn't even compiled.
- Onboarding — the awkward `autowireBean(this)` block goes away (the
  `extends Application` class is gone).
- Reasoning about security boundaries — desktop is a client like any
  other; it goes through the bearer-token front door.
- Future: lifting the desktop into its own repo if needed (Option C).
- Future: replacing the desktop entirely with a web frontend — the wire
  format is HTTP+JSON+WS, not in-process bean calls.

### Becomes harder

- **The desktop needs a server to talk to at startup.** Today it boots
  with the server in-process; tomorrow it shows a connection-error dialog
  and exits if the server URL is unreachable.
- **JWT lifetime is a user-visible thing.** When the token expires
  (default 1h), the desktop receives 401s and must re-login. Today
  this never happens because `SecurityContextHolder` is populated
  in-process.
- **Theme preference is now per-machine.** A user who logs in from two
  machines has two theme states. Acceptable for a fax UI but worth
  calling out.
- **Spring AOP magic on the desktop is gone.** `@Retryable` /
  `@Async` work via Spring proxies; without Spring DI, the retry has to
  be explicit. The replacement is mechanical but it's now in code rather
  than in annotations.

### Will need to revisit

- **WebSocket auth.** Today `/fax-updates` accepts unauthenticated WS
  upgrades from the configured origin. Once the desktop is talking to a
  remote server over an open network, the WS endpoint should require a
  bearer (Sec-WebSocket-Protocol header or a `?token=...` query param,
  pick one). Out of scope for this ADR; tracked as a follow-up.
- **Desktop config story.** Currently the desktop reads
  `app.websocket.client-url` from the in-process Spring `@Value`. After
  the split it needs a config source — a properties file in
  `${user.home}/.fax-trident/`, env vars, or JVM `-D` flags. ADR
  proposes properties file with env-var override.
- **Token persistence.** In-memory only is the MVP. If users complain
  about re-login on every launch, add OS keychain integration
  (Windows DPAPI / macOS Keychain / Linux Secret Service) — these
  require platform-specific libraries (e.g., `keyring-rust` via JNI or
  `java-keytar`). Out of scope here.
- **Installer story.** Today the deliverable is a fat jar; users run
  `java -jar`. JavaFX-bundled native installers via `jpackage` would
  be nicer but are a separate work item.

## Verification plan

Before merging the atomic change:

1. `mvn validate` on the parent pom — both modules parse, the reactor
   resolves dependencies.
2. `mvn -DskipTests compile` — every source file compiles in its new
   module.
3. `mvn test` — `FaxEngineServiceTest` (moved into the server module)
   still passes 4/4.
4. `mvn -pl fax-trident-server package` — fat jar produced at the
   expected path (the path the Dockerfile expects).
5. `mvn -pl fax-trident-desktop package` — desktop jar produced.
6. `docker build .` (manually by operator on a JDK 21 host) — image
   builds, JAR copies from `fax-trident-server/target/`.
7. Manual smoke test (operator, deferred): run server, run desktop, log
   in, upload PDF, send, observe status update via WebSocket.

The build verification (steps 1–5) is the gate. Steps 6–7 are operator
follow-ups documented in the AUDIT.md tech-debt #1 close-out note.

## Action Items

1. [ ] Create `fax-trident-server/pom.xml` with all current dependencies
       and plugins.
2. [ ] Create `fax-trident-desktop/pom.xml` with JavaFX, Jackson, and
       `Java-WebSocket` only — no Spring.
3. [ ] Move all server-side sources into
       `fax-trident-server/src/main/java/...`.
4. [ ] Move UI sources into
       `fax-trident-desktop/src/main/java/com/xai/trident/desktop/ui/`
       (note the new `.desktop` package root to avoid ambiguity with the
       server's `com.xai.trident.ui` package, which is now empty).
5. [ ] Create `FaxTridentServer` (server main) and `FaxTridentDesktop`
       (desktop main). Drop the inheritance from `Application` on the
       server side.
6. [ ] Implement `FaxApiClient`, `RetryHelper`, `LoginDialog`,
       `DesktopPreferences`.
7. [ ] Rewrite `MainView.sendFax` to upload + send via `FaxApiClient`
       (replacing the direct `FaxEngineService` call).
8. [ ] Rewrite `ThemeManager` to use `DesktopPreferences` instead of
       `RedisTemplate`.
9. [ ] Rewrite `PreviewPane.loadDocumentAsync` to use `RetryHelper`
       instead of `@Async` / `@Retryable` / `@Recover`.
10. [ ] Rewrite `FaxUpdateClient` to be a plain class, lifecycle wired
        from `FaxTridentDesktop.start` / `stop`.
11. [ ] Convert the parent `pom.xml` to `packaging=pom` with
        `<modules>fax-trident-server, fax-trident-desktop</modules>`.
        **Land this last** — per the §0.1 lesson, switching the pom
        before the modules exist is what broke last time.
12. [ ] Update `Dockerfile`: `COPY pom.xml fax-trident-server/pom.xml ...`,
        adjust the `mvn package` invocation, point the jar copy at
        `fax-trident-server/target/fax-trident-server-*.jar`.
13. [ ] Update `.github/workflows/ci.yml` if needed (Maven reactor
        builds both modules automatically; no change expected).
14. [ ] Run the full verification plan (1–5) and capture the build
        output.
15. [ ] Update `README.md` architecture diagram and project-layout
        section to reflect the two-module structure.
16. [ ] Update `AUDIT.md`: close tech-debt #1 with verification notes;
        prune the "Known follow-ups" item; add a new "Files added during
        remediation" entry; reference this ADR.
17. [ ] Add to `AUDIT.md` "Recommended" follow-ups: WebSocket bearer
        auth; OS keychain for JWT persistence.
