# --- Build frontend ---
FROM node:22-bookworm-slim AS frontend
RUN corepack enable && corepack prepare pnpm@latest --activate
WORKDIR /app/web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
ARG VITE_PUBLIC_POSTHOG_PROJECT_TOKEN
ARG VITE_PUBLIC_POSTHOG_HOST
ENV VITE_PUBLIC_POSTHOG_PROJECT_TOKEN=$VITE_PUBLIC_POSTHOG_PROJECT_TOKEN
ENV VITE_PUBLIC_POSTHOG_HOST=$VITE_PUBLIC_POSTHOG_HOST
RUN pnpm run build

# --- Build edict frontend (npm; default base is root, no basePath needed for direct port) ---
WORKDIR /app/edict-frontend
COPY edict/edict/frontend/package.json edict/edict/frontend/package-lock.json ./
RUN npm install --no-audit --no-fund
COPY edict/edict/frontend/ ./
RUN npm run build

# --- Build backend ---
FROM golang:1.26-alpine AS backend
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=$GOPROXY
RUN apk add --no-cache git gcc musl-dev
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
COPY --from=frontend /app/internal/web/dist ./internal/web/dist
RUN CGO_ENABLED=1 go build -o /oih .

# edict（Go 版）后端：独立 Go module，单独构建二进制
WORKDIR /app/edict
RUN go build -o /edict-go ./cmd/edict
WORKDIR /app

# --- Runtime ---
FROM alpine:3.21
RUN apk add --no-cache ca-certificates
COPY --from=backend /oih /usr/local/bin/oih
COPY --from=backend /edict-go /usr/local/bin/edict-go
COPY --from=frontend /app/edict-frontend/dist /app/edict/edict/frontend/dist
COPY deploy/hub-entrypoint.sh /usr/local/bin/hub-entrypoint.sh
RUN chmod +x /usr/local/bin/hub-entrypoint.sh
EXPOSE 9800 7891
ENTRYPOINT ["/usr/local/bin/hub-entrypoint.sh"]
CMD ["-listen", "0.0.0.0:9800"]
