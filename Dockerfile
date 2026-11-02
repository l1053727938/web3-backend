# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先复制 pom.xml 单独下载依赖，利用 Docker 层缓存加速重复构建
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 再复制源码并打包（跳过测试）
COPY src ./src
RUN mvn package -DskipTests -B

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 创建非 root 用户运行
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

COPY --from=builder /build/target/web3-backend-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# 容器化最佳实践：让 JVM 感知容器内存限制
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
