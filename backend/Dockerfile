FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY lombok.config .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
