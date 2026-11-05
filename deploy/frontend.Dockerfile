FROM node:20-alpine AS build
WORKDIR /src
# 必须连 package-lock.json 一起复制。原写法只 COPY package.json 再 npm install，
# lock 文件根本没进镜像，npm 每次重新解析依赖树——同一份 Dockerfile 在不同日期
# 构建出的前端产物可能不同，且可能与 CI 用 lock 装出的版本不一致（Issue #36）。
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
