# Lightweight Python 3.12 runtime.
FROM python:3.12-slim

# Standard Python container settings.
# UV_LINK_MODE=copy is recommended when using a BuildKit uv cache mount.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy

WORKDIR /app

# System dependencies required by audio/TTS processing.
RUN apt-get update \
    && apt-get install --no-install-recommends --yes espeak-ng ffmpeg libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

# Pin uv so builds do not unexpectedly change when "latest" moves.
COPY --from=ghcr.io/astral-sh/uv:0.12.13 \
    /uv /usr/local/bin/uv

# Copy dependency metadata before source code so this layer stays cached
# when only application code changes.
COPY pyproject.toml uv.lock ./

# Persist uv's package cache across Docker builds.
# If this layer must rebuild, uv can reuse previously downloaded packages.
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

# Application files are copied after dependencies to preserve dependency cache.
COPY alembic.ini ./alembic.ini
COPY alembic ./alembic
COPY app ./app

# Start the production FastAPI server on container port 8000.
CMD ["uv", "run", "fastapi", "run", "app/main.py", "--host", "0.0.0.0", "--port", "8000"]