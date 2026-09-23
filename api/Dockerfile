# syntax=docker/dockerfile:1.7
# TRS API – Nitro-Server (Nuxt 4, node-server) auf Node 24 LTS (Alpine).

FROM node:24-alpine AS build
WORKDIR /src
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0 \
    NUXT_TELEMETRY_DISABLED=1
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml nuxt.config.ts tsconfig.json ./
RUN pnpm install --frozen-lockfile
COPY server ./server
# Standard-Umhänge (assets/capes: catalog.json + PNGs; fehlt der Katalog, startet die API ohne).
COPY assets ./assets
RUN pnpm build

FROM node:24-alpine AS runtime
ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=3000 \
    DATA_DIR=/data
WORKDIR /app
# Nur das gebaute Server-Bundle (bringt seine node_modules selbst mit).
COPY --from=build --chown=root:root /src/.output ./.output
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME ["/data"]
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/v1/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
CMD ["node", ".output/server/index.mjs"]
