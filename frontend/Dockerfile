FROM node:24-alpine AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Only public, same-origin paths enter the JavaScript bundle.
ENV VITE_API_BASE_URL=/api/v1 \
    VITE_WS_BASE_URL=/ws \
    VITE_USE_MSW=false
RUN npm run build && rm -f dist/mockServiceWorker.js

FROM nginx:stable-alpine
COPY nginx/default.conf.template /etc/nginx/app.conf.template
COPY nginx/40-render-config.sh /docker-entrypoint.d/40-render-config.sh
RUN sed -i 's/\r$//' /docker-entrypoint.d/40-render-config.sh \
    && chmod +x /docker-entrypoint.d/40-render-config.sh
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/healthz > /dev/null || exit 1
