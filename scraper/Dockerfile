# Service de recherche d'offres : conteneur à part, sans secret ni accès à la base.
FROM python:3.12-slim@sha256:a0e2dd78cc679ddfffea960fe45ecfcff9ac2f68d67917a0edfcde5c2100151b
WORKDIR /app
COPY requirements.txt ./
# versions figées avec leurs empreintes : un paquet modifié en amont est refusé à l'installation
RUN pip install --no-cache-dir --require-hashes -r requirements.txt
COPY . ./
RUN useradd --system --uid 10002 scraper
USER scraper
ENV HOME=/tmp PYTHONDONTWRITEBYTECODE=1
EXPOSE 8000
CMD ["python", "-u", "server.py"]
