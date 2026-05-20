# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml .
COPY proto/ proto/
COPY services/ services/
COPY tests/ tests/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl services/bot-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S -G spring -u 1001 spring
USER spring
WORKDIR /app
COPY --from=builder /workspace/services/bot-service/target/bot-service-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java","-jar","/app/app.jar"]
