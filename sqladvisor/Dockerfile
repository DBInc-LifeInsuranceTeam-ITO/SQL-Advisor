FROM eclipse-temurin:17-jdk AS builder

WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN bash ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre

WORKDIR /app

RUN mkdir -p /app/config /app/data/awr

COPY --from=builder /workspace/build/libs/*.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=dev
ENV AWR_STORAGE_ROOT=/app/data/awr

EXPOSE 8080

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
