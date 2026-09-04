# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S optiq && adduser -S optiq -G optiq

COPY --from=build /app/target/*.jar app.jar

USER optiq

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
