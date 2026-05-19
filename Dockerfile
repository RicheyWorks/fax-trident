# Stage 1: Build Stage
FROM maven:3.9.6-eclipse-temurin-21 AS builder

# Metadata
LABEL maintainer="Trident Team"
LABEL description="Enterprise-grade faxing with divine UX and scalable architecture"
LABEL version="1.0.0-SNAPSHOT"

# Set working directory
WORKDIR /app

# Multi-module layout (ADR-0001):
#   - pom.xml                       parent reactor
#   - fax-trident-server/           Spring Boot REST + WS (this image)
#   - fax-trident-desktop/          JavaFX desktop client (NOT in this image)
#
# Only the server module is copied into the image. The desktop module's
# pom is still copied so `mvn` can resolve the reactor — but with `-pl
# fax-trident-server -am` we never compile its sources or pull its
# JavaFX deps. Image size and build time both win.

# Layer 1: poms only, so dependency resolution is cached separately from sources.
COPY pom.xml .
COPY fax-trident-server/pom.xml fax-trident-server/pom.xml
COPY fax-trident-desktop/pom.xml fax-trident-desktop/pom.xml
RUN mvn -pl fax-trident-server -am dependency:go-offline -B

# Layer 2: server sources.
COPY fax-trident-server/src fax-trident-server/src
RUN mvn -pl fax-trident-server -am clean package -DskipTests

# Stage 2: Runtime Stage
#
# Switched from eclipse-temurin:21-jre-alpine to 21-jre-jammy as part of
# tech-debt #9 close-out (2026-05-17). Alpine ships musl libc and omits the
# font infrastructure (no fontconfig, no system fonts) that PDFBox 2.x leans
# on for text extraction and the rendered preview path. The Alpine image was
# silently producing degraded output and occasional NPEs from
# java.awt.GraphicsEnvironment. Jammy (Ubuntu 22.04) carries fontconfig and a
# baseline of DejaVu fonts via the JRE package — image size grows from ~150 MB
# to ~250 MB, which is the right trade for "PDFs actually render."
#
# If a smaller runtime becomes important later, the right move is the
# eclipse-temurin distroless variant + an explicit `fontconfig` + `fonts-dejavu`
# install in a builder stage; do NOT go back to Alpine without first proving
# PDFBox works on it for this codebase's specific text/barcode flows.
FROM eclipse-temurin:21-jre-jammy

# Set working directory
WORKDIR /app

# Environment variables for runtime configuration.
#
# Secrets are intentionally NOT defaulted here — baking them into the image
# is a `docker history` leak. The application will fail fast at startup if
# any required secret is missing. Inject them at runtime via:
#   docker run -e JWT_SECRET=... -e SPRING_DATASOURCE_PASSWORD=... ...
# or via a secrets manager / Kubernetes Secret.
#
# Required at runtime (no defaults):
#   JWT_SECRET                       random, >=32 bytes (e.g. `openssl rand -base64 48`)
#   SPRING_DATASOURCE_PASSWORD       database password
#
# Note: OAUTH2_GOOGLE_* env vars were removed with tech-debt #5 (stateless
# JWT-only auth — see SecurityConfig javadoc).
#
# Note 2: java.awt.headless=true is intentionally KEPT in JAVA_OPTS even
# though the server no longer ships JavaFX (ADR-0001). PDFBox still touches
# java.awt for font metrics during text extraction, so the flag remains a
# small belt-and-braces measure.
ENV SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/faxtrident \
    SPRING_DATASOURCE_USERNAME=trident \
    SPRING_DATA_REDIS_HOST=redis \
    SPRING_DATA_REDIS_PORT=6379 \
    JAVA_OPTS="-Xms256m -Xmx512m -Djava.awt.headless=true"

# Expose application port
EXPOSE 8080

# Copy the built JAR from the builder stage. The server module's pom
# pins finalName to fax-trident-1.0.0-SNAPSHOT (see fax-trident-server/pom.xml)
# so this path is stable across the multi-module split.
COPY --from=builder /app/fax-trident-server/target/fax-trident-1.0.0-SNAPSHOT.jar app.jar

# Healthcheck for container monitoring (enabled by actuator)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entry point with Java options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Volume for persistent data (e.g., barcodes)
VOLUME /app/barcodes
