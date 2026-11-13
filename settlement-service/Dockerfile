# Multi-stage Java build — non-root runtime image
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
RUN apk add --no-cache maven git
COPY pom.xml .
# e2a-java at the version the pom pins (no package registry: built from E2A's tag)
RUN E2A="v$(sed -n 's:.*<e2a.version>\(.*\)</e2a.version>.*:\1:p' pom.xml)" \
 && git clone -q --depth 1 --branch "$E2A" https://github.com/subhamviky/e2a-framework.git /e2a \
 && mvn -B -q -f /e2a/java/pom.xml install -DskipTests
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -u 1001
COPY --from=builder /build/target/*.jar app.jar
ENV SERVER_PORT=8080
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget -q -O- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
