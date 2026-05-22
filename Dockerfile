FROM gradle:9.2.1-jdk17 AS build
# Set container working directory to /app
WORKDIR /app
# Optional build metadata overrides for environments where `.git` is not present
ARG CABO_SERVER_BUILD_COMMIT_ID
ARG CABO_SERVER_BUILD_COMMIT_TIMESTAMP
# Copy Gradle configuration files
COPY gradlew /app/
COPY gradle /app/gradle
# Normalize Windows line endings and ensure Gradle wrapper is executable
RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew
# Copy build script and source code
COPY build.gradle settings.gradle /app/
COPY src /app/src
# Run tests and coverage report during image build so failures are visible in docker build logs
RUN ./gradlew test jacocoTestReport --no-daemon --max-workers=1 \
    -Dorg.gradle.daemon=false \
    -Dorg.gradle.jvmargs="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
# Build the server
RUN ./gradlew clean bootJar --no-daemon --max-workers=1 -x test \
    -Dorg.gradle.daemon=false \
    -Dorg.gradle.jvmargs="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"

# make image smaller by using multi stage build
FROM eclipse-temurin:17-jdk
# Optional build metadata overrides (must also exist in runtime stage)
ARG CABO_SERVER_BUILD_COMMIT_ID
ARG CABO_SERVER_BUILD_COMMIT_TIMESTAMP
# Set the env to "production"
ENV SPRING_PROFILES_ACTIVE=production
ENV LOGGING_LEVEL_ROOT=OFF
ENV LOGGING_LEVEL_CH_UZH_IFI_HASE_SOPRAFS26=OFF
ENV CABO_SERVER_BUILD_COMMIT_ID=${CABO_SERVER_BUILD_COMMIT_ID}
ENV CABO_SERVER_BUILD_COMMIT_TIMESTAMP=${CABO_SERVER_BUILD_COMMIT_TIMESTAMP}
# get non-root user
RUN groupadd appgroup && \
    useradd -r -g appgroup appuser
USER appuser
# Set container working directory to /app
WORKDIR /app
# copy built artifact from build stage
COPY --from=build /app/build/libs/*.jar /app/soprafs26.jar
# Expose the port on which the server will be running (based on application.properties)
EXPOSE 8080
# start server
CMD ["java", "-jar", "/app/soprafs26.jar"]
