# --- build stage ---
FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod 755 gradlew
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# --- run stage ---
FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
WORKDIR /app
RUN groupadd --gid 10001 exchange && useradd --uid 10001 --gid exchange --no-create-home exchange
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
