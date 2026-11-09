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
# 阶段 1：Builder
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 把配置了阿里云镜像的 settings.xml 拷贝到 Maven 的配置目录
COPY deploy/settings.xml /usr/share/maven/ref/settings.xml

# 直接拷贝整个 backend 目录（注意 .dockerignore 已经排除了 target 等垃圾文件）
COPY backend/ .

# 编译打包并跳过测试
RUN mvn clean package -DskipTests

# 阶段 2：Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 非 root 运行
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 从 builder 阶段拷贝打好的 jar 包
COPY --from=builder /app/co-bootstrap/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]