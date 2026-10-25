# ---------- Etapa 1: build con Maven ----------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
# Primero el pom: la capa de dependencias se cachea mientras no cambie
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Etapa 2: runtime liviano (solo JRE) ----------
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
USER app
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
