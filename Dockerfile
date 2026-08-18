FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
# No git history in the build context, so the caller passes the version in. Unset -> 0.0.0-unknown.
#   docker build --build-arg VERSION="$(git describe --tags --always --dirty | sed 's/^v//')" .
ARG VERSION=
RUN ./gradlew buildFatJar --no-daemon -PserverVersion="$VERSION"

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/fhirpath-server.jar app.jar
# Kept outside the jar, next to it in the WORKDIR, so it can be edited (or bind-mounted over)
# without rebuilding or restarting.
COPY kotlin-fhirpath-config.json kotlin-fhirpath-config.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
