FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY agent.py dicom_header.py edge_pass.py image_server.py /app/
COPY scripts/render_orthanc_config.py /app/scripts/render_orthanc_config.py

ENV PYTHONUNBUFFERED=1
# Image service for browsers on the hospital network (see lan_port in the config).
EXPOSE 8090
CMD ["python3", "/app/agent.py", "run", "--config", "/config/config.json"]
