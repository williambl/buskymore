FROM eclipse-temurin:21-jdk-alpine
COPY build/static/buskymore-all.jar buskymore.jar
ENTRYPOINT ["java", "-jar", "buskymore.jar"]