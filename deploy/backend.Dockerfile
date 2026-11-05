# 多阶段构建：第一阶段用 Maven 编译，第二阶段只保留 JRE 与可执行 jar（非 root 运行）。
# 已知局限：下面单独 COPY 父 pom 这一层目前并没有真正缓存依赖——紧接着的
# `COPY backend/ ./` 会让后续层全部失效。要真正缓存，需先复制所有模块的 pom
# 再执行 dependency:go-offline，但那会让 Dockerfile 与模块清单强耦合。
#
# jar 命名（2026-11-05 修）：co-bootstrap/pom.xml 声明
# <finalName>campus-overflow</finalName>，Spring Boot repackage 后产物是
# campus-overflow.jar，原包被改名为 campus-overflow.jar.original。
# 旧写法 `COPY --from=build /src/co-bootstrap/target/co-bootstrap-*.jar app.jar`
# 一个文件都匹配不到：镜像能 build 成功、uid / java -version 冒烟也全过，
# 但真正启动时才报 `Error: Unable to access jarfile app.jar`。
# 现在构建阶段显式 cp 到固定路径并 test -f 硬校验：finalName 再变化会在
# 构建期直接失败，而不是静默产出跑不起来的镜像。见 Issue #35。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml ./pom.xml
COPY backend/ ./
RUN mvn -B -DskipTests clean package \
    && test -f co-bootstrap/target/campus-overflow.jar \
    && cp co-bootstrap/target/campus-overflow.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
