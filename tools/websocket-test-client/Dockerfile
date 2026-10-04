FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

FROM nginx:1.28-alpine
ENV BACKEND_UPSTREAM=backend:8082
COPY deploy /etc/nginx/templates
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
