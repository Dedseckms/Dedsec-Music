FROM python:3.12.16-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && apt-get clean && find /var/lib/apt/lists -type f -delete
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: --require-hashes -r requirements.txt
COPY audio.py profile_api.py server.py yandex_import.py yandex_source.py import_errors.py import_playlists.py ./
COPY radio.py radio_catalog.py radio_features.py ./
COPY admin_api.py ./
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 1000:1000
EXPOSE 8080
CMD ["python", "server.py"]
