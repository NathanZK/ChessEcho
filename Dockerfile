# Builder stage
# Pin base image to specific digest for reproducible builds and security auditing
FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS builder
WORKDIR /app

# Copy only dependency definition files first — this layer is cached
# as long as gradle wrapper and build files don't change
COPY gradle/wrapper gradle/wrapper
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x gradlew

# Download dependencies — cached as long as build.gradle.kts doesn't change
RUN ./gradlew dependencies --no-daemon

# Copy source — cache only invalidates from here on source changes
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# Runtime stage
# Pin base image to specific digest for reproducible builds and security auditing
FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c
WORKDIR /app
RUN apk add --no-cache --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing stockfish || true
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
