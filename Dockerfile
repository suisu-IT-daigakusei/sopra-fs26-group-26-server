FROM gradle:9.6.1-jdk25 AS build

WORKDIR /home/gradle/project

ARG CABO_SERVER_BUILD_COMMIT_ID
ARG CABO_SERVER_BUILD_COMMIT_TIMESTAMP

COPY --chown=gradle:gradle gradlew build.gradle settings.gradle ./
COPY --chown=gradle:gradle gradle ./gradle
RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew

COPY --chown=gradle:gradle src ./src
RUN ./gradlew --no-daemon --max-workers=1 bootJar -x test

FROM eclipse-temurin:25-jre-noble AS runtime

ARG CABO_SERVER_BUILD_COMMIT_ID
ARG CABO_SERVER_BUILD_COMMIT_TIMESTAMP

ENV SPRING_PROFILES_ACTIVE=production \
    CABO_SERVER_BUILD_COMMIT_ID=${CABO_SERVER_BUILD_COMMIT_ID} \
    CABO_SERVER_BUILD_COMMIT_TIMESTAMP=${CABO_SERVER_BUILD_COMMIT_TIMESTAMP}

RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=10001:10001 /home/gradle/project/build/libs/*.jar /app/server.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/server.jar"]
