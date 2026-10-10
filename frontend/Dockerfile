# Build context is the REPOSITORY ROOT, not frontend/:
#   docker build -f frontend/Dockerfile .
# `pnpm build` is `tsc && vite build`, and both halves resolve @vizcor/api-types through a
# RELATIVE path to a sibling directory (`../shared/api-types` in vite.config.ts, and the same
# path in tsconfig.json's `paths` + `include`). So the image has to reproduce the repository
# layout: /app/frontend next to /app/shared/api-types.
FROM node:24-slim AS base
# Pin pnpm 9 — the version .github/workflows/ci-frontend.yml installs, and the one
# frontend/pnpm-lock.yaml was written by (lockfileVersion 9.0). `corepack enable pnpm` without
# a version activates the newest release instead, and pnpm 10+ aborts
# `install --frozen-lockfile` with ERR_PNPM_IGNORED_BUILDS because frontend/package.json
# carries no onlyBuiltDependencies allow-list for @swc/core, esbuild and msw.
RUN npm install -g pnpm@9

FROM base AS deps
WORKDIR /app/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM base AS build
WORKDIR /app
COPY frontend frontend
COPY shared/api-types shared/api-types
COPY --from=deps /app/frontend/node_modules ./frontend/node_modules
WORKDIR /app/frontend
RUN pnpm build

# Vite dev server, used by docker-compose.yml (target: dev).
FROM base AS dev
WORKDIR /app
COPY frontend frontend
COPY shared/api-types shared/api-types
COPY --from=deps /app/frontend/node_modules ./frontend/node_modules
WORKDIR /app/frontend
EXPOSE 3000
CMD ["pnpm", "dev", "--host"]

# Deployable static image. LAST on purpose: `docker build -f frontend/Dockerfile .` with no
# --target must produce this, not the dev server.
FROM nginx:alpine AS production
COPY --from=build /app/frontend/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000
