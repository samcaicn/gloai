# AI 转型驾驶舱 (cockpit) —— 独立 Next.js 16 前端
# 构建上下文：仓库根目录（.）；源码来自 cockpit（只读拷贝，不修改子模块）
FROM node:20-alpine AS build
WORKDIR /app
COPY cockpit/package.json cockpit/package-lock.json ./
RUN npm ci
COPY cockpit/ ./
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/next.config.ts ./next.config.ts
EXPOSE 3000
CMD ["npx", "next", "start", "-H", "0.0.0.0", "-p", "3000"]
