# 多阶段构建：第一阶段用 Maven 编译，第二阶段只保留 JRE 与可执行 jar（非 root 运行）。
# 已知局限：下面单独 COPY 父 pom 这一层目前并没有真正缓存依赖——紧接着的
# `COPY backend/ ./` 会让后续层全部失效。要真正缓存，需先复制所有模块的 pom
# 再执行 dependency:go-offline，但那会让 Dockerfile 与模块清单强耦合。
# 这里不改构建逻辑（本机无 Docker，无法验证），仅如实标注，见 Issue #35 评论。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml ./pom.xml
COPY backend/ ./
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget
WORKDIR /app
COPY --from=build /src/co-bootstrap/target/co-bootstrap-*.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
