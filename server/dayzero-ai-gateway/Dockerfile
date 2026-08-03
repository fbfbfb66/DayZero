# syntax=docker/dockerfile:1
# Build argument allows swapping the Deno base image (e.g. for a private ACR mirror).
ARG DENO_IMAGE=denoland/deno:2.9.0

FROM ${DENO_IMAGE}

# Traceability build arguments. These contain NO secrets and are re-declared after FROM
# so they are available to the LABEL instructions below.
ARG VCS_REF
ARG SOURCE_BUNDLE_SHA
ARG BUILD_DATE
ARG IMAGE_VERSION

WORKDIR /app

# Prefer non-root user for runtime security.
RUN groupadd -r dayzero && useradd -r -g dayzero dayzero

# Copy manifest + lockfile first so dependency caching is reproducible and integrity-checked.
COPY deno.json deno.lock ./
COPY src ./src
# --frozen fails the build if resolution would change deno.lock, guaranteeing the pinned,
# integrity-verified dependency set is what ships in the image.
RUN deno cache --frozen src/main.ts src/title_worker.ts && deno check src/main.ts src/title_worker.ts

USER dayzero

EXPOSE 8080

ENV PORT=8080

LABEL org.opencontainers.image.title="DayZero AI Gateway"
LABEL org.opencontainers.image.description="DayZero AI Gateway for Kimi/Moonshot assistant turns"
LABEL org.opencontainers.image.source="https://github.com/dayzero/dayzero-ai-gateway"
LABEL org.opencontainers.image.licenses="MIT"
LABEL org.opencontainers.image.revision="${VCS_REF}"
LABEL org.opencontainers.image.created="${BUILD_DATE}"
LABEL org.opencontainers.image.version="${IMAGE_VERSION}"
LABEL com.dayzero.source_bundle_sha="${SOURCE_BUNDLE_SHA}"

# --frozen at runtime refuses to start if the lockfile is out of date with deno.json.
CMD ["deno", "run", "--frozen", "--allow-net", "--allow-env", "--allow-read", "src/main.ts"]
