FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew buildFatJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY --from=build /app/src/main/resources/logback-prod.xml /app/logback-prod.xml
EXPOSE 8080
ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback-prod.xml", "-jar", "app.jar"]
