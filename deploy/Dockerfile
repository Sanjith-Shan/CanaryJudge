# One image for every CanaryJudge process; the command picks the jar.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY *.jar /app/
EXPOSE 8080 8000 8001 8090
