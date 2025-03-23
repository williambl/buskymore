FROM openjdk:21-jdk-alpine
COPY build/static/buskymore.jar buskymore.jar
ENTRYPOINT ["java", "-jar", "buskymore.jar"]