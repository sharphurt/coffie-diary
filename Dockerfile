FROM node:24.20.0-alpine AS frontend
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /web/dist ./src/main/resources/static
RUN mvn -B verify
FROM eclipse-temurin:21.0.9_10-jre-alpine
WORKDIR /app
RUN addgroup -S coffee && adduser -S coffee -G coffee && mkdir -p /data/media && chown -R coffee:coffee /data
COPY --from=backend /build/target/coffee-diary-0.1.0.jar app.jar
USER coffee
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
