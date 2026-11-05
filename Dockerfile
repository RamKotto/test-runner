# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
 && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/deps

FROM eclipse-temurin:21-jre-jammy
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/deps /app/deps
RUN java -cp "/app/deps/*" com.microsoft.playwright.CLI install --with-deps chromium \
 && rm -rf /var/lib/apt/lists/* \
 && chmod -R a+rX /ms-playwright \
 && groupadd -r app && useradd -r -g app -d /app app \
 && chown -R app:app /app /ms-playwright
COPY --from=build /src/target/*.jar /app/app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
