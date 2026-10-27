FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN groupadd --gid 10001 sage && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin sage
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/sage-agent-*.jar /app/sage-agent.jar
RUN mkdir -p /app/data && chown 10001:10001 /app/data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/sage-agent.jar"]
