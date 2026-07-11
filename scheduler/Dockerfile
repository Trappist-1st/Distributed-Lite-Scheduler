# 多阶段构建：build → runtime
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY pom.xml ./
COPY scheduler/pom.xml ./scheduler/
COPY worker/pom.xml ./worker/
# 先拉依赖（利用层缓存）
RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -f pom.xml dependency:go-offline -pl scheduler -am -q
COPY scheduler/src ./scheduler/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -pl scheduler -am -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/scheduler/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
