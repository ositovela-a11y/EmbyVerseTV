# Imagen del backend + panel. Construir desde la raíz del repositorio:
#   docker build -f backend/Dockerfile -t embyverse-backend .
FROM node:22-alpine AS build
WORKDIR /src
COPY shared ./shared
COPY backend ./backend
COPY admin-web ./admin-web
RUN cd backend && npm install && npm run build \
 && cd ../admin-web && npm install && npm run build

FROM node:22-alpine
ENV NODE_ENV=production DATA_DIR=/data PORT=8080
WORKDIR /app
COPY --from=build /src/shared ./shared
COPY --from=build /src/backend/dist ./backend/dist
COPY --from=build /src/backend/package.json ./backend/package.json
COPY --from=build /src/admin-web/dist ./admin-web/dist
USER node
VOLUME ["/data"]
EXPOSE 8080
CMD ["node", "--disable-warning=ExperimentalWarning", "backend/dist/src/server.js"]
