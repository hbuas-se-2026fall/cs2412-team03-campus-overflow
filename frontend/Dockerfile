# ==========================================
# 阶段 1：构建前端静态文件
# ==========================================
FROM node:20-alpine AS builder
WORKDIR /app

# 安装依赖 (如果你们用 pnpm，这里改成 pnpm)
COPY package*.json ./
RUN npm ci

# 拷贝源码并构建
COPY . .
RUN npm run build

# ==========================================
# 阶段 2：Nginx 运行环境
# ==========================================
FROM nginx:alpine

# 移除默认配置，拷贝我们的配置
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 拷贝构建好的静态文件
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]