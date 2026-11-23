# Build Stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/load-tester-1.0.0.jar load-tester.jar

ENTRYPOINT ["java", "-jar", "load-tester.jar"]
CMD ["--host", "http://nginx:80", "--strategy", "pessimistic", "--requests", "300", "--threads", "30"]
