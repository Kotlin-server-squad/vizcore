# Build context is the REPOSITORY ROOT, not backend/:
#   docker build -f backend/Dockerfile .
# The root context is what lets the image copy every module `backend/settings.gradle.kts`
# includes. :intellij-plugin is NOT one of them — it is opted in with
# -Pvizcore.withPlugin=true only (#130) — so this image never needs the sibling
# intellij-plugin/ tree.
# :coroutine-viz-agent pins a real JDK-17 toolchain (jvmToolchain(17) + a Java toolchain of
# the same version) because the agent jar is injected into arbitrary target JVMs and must keep
# the JVM-17 bytecode floor (D-07). A JDK-21-only image makes Gradle fail configuration with
# "Cannot find a Java installation ... matching {languageVersion=17}", so ship a JDK 17
# alongside the 21 and hand Gradle its path. core/client/cli only set jvmTarget /
# sourceCompatibility, which JDK 21 satisfies on its own.
FROM eclipse-temurin:17-jdk AS jdk17

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk-17

# Wrapper + build scripts first: the dependency layer below then caches independently of
# source edits. Every included module's build file is part of that layer, otherwise Gradle
# cannot configure the build and the prefetch resolves nothing.
COPY backend/gradle gradle
COPY backend/gradlew .
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/detekt.yml backend/detekt-baseline.xml ./
COPY backend/coroutine-viz-core/build.gradle.kts coroutine-viz-core/
COPY backend/coroutine-viz-client/build.gradle.kts coroutine-viz-client/
COPY backend/coroutine-viz-cli/build.gradle.kts coroutine-viz-cli/
COPY backend/coroutine-viz-agent/build.gradle.kts coroutine-viz-agent/
# -Porg.gradle.java.installations.paths (a property, not an env var — the name has dots) is
# what points the toolchain resolver at the JDK 17 copied in above.
RUN ./gradlew dependencies --no-daemon \
      -Porg.gradle.java.installations.paths=/opt/java/openjdk-17 || true

# All four SDK modules the default build includes. buildFatJar needs them: the root project
# depends on :coroutine-viz-core, and the checkBytecode / verifyNoDuplicateSourceFqns guards
# read :coroutine-viz-core and :coroutine-viz-client directly.
COPY backend/coroutine-viz-core coroutine-viz-core
COPY backend/coroutine-viz-client coroutine-viz-client
COPY backend/coroutine-viz-cli coroutine-viz-cli
COPY backend/coroutine-viz-agent coroutine-viz-agent
COPY backend/src src
RUN ./gradlew buildFatJar --no-daemon \
      -Porg.gradle.java.installations.paths=/opt/java/openjdk-17

# eclipse-temurin:21-jre is Ubuntu-based and ships curl, which is what the compose
# healthcheck shells out to; nothing extra is installed for it.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY --from=build /app/src/main/resources/logback-prod.xml /app/logback-prod.xml
EXPOSE 8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dlogback.configurationFile=/app/logback-prod.xml -jar app.jar"]
