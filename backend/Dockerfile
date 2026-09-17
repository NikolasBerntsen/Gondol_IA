# GondolIA - backend (Spring Boot 3.5 / Java 21).

# ---------- Build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Capa de dependencias: solo se vuelven a descargar si cambia el pom.xml.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package \
    && cp target/gondolia-backend.jar /workspace/app.jar

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre-alpine

# Usuario sin privilegios con UID/GID fijos: el volumen de adjuntos conserva los permisos entre rebuilds.
# /data/uploads se crea acá con ese dueño para que el volumen nombrado lo herede al montarse por primera vez.
RUN addgroup -S -g 10001 gondolia \
    && adduser -S -D -H -u 10001 -G gondolia -h /app -s /sbin/nologin gondolia \
    && mkdir -p /app /data/uploads \
    && chown -R gondolia:gondolia /app /data

WORKDIR /app
COPY --from=build --chown=gondolia:gondolia /workspace/app.jar /app/app.jar

USER gondolia

# JAVA_OPTS: el heap se calcula como porcentaje de la memoria disponible para Docker
# (la comparten la base, la IA y nginx). docker-compose.yml lo puede sobreescribir.
ENV SERVER_PORT=8080 \
    APP_STORAGE_DIR=/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=40.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# wget viene con busybox en la imagen alpine.
HEALTHCHECK --interval=10s --timeout=5s --start-period=240s --retries=12 \
    CMD wget -q -T 4 -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
