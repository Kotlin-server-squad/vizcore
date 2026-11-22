# Build context is the REPOSITORY ROOT, not frontend/:
#   docker build -f frontend/Dockerfile .
# `pnpm build` is `tsc && vite build`, and both halves resolve @vizcor/api-types through a
# RELATIVE path to a sibling directory (`../shared/api-types` in vite.config.ts, and the same
# path in tsconfig.json's `paths` + `include`). So the image has to reproduce the repository
# layout: /app/frontend next to /app/shared/api-types.
# Node stages run on the BUILDER's platform: `dist/` is static files, the same for every
# architecture, so a multi-arch build (deploy.yml, #269) runs `pnpm build` once, natively, and
# only the nginx stage is produced per platform. It has no RUN step, so nothing runs emulated.
FROM --platform=$BUILDPLATFORM node:24-slim AS base
# Pin pnpm 9 — the version .github/workflows/ci-frontend.yml installs, and the one
# frontend/pnpm-lock.yaml was written by (lockfileVersion 9.0). `corepack enable pnpm` without
# a version activates the newest release instead, and pnpm 10+ aborts
# `install --frozen-lockfile` with ERR_PNPM_IGNORED_BUILDS because frontend/package.json
# carries no onlyBuiltDependencies allow-list for @swc/core, esbuild and msw.
RUN npm install -g pnpm@9

FROM base AS deps
WORKDIR /app/frontend
# .npmrc too: its shamefully-hoist puts @heroui/theme at node_modules/@heroui/theme, the path
# tailwind.config.ts scans for HeroUI's classes. Without it the image's CSS has none of them (#266).
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/.npmrc ./
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
# nginx-unprivileged runs as uid 101 (no root, no `user` directive) and its own nginx.conf
# already puts the pid file and every temp path (client_body, proxy, fastcgi, uwsgi, scgi) under
# /tmp, so with a read-only root filesystem only /tmp has to be writable (a Kubernetes Pod
# Security "restricted" pod mounts an emptyDir there). Port 3000 needs no capability. Pinned to
# an exact release; this stage still has no RUN step, so a multi-arch build needs no emulation.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine AS production
COPY --from=build /app/frontend/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000
