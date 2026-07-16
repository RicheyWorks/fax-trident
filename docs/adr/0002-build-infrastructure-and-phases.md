# ADR-0002: Build infrastructure and delivery phases

**Status:** Proposed
**Date:** 2026-07-16
**Deciders:** Richmond (operator/owner). Author: this ADR.

> Builds on ADR-0001 (two-module reactor). Covers the four delivery gaps the
> split left open: CI shape, Docker image publishing, desktop packaging for
> Windows/macOS/Linux, and release versioning. Server deploy target is
> **intentionally undecided** — every choice below stays portable across
> Docker-on-VM, Kubernetes, or bare JVM.

## Context

What exists today:

- **CI** (`.github/workflows/ci.yml`): one job, `mvn verify` over the whole
  reactor on JDK 21 Temurin with Maven caching. Two gaps have crept in since
  ADR-0001:
  - The failure-artifact upload points at `target/surefire-reports/` — the
    *root* target, which no longer receives reports in a multi-module build.
    Reports now land in `fax-trident-server/target/surefire-reports/` (and the
    desktop module's, once it has tests). Failures currently upload nothing.
  - The Dockerfile is never exercised by CI. A change that breaks the image
    build (layer paths, `-pl` flags) is only discovered at deploy time.
- **Docker**: a well-tuned two-stage Dockerfile (server module only,
  `-pl fax-trident-server -am`, jammy JRE for PDFBox font support) — but no
  workflow builds or publishes it. Images are built by hand on whatever
  machine deploys.
- **Desktop**: builds a plain jar. There is no distributable artifact — a user
  needs a local JDK 21 + JavaFX and a Maven checkout to run it. ADR-0001 made
  the desktop an independent client shipping to Windows, macOS, and Linux;
  nothing ships it.
- **Versioning**: everything is `1.0.0-SNAPSHOT`, forever. No tags, no
  releases, no way to say "the desktop build from last Tuesday talks to server
  image X."

Forces:

- Solo-operator project on GitHub — infrastructure must be near-zero
  maintenance and free-tier friendly.
- Deploy target undecided — publish artifacts in the most portable formats
  (OCI image, native installers) and defer environment-specific automation.
- The desktop and server share one repo and one version (ADR-0001 chose one
  repo / one PR per change); the release scheme should not fight that.
- CI minutes on macOS runners are 10x Linux cost — desktop packaging should
  run on release events, not every push.

## Decision

Adopt GitHub-native build infrastructure, delivered in four phases:
harden CI, publish the server image to GHCR, package the desktop with
`jpackage` on a three-OS matrix, and drive releases from git tags with a
single reactor version. Details and alternatives per area below.

## Options considered

### Area 1 — CI pipeline shape

#### Option A: Keep one whole-reactor job, fix the gaps (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Cost | ~5 min Linux per push (current) |
| Scalability | Fine until build time or module count grows |
| Team familiarity | High — it's the current setup |

**Pros:** One green check to reason about; reactor build catches cross-module
breakage (the exact §0.1 failure mode ADR-0001 warned about); minimal YAML.
**Cons:** Desktop-only CSS change still compiles the server and runs its tests.

#### Option B: Per-module jobs with path filters

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | Lower per-push on average |
| Scalability | Better for many modules |
| Team familiarity | Medium |

**Pros:** Faster feedback on module-local changes.
**Cons:** Path filters silently skip the reactor build that validates the
parent pom and inter-module wiring — reintroduces the half-built-state risk
this repo has already been burned by (AUDIT §0.1). Two modules don't justify it.

**Decision:** Option A. Fix the surefire artifact path to
`**/target/surefire-reports/`, add a `docker build` smoke step (build, don't
push) so Dockerfile breakage fails PRs, and add a `concurrency` group so
superseded pushes cancel. Revisit Option B only if `mvn verify` exceeds ~10
minutes.

### Area 2 — Server image publishing

#### Option A: GHCR via GitHub Actions (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — `GITHUB_TOKEN` auth, no new secrets |
| Cost | Free for public repos; generous private quota |
| Scalability | OCI-standard; any future target (VM, k8s) pulls it |
| Team familiarity | Same platform as everything else |

**Pros:** Zero new accounts/secrets; image lives next to the code;
tag-triggered publishing composes with Area 4.
**Cons:** If the deploy target lands on AWS/GCP, a cloud-local registry may
later be preferred for pull latency/egress — a one-line push-target change.

#### Option B: Docker Hub

**Pros:** Ubiquitous default; anonymous pulls.
**Cons:** Separate account + access token to manage; free-tier rate limits on
pulls; no advantage over GHCR for a GitHub-hosted repo.

#### Option C: Defer until deploy target is chosen

**Pros:** No speculative work.
**Cons:** Keeps hand-built images as the deploy path — unreproducible and
untraceable. The undecided target is the argument *for* a neutral registry,
not against one.

**Decision:** Option A. Tagging scheme: every `main` push gets `sha-<short>`;
release tags get the semver (`1.2.0`) plus `latest`. The workflow reuses the
existing Dockerfile untouched.

### Area 3 — Desktop packaging (Windows, macOS, Linux)

#### Option A: `jpackage` native installers on a 3-OS CI matrix (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium — per-OS quirks, but jpackage ships with JDK 21 |
| Cost | macOS/Windows runners; mitigated by running on release only |
| Scalability | The standard endgame for JavaFX distribution |
| Team familiarity | Low today; well-trodden path in JavaFX community |

**Pros:** Users get `.msi` / `.dmg` / `.deb` with a bundled runtime — no JDK,
no JavaFX, no Maven required; `jlink`-trimmed runtime keeps installers ~50-70
MB; jpackage must run on the target OS, and the CI matrix provides exactly
that.
**Cons:** Unsigned installers trigger SmartScreen / Gatekeeper warnings —
signing and notarization cost money and Apple/MS accounts, so they are
explicitly **deferred** (acceptable for a self-distributed tool with a known
user base); JavaFX jmods must be fetched per-platform for the jlink image.

#### Option B: Shaded ("fat") jar per platform

**Pros:** Simple; single Maven plugin; one CI job.
**Cons:** Requires users to install a matching JDK 21 — the main source of
"works on my machine" support burden; JavaFX native libs in a shaded jar are
fragile across platforms; no OS integration (menu entries, file associations).

#### Option C: jlink runtime zip (no installer)

**Pros:** Self-contained like A, without installer quirks.
**Cons:** Still a "unzip and find the launcher script" experience; no upgrade
path; saves little over A since jpackage wraps the same jlink image.

**Decision:** Option A. Runs only on release tags (Area 4), not per-push, to
keep macOS/Windows runner minutes negligible. Signing/notarization recorded as
deferred follow-up, same register as the AUDIT "Follow-ups" list.

### Area 4 — Releases and versioning

#### Option A: Single reactor version, tag-driven releases (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Cost | None |
| Scalability | Fine while desktop+server release together |
| Team familiarity | Matches the one-repo/one-version reality |

**Pros:** `v1.2.0` names a *compatible pair* of server image and desktop
installers — exactly the traceability gap today; trivially automatable
(`mvn versions:set` in the release workflow, no pom churn in git);
ADR-0001 kept one wire contract, so lockstep versions are honest.
**Cons:** A server-only hotfix bumps the desktop version too — harmless
(clients just have a newer number) but slightly noisy.

#### Option B: Independent module versions

**Pros:** Server hotfixes don't touch desktop versioning.
**Cons:** Requires a compatibility matrix ("desktop 1.3 needs server ≥1.2") —
real cost, no benefit at this scale; fights the shared parent/reactor.

**Decision:** Option A. Poms stay `-SNAPSHOT` on `main`. Pushing tag `v1.2.0`
triggers a release workflow that: sets the reactor version to `1.2.0`
in-workflow, runs `mvn verify`, pushes the server image (Area 2 tags), runs
the jpackage matrix (Area 3), and creates a GitHub Release with the three
installers attached and the image digest in the notes. SemVer semantics keyed
to the REST + WS contract: breaking API change = major, additive = minor,
fix = patch.

## Trade-off analysis

The through-line is **GitHub-native and target-agnostic**. Every area picks
the option with zero new accounts, zero new secrets beyond `GITHUB_TOKEN`,
and artifacts (OCI image, native installers, git tags) that remain valid no
matter where the server eventually deploys. The cost of that neutrality is
deferring deploy automation (no CD, no environment promotion) — correct while
the target is undecided, and the release workflow becomes the natural hook
for CD later.

The second trade is **release-time vs push-time work**. Expensive or
flaky-prone steps (3-OS jpackage matrix) run only on tags; cheap correctness
gates (reactor verify, Docker build smoke) run on every push. This keeps
per-push feedback fast and free while making releases comprehensive.

## Consequences

- Easier: reproducible deploys (`ghcr.io/...:1.2.0` instead of a hand-built
  image), shippable desktop clients, answering "what version is running?"
- Easier: future CD — whatever the deploy target becomes, it starts from a
  published, digest-pinned image.
- Harder: cutting a release is now a process (tag → wait for matrix) instead
  of `mvn package` — the intended friction.
- To revisit: installer signing/notarization when SmartScreen/Gatekeeper
  warnings become a real adoption problem; desktop auto-update (out of scope);
  registry choice if a cloud target is picked; per-module CI if build times
  grow.

## Action items (phased)

### Phase 1 — CI hardening (no new surface) — ✅ done 2026-07-16
1. [x] Fix surefire artifact path to `**/target/surefire-reports/`.
2. [x] Add `concurrency` group with `cancel-in-progress: true`.
3. [x] Add Docker build smoke step (`docker build .`, no push) to the CI job.

### Phase 2 — Server image publishing — ✅ done 2026-07-16
4. [x] Add `docker-publish.yml`: on `main` push, build and push
       `ghcr.io/<owner>/fax-trident:sha-<short>` using `GITHUB_TOKEN`.
       (Also handles `v*` tags — semver + `latest` — so Phase 4 needed no
       second publish path.)
5. [x] Document `docker pull` + required env vars in README's deploy section.

### Phase 3 — Desktop packaging — ✅ done 2026-07-16
6. [x] Add jpackage input assembly to `fax-trident-desktop` (`dist` profile;
       app name + launcher class in the workflow; icon deferred — no asset
       yet, see AUDIT.md), keeping the module Spring-free per ADR-0001.
7. [x] Add `package-desktop.yml`: 3-OS matrix producing `.msi`, `.dmg`,
       `.deb`; `workflow_dispatch` + `workflow_call` (invoked by Phase 4).
8. [x] Record signing/notarization as a deferred follow-up in AUDIT.md.

### Phase 4 — Releases — workflows done 2026-07-16; first release pending
9. [x] Add `release.yml`: on `v*` tag — `mvn versions:set`, verify, run
       desktop matrix via `workflow_call`, create GitHub Release with
       installers and image digest (digest resolved best-effort, since
       `docker-publish.yml` races on the same tag).
10. [ ] Cut `v1.0.0` end-to-end as validation. **Operator action:**
        `git tag v1.0.0 && git push origin v1.0.0` after the Phase 1-4
        changes land on `main`.
11. [x] Add a "Releasing" section to README (tag format, SemVer-by-contract
        rule).
