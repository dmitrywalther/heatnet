# Сборка
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# Запуск (Java 11 — требование ТЗ, п. 3.2)
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /build/target/heatnet-1.0.0.jar app.jar
RUN mkdir -p /app/data
ENV HEATNET_STORAGE=/app/data
# по умолчанию — под машину проверки из ТЗ (16 ГБ ОЗУ);
# на небольшом VPS переопределяется через JAVA_OPTS (см. deploy/)
ENV JAVA_OPTS="-Xmx12g"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
