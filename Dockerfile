# SoarerAlert 生产镜像构建文件。
# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml maven-settings.docker.xml ./
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s maven-settings.docker.xml -DskipTests dependency:go-offline

COPY src ./src
COPY docker/HealthCheck.java ./HealthCheck.java
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -s maven-settings.docker.xml -DskipTests package
RUN mkdir -p /workspace/healthcheck \
    && javac -d /workspace/healthcheck /workspace/HealthCheck.java

FROM eclipse-temurin:25-jre
RUN groupadd --system soarer \
    && useradd --system --gid soarer --home /app soarer

WORKDIR /app
COPY --from=build /workspace/target/soarer-alert-service-1.0-SNAPSHOT.jar app.jar
COPY --from=build /workspace/healthcheck /app/healthcheck

ENV SERVER_PORT=9900 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 9900
USER soarer
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["java", "-cp", "/app/healthcheck", "HealthCheck"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
