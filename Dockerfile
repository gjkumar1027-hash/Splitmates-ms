# syntax=docker/dockerfile:1
# SplitMates API — multi-stage build for Render (or any Docker host).

# ---------- build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Maven wrapper + POM first so dependency downloads are cached between builds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# The wrapper may have Windows (CRLF) line endings when committed from Windows.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests need a live database, so they run in CI/locally rather than during the image build.
RUN ./mvnw -B -q package -DskipTests && cp target/splitmates-api-*.jar app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 --home /app spring \
    && mkdir -p /app/uploads && chown -R spring /app
USER spring

COPY --from=build --chown=spring /app/app.jar app.jar

# Render injects PORT (application.yml: server.port=${PORT:8080}).
ENV PORT=8080 \
    UPLOAD_DIR=/app/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
