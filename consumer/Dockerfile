FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY . .
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:11-jre
COPY --from=build /app/target/skitrak-consumer-1.0.jar /app.jar
CMD ["java", "-Xmx512m", "-jar", "/app.jar"]
