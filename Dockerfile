# Stage 1: Build Stage
FROM maven:3.9.6-eclipse-temurin-21 AS builder

# Metadata
LABEL maintainer="Trident Team"
LABEL description="Enterprise-grade faxing with divine UX and scalable architecture"
LABEL version="1.0.0-SNAPSHOT"

# Set working directory
WORKDIR /app

# Copy Maven POM and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build the application
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime Stage
FROM eclipse-temurin:21-jre-alpine

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
#   OAUTH2_GOOGLE_CLIENT_ID          Google OAuth client id
#   OAUTH2_GOOGLE_CLIENT_SECRET      Google OAuth client secret
ENV SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/faxtrident \
    SPRING_DATASOURCE_USERNAME=trident \
    SPRING_DATA_REDIS_HOST=redis \
    SPRING_DATA_REDIS_PORT=6379 \
    JAVA_OPTS="-Xms256m -Xmx512m -Djava.awt.headless=true"

# Expose application port
EXPOSE 8080

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/fax-trident-1.0.0-SNAPSHOT.jar app.jar

# Healthcheck for container monitoring (enabled by actuator)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entry point with Java options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# Volume for persistent data (e.g., barcodes)
VOLUME /app/barcodes