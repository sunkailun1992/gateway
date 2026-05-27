FROM eclipse-temurin:17-jre
COPY build/libs/gateway-1.0.0.jar /app/gateway-1.0.0.jar
WORKDIR /app/
ENTRYPOINT ["java", "-jar", "/app/gateway-1.0.0.jar"]
