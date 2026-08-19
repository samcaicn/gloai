# --- Build frontend ---
FROM node:22-bookworm-slim AS frontend
RUN corepack enable && corepack prepare pnpm@latest --activate
WORKDIR /app/web
ENV NPM_CONFIG_REGISTRY=https://registry.npmmirror.com
COPY web/package.json web/pnpm-lock.yaml ./
COPY web/ ./
ARG VITE_PUBLIC_POSTHOG_PROJECT_TOKEN
ARG VITE_PUBLIC_POSTHOG_HOST
ENV VITE_PUBLIC_POSTHOG_PROJECT_TOKEN=$VITE_PUBLIC_POSTHOG_PROJECT_TOKEN
ENV VITE_PUBLIC_POSTHOG_HOST=$VITE_PUBLIC_POSTHOG_HOST
# install+build 清理合并为一层：node_modules 不作为独立 layer 保留，减小 content-store 峰值
RUN pnpm install --frozen-lockfile && \
    pnpm run build && \
    rm -rf /app/web/node_modules /app/web/.pnpm-store

# --- Build edict frontend (npm) ---
WORKDIR /app/edict-frontend
ENV NPM_CONFIG_REGISTRY=https://registry.npmmirror.com
COPY edict/edict/frontend/package.json edict/edict/frontend/package-lock.json ./
COPY edict/edict/frontend/ ./
RUN npm install --no-audit --no-fund && npm run build && rm -rf node_modules

# --- Build golershop ---
FROM golang:1.26-alpine AS golershop-builder
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=$GOPROXY
RUN echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/community" >> /etc/apk/repositories && \
    apk add --no-cache git gcc musl-dev sqlite-dev
WORKDIR /app/golershop
COPY golershop/go.mod golershop/go.sum ./
COPY golershop/ .
RUN go mod download && \
    CGO_ENABLED=1 go build -ldflags "-s -w" -o /golershop . && \
    rm -rf $(go env GOMODCACHE) /root/.cache

# --- Build backend ---
FROM golang:1.26-alpine AS backend
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=$GOPROXY
RUN echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/community" >> /etc/apk/repositories && \
    apk add --no-cache git gcc musl-dev
WORKDIR /app
COPY . .
COPY --from=frontend /app/internal/web/dist ./internal/web/dist
RUN go mod download && \
    CGO_ENABLED=1 go build -ldflags "-s -w" -o /oih . && \
    rm -rf $(go env GOMODCACHE) /root/.cache
# edict（Go 版）后端：独立 Go module，单独构建二进制
WORKDIR /app/edict
RUN go build -o /edict-go ./cmd/edict && rm -rf $(go env GOMODCACHE) /root/.cache
WORKDIR /app

# --- Build multica server (independent Go module, pinned commit) ---
FROM golang:1.26-alpine AS multica-builder
ARG GOPROXY=https://goproxy.cn,direct
ARG MULTICA_COMMIT=49cc7d6f4660963747b32c752f6e0b2744aee0b0
ENV GOPROXY=$GOPROXY
RUN echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/community" >> /etc/apk/repositories && \
    apk add --no-cache git
# 拉取固定提交 MULTICA_COMMIT 的上游源码（CI/有外网环境）；本地也可用
# --build-context multica=本地源码目录 + COPY --from=multica / /src/multica 覆盖。
RUN git init -q /src/multica \
    && cd /src/multica \
    && git remote add origin https://github.com/multica-ai/multica.git \
    && for i in $(seq 1 40); do \
        git -c http.connectTimeout=15 -c http.lowSpeedLimit=1 -c http.lowSpeedTime=5 -c http.timeout=60 fetch --depth 1 origin "$MULTICA_COMMIT" && break \
            || { echo "git fetch attempt $i failed, retrying..." >&2; sleep 10; }; \
      done \
    && git checkout -q FETCH_HEAD
# Hub 租户登录 SSO: 注入 tenant_auth.go handler 并在 router.go 注册 /auth/tenant 路由
COPY deploy/multica-patches/server-internal-handler-tenant_auth.go /src/multica/server/internal/handler/tenant_auth.go
RUN sed -i 's|r.With(authRL).Post("/auth/google", h.GoogleLogin)|&\n\tr.With(authRL).Post("/auth/tenant", h.TenantAuth)|' /src/multica/server/cmd/server/router.go \
    && grep -n 'auth/tenant' /src/multica/server/cmd/server/router.go
