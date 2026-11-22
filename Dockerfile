# syntax=docker/dockerfile:1
# SplitMates API — multi-stage build for Render (or any Docker host).

# ---------- build stage ----------
# Uses the official Maven image: the repo has no .mvn/wrapper directory, so ./mvnw can't run.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# POM first so dependency downloads are cached between builds.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src/ src/
# Tests need a live database, so they run in CI/locally rather than during the image build.
RUN mvn -B -q package -DskipTests && cp target/splitmates-api-*.jar app.jar

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
