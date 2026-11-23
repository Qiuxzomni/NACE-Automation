FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py dashboard.html ./
RUN useradd -r -u 10001 app && mkdir /data && chown app /data
USER app