WORKDIR /src/multica/server
RUN go mod download && \
    CGO_ENABLED=0 go build -ldflags "-s -w" -o /multica-server ./cmd/server && \
    CGO_ENABLED=0 go build -ldflags "-s -w" -o /multica-migrate ./cmd/migrate && \
    cp -r migrations /multica-migrations && \
    rm -rf $(go env GOMODCACHE) /root/.cache

# --- Build multica web (Next.js 16 standalone) ---
# 仅安装 apps/web 及其依赖的 workspace 包（core/ui/views/tsconfig/eslint-config），
# 跳过 desktop/mobile/docs 及 electron，从而显著降低 node_modules 体积。
# 参考上游 multica Dockerfile.web，但走 npmmirror + 离线 Google Fonts mock。
FROM node:22-alpine AS multica-web-builder
# 离线字体：multica web 的 next/font/google 构建时要访问 fonts.googleapis.com，
# 国内网络不通。用 NEXT_FONT_GOOGLE_MOCKED_RESPONSES 把字体请求指向本地 woff2。
COPY deploy/multica-fonts/fonts /app/multica/fonts
COPY deploy/multica-fonts/mock-google-fonts.js /app/multica/mock-google-fonts.js
RUN echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/community" >> /etc/apk/repositories && \
    apk add --no-cache git
WORKDIR /src/multica
# 仅复制 apps/web + packages（不含 desktop/mobile/docs），避免安装 electron 等多余依赖
COPY --from=multica-builder /src/multica/pnpm-lock.yaml /src/multica/pnpm-workspace.yaml /src/multica/package.json /src/multica/turbo.json ./
RUN corepack enable && \
    PNPM_VERSION="$(node -p 'require("./package.json").packageManager')" && \
    corepack prepare "$PNPM_VERSION" --activate
COPY --from=multica-builder /src/multica/apps/web/ apps/web/
COPY --from=multica-builder /src/multica/packages/ packages/
# Hub 租户登录 SSO: 覆盖 web 登录页(去掉邮箱验证码)、Provider(ws 走 basePath)与语言文件，
# 并给 core 的 api client / auth store 注入 tenantLogin / loginWithTenant 方法。
COPY deploy/multica-patches/views-login-page.tsx /src/multica/packages/views/auth/login-page.tsx
COPY deploy/multica-patches/web-providers.tsx /src/multica/apps/web/components/web-providers.tsx
COPY deploy/multica-patches/en-auth.json /src/multica/packages/views/locales/en/auth.json
RUN sed -i 's|  async verifyCode(email: string, code: string): Promise<LoginResponse> {|  async tenantLogin(input: { email: string; name?: string; exp: number; sig: string }): Promise<LoginResponse> {\n    return this.fetch("/auth/tenant", {\n      method: "POST",\n      body: JSON.stringify(input),\n    });\n  }\n\n&|' /src/multica/packages/core/api/client.ts \
    && sed -i 's|  verifyCode: (email: string, code: string) => Promise<User>;|  loginWithTenant: (input: { email: string; name?: string; exp: number; sig: string }) => Promise<User>;\n  verifyCode: (email: string, code: string) => Promise<User>;|' /src/multica/packages/core/auth/store.ts \
    && sed -i 's|    verifyCode: async (email: string, code: string) => {|    loginWithTenant: async (input: { email: string; name?: string; exp: number; sig: string }) => {\n      const { token, user } = await api.tenantLogin(input);\n      if (!cookieAuth) {\n        storage.setItem("multica_token", token);\n        api.setToken(token);\n      }\n      onLogin?.();\n      identifyAnalytics(user.id, { email: user.email, name: user.name });\n      set({ user });\n      return user;\n    },\n\n    verifyCode: async (email: string, code: string) => {|' /src/multica/packages/core/auth/store.ts \
    && grep -n 'tenantLogin\|loginWithTenant' /src/multica/packages/core/api/client.ts /src/multica/packages/core/auth/store.ts
# 注入 basePath=/apps/multica：hub 以 /apps/multica 反向代理本 web，
# Next.js 需以该子路径为 basePath 生成页面/资源/rewrite，前端才不会直跳 localhost:3001。
RUN sed -i 's|  transpilePackages: \["@multica/core", "@multica/ui", "@multica/views"\],|  basePath: "/apps/multica",\n  transpilePackages: ["@multica/core", "@multica/ui", "@multica/views"],|' apps/web/next.config.ts \
    && grep -n "basePath" apps/web/next.config.ts
