
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

# Add Aiven's CA certificate to Java's trusted certificates
COPY ca.pem /tmp/aiven-ca.pem
RUN keytool -importcert \
    -noprompt \
    -trustcacerts \
    -alias aiven-kafka-ca \
    -file /tmp/aiven-ca.pem \
    -cacerts \
    -storepass changeit \
    && rm /tmp/aiven-ca.pem

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
