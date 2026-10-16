FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 bintime \
    && useradd --uid 10001 --gid bintime --no-create-home bintime

WORKDIR /app
COPY --chown=bintime:bintime build/libs/bintime.jar app.jar
USER bintime

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
