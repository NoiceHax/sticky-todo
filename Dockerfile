FROM python:3.12-slim
WORKDIR /srv
COPY server.py ./
COPY static ./static
ENV DATA_DIR=/data PORT=8000 PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["python", "server.py"]
