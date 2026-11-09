# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Container image of Warden (CON-009, SDD V21): built from source in a JDK stage, run as a
# non-root user on a JRE with the Spring Boot jar extracted in layers, so a code change rebuilds
# only the application layer.
#
#   docker build -t warden:local .
#
# Every image is pinned by digest, the syntax image included (REQ-BUILD-003).

# ---- Build: compile and package with the Maven wrapper (tests run in CI, not here) ----
FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# The cache mount keeps the Maven repository between builds without putting it in a layer.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true -Djacoco.skip=true package \
    && cp target/warden-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Runtime: JRE only, non-root, layers from the least to the most frequently changed ----
FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS runtime

# The key directory belongs to the runtime user, so a volume mounted on it is writable when the
# entry point generates the key file of the reference environment.
RUN groupadd --system --gid 10001 warden \
    && useradd --system --uid 10001 --gid warden --no-create-home --shell /usr/sbin/nologin warden \
    && mkdir -p /var/lib/warden/keys \
    && chown warden:warden /var/lib/warden/keys \
    && chmod 700 /var/lib/warden/keys

WORKDIR /app
COPY --from=build --chown=warden:warden /workspace/extracted/dependencies/ ./
COPY --from=build --chown=warden:warden /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=warden:warden /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=warden:warden /workspace/extracted/application/ ./
COPY --chmod=0555 src/main/docker/entrypoint.sh /usr/local/bin/warden-entrypoint

USER 10001:10001

# 8080: API with the local profile; 8443: API over TLS with the prod-like profile. The management
# port (8081) is deliberately not exposed: it is reached only inside the deployment network.
EXPOSE 8080 8443

# The entry point execs the JVM, which runs as PID 1 with -XX:MaxRAMPercentage=60 and receives
# SIGTERM itself (REQ-AVAIL-002); it also reads the key file named by WARDEN_KEYS_FILE, if any.
ENTRYPOINT ["/usr/local/bin/warden-entrypoint"]