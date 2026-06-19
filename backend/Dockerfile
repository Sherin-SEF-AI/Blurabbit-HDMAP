# Blurabbit HD-map backend image.
#
# The service is part of a Gradle build that also contains Android modules, so the distribution is
# built on the host/CI (which has the Android SDK) and packaged here:
#
#   export JAVA_HOME=/path/to/jdk17
#   ./gradlew :backend:installDist            # → backend/build/install/backend
#   docker build -t blurabbit/hdmap-backend -f backend/Dockerfile backend
#
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY build/install/backend /app

ENV PORT=8080 \
    STORE=postgres \
    DB_URL=jdbc:postgresql://postgis:5432/blurabbit \
    DB_USER=blurabbit \
    DB_PASSWORD=blurabbit

EXPOSE 8080
# Container healthcheck hits the liveness endpoint.
HEALTHCHECK --interval=15s --timeout=3s --retries=4 \
    CMD wget -qO- http://localhost:8080/v1/health || exit 1

ENTRYPOINT ["/app/bin/backend"]
