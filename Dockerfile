# Build stage: the Gradle build runs on a full JDK, then the executable jar is exploded
# into Spring Boot layers so that dependency layers cache independently of application code.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# Wrapper and build scripts first, so the dependency download layer is reused while only
# source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon --console=plain bootJar
# The build leaves two jars behind, the executable boot jar and a -plain.jar. Selecting by
# what the other one is named keeps the version out of the build: matching on a version
# fragment breaks the image the day the project stops being a snapshot.
RUN cp "$(ls build/libs/*.jar | grep -v -- '-plain\.jar$')" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Runtime stage: a slim JRE, no build toolchain. JVM defaults are container-aware on 21, so
# no -Xmx is set. The base image already ships curl, which the health probe uses.
FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system app \
    && useradd --system --gid app --home /application app
WORKDIR /application
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
# Readiness, not liveness: this gate exists for compose dependents waiting on service_healthy,
# where the right signal is whether the app can serve, not merely that the process is up. A
# database blip does not restart the container through here, since restarts follow the
# orchestrator's own probes rather than the Docker healthcheck.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]
