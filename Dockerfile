# syntax=docker/dockerfile:1
FROM node:22-alpine AS web-build
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS java-build
WORKDIR /build
COPY scripts/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository mvn -q dependency:go-offline
COPY src ./src
COPY --from=web-build /build/src/main/resources/static ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests package

FROM node:22-alpine AS backend
ARG APP_UID=1000
ARG APP_GID=1000
ARG CLAUDE_CODE_VERSION=2.1.317
RUN apk add --no-cache openjdk21-jre-headless python3 git bash curl tini shadow \
    && test -n "$CLAUDE_CODE_VERSION" \
    && npm install -g "@anthropic-ai/claude-code@$CLAUDE_CODE_VERSION" \
    && if [ "$APP_UID" != "1000" ]; then usermod -u "$APP_UID" node; fi \
    && if getent group "$APP_GID" >/dev/null; then usermod -g "$APP_GID" node; else groupmod -g "$APP_GID" node; fi \
    && mkdir -p /app/data/supplier-kb /home/node/.claude \
    && chown -R node:"$APP_GID" /app /home/node
WORKDIR /app
COPY --from=java-build /build/target/feishu-claude-v2-1.0-SNAPSHOT.jar /app/app.jar
ENV HOME=/home/node
USER node
EXPOSE 8080
ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "/app/app.jar"]

FROM nginx:1.28-alpine AS frontend
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=web-build /build/src/main/resources/static /usr/share/nginx/html
EXPOSE 80
