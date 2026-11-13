FROM eclipse-temurin:21-jre
COPY build/libs/gateway.jar /app/gateway.jar
WORKDIR /app/
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
