# Multi-stage build for lightweight final image
FROM maven:3.9.8-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and compile jar
COPY src src
RUN mvn clean package -DskipTests -B

# Final runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Hugging Face Spaces requires user with UID 1000
RUN adduser -D -u 1000 user && \
    mkdir -p /app && \
    chown -R user:user /app

USER user

# Copy built jar
COPY --from=builder --chown=user:user /app/target/*.jar app.jar

ENV PORT=7860
EXPOSE 7860

ENTRYPOINT ["java", "-jar", "app.jar"]
