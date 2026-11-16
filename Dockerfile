# syntax=docker/dockerfile:1

# Image unique pour les trois microservices : --build-arg SERVICE=philosopher-service
# (ou monitor-service, supervisor-service).

# --- Étape 1 : compilation de tout le projet avec Maven ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY common common
COPY philosopher-service philosopher-service
COPY monitor-service monitor-service
COPY supervisor-service supervisor-service
COPY system-tests/pom.xml system-tests/pom.xml
# Les tests tournent dans la CI, avant la construction de l'image.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -q -DskipTests -pl philosopher-service,monitor-service,supervisor-service -am package

# --- Étape 2 : image d'exécution minimale (JRE seul, utilisateur non privilégié) ---
FROM eclipse-temurin:21-jre
ARG SERVICE
RUN useradd --system --create-home --uid 10001 dining \
    && mkdir /data && chown dining /data
USER dining
WORKDIR /app
COPY --from=build /workspace/${SERVICE}/target/${SERVICE}-*-exec.jar app.jar
VOLUME /data
# MaxRAMPercentage : la JVM se cale sur la limite mémoire du conteneur.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
