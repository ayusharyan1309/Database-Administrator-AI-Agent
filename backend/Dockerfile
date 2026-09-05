# ── Build ────────────────────────────────────────────────────────────────────
# Uses the maven image rather than ./mvnw: this repository has no wrapper
# checked in, and depending on one that is not there fails the build outright.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies in their own layer so source edits do not re-download
# the world on every rebuild.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S optiq && adduser -S optiq -G optiq

COPY --from=build /build/target/*.jar app.jar
RUN chown optiq:optiq /app/app.jar

USER optiq
EXPOSE 8080

# The container is the unit of restart, so let the JVM die on OOM rather than
# limping. MaxRAMPercentage respects the container limit instead of the host's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
