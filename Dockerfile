FROM python:3.12-slim
# Claude Code CLI: tags tasks via a headless `claude -p` call (see tagger.py).
# Installed as a standalone binary so it doesn't depend on root's home directory.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://claude.ai/install.sh | bash \
    && cp "$(readlink -f /root/.local/bin/claude)" /usr/local/bin/claude \
    && rm -rf /root/.local /root/.claude* /var/lib/apt/lists/*
WORKDIR /srv
COPY server.py tagger.py ./
COPY static ./static
ENV DATA_DIR=/data PORT=8000 PYTHONUNBUFFERED=1 DISABLE_AUTOUPDATER=1
EXPOSE 8000
CMD ["python", "server.py"]
