FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/fhirpath-server.jar app.jar
COPY kotlin-fhirpath-config.json kotlin-fhirpath-config.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
