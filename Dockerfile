# ── 1-bosqich: Build ──────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

# Avval faqat pom.xml ni nusxalaymiz — dependency cache uchun
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Keyin manba kodini nusxalaymiz
COPY src ./src
RUN mvn package -DskipTests -B

# ── 2-bosqich: Run ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Uploads papkasi
RUN mkdir -p /app/uploads

# JAR faylni ko'chirish
COPY --from=builder /app/target/*.jar app.jar

# Port
EXPOSE 8080

# JVM optimizatsiya (Render free tier uchun xotira tejash)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]