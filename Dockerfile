# ---- 1. interface Angular ----
# Images de base épinglées par empreinte (mises à jour proposées par Dependabot).
FROM oven/bun:1.4.3-slim@sha256:14a223fa35747ec728bce300ef85888bc5f7958164d15bbba6a54b22b52a3288 AS frontend
WORKDIR /app
COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile
COPY frontend/ ./
RUN bun run build

# ---- 2. serveur Spring Boot (l'interface compilée est embarquée dans le jar) ----
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS backend
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/dist/frontend/browser ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# ---- 3. image finale : uniquement Java (la recherche d'offres tourne dans un conteneur à part, voir scraper/) ----
FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*
COPY --from=backend /app/target/candidatures.jar /app/candidatures.jar

RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /app
ENV TZ=Europe/Paris
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/candidatures.jar"]
