# ---- 1. interface Angular ----
FROM node:24-bookworm-slim AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx ng build

# ---- 2. serveur Spring Boot (l'interface compilée est embarquée dans le jar) ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/dist/frontend/browser ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# ---- 3. image finale : Java pour le serveur, Python pour les scripts de recherche d'offres ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 python3-venv tzdata \
    && rm -rf /var/lib/apt/lists/*
COPY scraper/requirements.txt /app/scraper/requirements.txt
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir -r /app/scraper/requirements.txt
COPY scraper/ /app/scraper/
COPY --from=backend /app/target/candidatures.jar /app/candidatures.jar

RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /app
ENV SCRAPER_PYTHON=/opt/venv/bin/python \
    SCRAPER_SCRIPT=/app/scraper/scrape.py \
    TZ=Europe/Paris
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/candidatures.jar"]
