FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY pom.xml ./
COPY scheduler/pom.xml ./scheduler/
COPY worker/pom.xml ./worker/
RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -f pom.xml dependency:go-offline -pl worker -am -q
COPY worker/src ./worker/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -pl worker -am -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/worker/target/*.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