RUN printf 'registry=https://registry.npmmirror.com\nshamefully-hoist=true\n' > .npmrc && \
    export ELECTRON_SKIP_BINARY_DOWNLOAD=1 NEXT_FONT_GOOGLE_MOCKED_RESPONSES=/app/multica/mock-google-fonts.js STANDALONE=true && \
    pnpm install --frozen-lockfile && \
    pnpm --filter @multica/web build && \
    rm -rf node_modules .pnpm-store /root/.local/share/pnpm/store /src/multica/.next/cache /root/.cache

# --- Build pgvector (alpine 社区无 pgvector 包，需源码编译) ---
# multica 需要 postgres 的 vector 扩展做向量检索，捆绑进同一镜像实现单容器部署。
FROM alpine:3.21 AS pgvector-builder
ARG PGVECTOR_VERSION=v0.8.0
RUN echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/community" >> /etc/apk/repositories && \
    apk add --no-cache build-base git postgresql17-dev
RUN git init -q /pgvector-src && cd /pgvector-src \
    && git remote add origin https://github.com/pgvector/pgvector.git \
    && for i in $(seq 1 20); do \
        git -c http.connectTimeout=15 -c http.lowSpeedLimit=1 -c http.lowSpeedTime=5 -c http.timeout=60 fetch --depth 1 origin tag "$PGVECTOR_VERSION" && break \
            || { echo "pgvector fetch attempt $i failed, retrying..." >&2; sleep 10; }; \
      done \
    && git checkout -q FETCH_HEAD
WORKDIR /pgvector-src
# OPTFLAGS="" 去掉 Makefile 默认的 -march=native：避免用 CI runner 的 CPU 指令集
# 编译出不兼容其他机器的 vector.so（否则加载时 SIGILL/Illegal instruction）
RUN make OPTFLAGS="" && make install

# --- Runtime ---
FROM alpine:3.21
RUN echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v$(cut -d. -f1,2 /etc/alpine-release)/community" >> /etc/apk/repositories && \
    apk add --no-cache ca-certificates curl libgcc nodejs postgresql17 postgresql17-contrib && \
    mkdir -p /var/lib/postgresql && chown -R postgres:postgres /var/lib/postgresql
# pgvector 编译产物拷入运行时（alpine postgresql17 的 pkglibdir=/usr/lib/postgresql17）
COPY --from=pgvector-builder /usr/lib/postgresql17/vector.so /usr/lib/postgresql17/
COPY --from=pgvector-builder /usr/share/postgresql17/extension/vector* /usr/share/postgresql17/extension/
COPY --from=backend /oih /usr/local/bin/oih
COPY --from=backend /edict-go /usr/local/bin/edict-go
RUN mkdir -p /app/golershop
COPY --from=golershop-builder /golershop /app/golershop/golershop
COPY --from=golershop-builder /app/golershop/manifest/config /app/golershop/manifest/config
COPY --from=golershop-builder /app/golershop/resource/public /app/golershop/resource/public
COPY --from=frontend /app/edict-frontend/dist /app/edict/edict/frontend/dist
# multica server + migrations (migrate 从 cwd 向上搜索 migrations 目录)
COPY --from=multica-builder /multica-server /usr/local/bin/multica-server
COPY --from=multica-builder /multica-migrate /usr/local/bin/multica-migrate
COPY --from=multica-builder /multica-migrations /app/multica/migrations
# multica web standalone (Next.js)
COPY --from=multica-web-builder /src/multica/apps/web/.next/standalone /app/multica/web
COPY --from=multica-web-builder /src/multica/apps/web/.next/static /app/multica/web/apps/web/.next/static
COPY --from=multica-web-builder /src/multica/apps/web/public /app/multica/web/apps/web/public
# multica 字体（web standalone 构建后不再引用这些文件，保留以兼容 mock 产物）
COPY deploy/multica-fonts/fonts /app/multica/fonts
COPY deploy/hub-entrypoint.sh /usr/local/bin/hub-entrypoint.sh
RUN chmod +x /usr/local/bin/hub-entrypoint.sh
# hub 内置应用反向代理：golershop、multica web 都通过 hub 子路径访问（无需对外暴露独立端口）
ENV APP_PROXIES="golershop=http://localhost:8000,multica=http://localhost:3001"
# Hub 租户登录 SSO 共享密钥（oih 与 multica-server 都会读，生产必须覆盖）
ENV MULTICA_SSO_SECRET=change-me-multica-sso
EXPOSE 9800 7891 8000 8080 3001
ENTRYPOINT ["/usr/local/bin/hub-entrypoint.sh"]
CMD ["-listen", "0.0.0.0:9800"]
