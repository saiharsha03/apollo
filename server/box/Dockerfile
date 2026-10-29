# One image, two containers (deploy/box-install.sh): apollo-doorman (python -I /app/doorman.py, holds the environment
# key) and apollo-box (python -I /app/sandbox.py, where Athena's tools run; never the key). Built as if the code inside
# were hostile: base pinned by digest, dependencies from the hash-pinned lock at build time only, no pip in the final
# image, non-root, read-only root fs, all capabilities dropped (flags in box-install.sh).
FROM python:3.12-slim@sha256:cb33f7f771ecec2ef1225cf39b159591a040391bd73e4c01f00fdfc4c47611d2 AS build
ENV PYTHONDONTWRITEBYTECODE=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
COPY requirements.txt /build/requirements.txt
RUN pip install --no-compile --require-hashes --only-binary=:all: --target /deps -r /build/requirements.txt

FROM python:3.12-slim@sha256:cb33f7f771ecec2ef1225cf39b159591a040391bd73e4c01f00fdfc4c47611d2
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/workspace
COPY --from=build /deps /usr/local/lib/python3.12/site-packages/
# Her everyday tools; then no installer is left in the running image.
RUN apt-get update && apt-get install -y --no-install-recommends git curl jq ca-certificates ripgrep \
 && rm -rf /var/lib/apt/lists/* \
 && rm -rf /usr/local/lib/python3.12/site-packages/pip /usr/local/lib/python3.12/site-packages/pip-* \
       /usr/local/lib/python3.12/ensurepip /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.12 \
 && useradd --system --uid 10021 --no-create-home --shell /usr/sbin/nologin box \
 && install -d -m 0755 /workspace /run/box /run/secrets
COPY doorman.py sandbox.py /app/
USER 10021
WORKDIR /workspace
CMD ["python", "-I", "/app/sandbox.py"]
