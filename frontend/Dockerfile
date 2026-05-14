FROM node:22-alpine AS builder

WORKDIR /workspace

COPY package*.json ./
RUN npm ci

COPY . .
ENV VITE_API_BASE_URL=/api
RUN npm run build-only

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html

EXPOSE 80
