# Stage 1 - Build
FROM gradle:jdk8 as builder
RUN gradle --version && java -version
WORKDIR /home/gradle/app
# Only copy gradle dependency-related files
COPY --chown=gradle:gradle build.gradle /home/gradle/app/
COPY --chown=gradle:gradle gradle.properties /home/gradle/app/

# Trigger Build with only gradle files to download proj gradle dependencies
# This build is expected to fail since there is obviously no src code at this point
# We'll just route the output to a black hole and swallow the error
# Purpose: This layer will be cached so after 1st build we can pick up from here with
# all of our gradle dependencies already downloaded
# Adds ~7 secs to 1st build, (~1min), subsequent builds will be ~20 secs
# Without this 1-liner every container build would be ~1min
RUN gradle build --no-daemon > /dev/null 2>&1 || true

COPY --chown=gradle:gradle ./src /home/gradle/app/src
RUN gradle bootJar --no-daemon


# Stage 2 - Create a size optimized Image for our Service with only what we need to run
FROM openjdk:8-jre-slim
EXPOSE 8010
WORKDIR /opt/demo_app
COPY --from=builder /home/gradle/app/src/dist/conf/cloudwatch_scrape_config_sample.yml ./cloudwatch_scrape_config.yml
COPY --from=builder /home/gradle/app/build/libs/* ./
COPY --from=builder /home/gradle/app/build/resources/main/*.xml ./
COPY --from=builder /home/gradle/app/build/resources/main/*.properties ./
CMD ["/bin/sh", "-c", "java -jar app-*.jar --spring.config.location=application.properties"]