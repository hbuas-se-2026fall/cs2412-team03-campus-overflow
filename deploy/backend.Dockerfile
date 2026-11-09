# 多阶段构建：Maven 编译 → jdeps/jlink 定制 JRE → 极小运行时（非 root）。
#
# 为什么换成 jlink（Issue #35）：
#   原运行时用 eclipse-temurin:21-jre-alpine，完整 JRE 约 200MB，再加 76MB 的
#   Spring Boot fat jar，实测镜像 275MB，远超 #35「<200MB」门槛。
#   现改为「jdeps 静态分析 + 反射兜底清单」生成只含所需 JPMS 模块的 JRE
#   （本机实测 52MB / 27 模块），运行时用 alpine + 定制 JRE。
#
# 两个必须守住的约束（踩过坑，别改回去）：
#   1. **musl 同源**：jlink 阶段用 eclipse-temurin:21-jdk-alpine（musl），运行时
#      用 alpine（musl）。若从 glibc 的 JDK 生成 JRE 再放进 alpine，java 二进制
#      会因找不到 glibc 动态库直接启动失败——镜像能 build 成功、冒烟也可能
#      只查文件，直到 compose 真启动才暴露。
#   2. **反射兜底**：jdeps 只看静态字节码，看不到 Spring / Hibernate / JDBC 通过
#      Class.forName、ServiceLoader 加载的类。EXTRA 显式补齐 java.sql / java.naming /
#      java.management / jdk.crypto.ec 等模块，缺一个就是运行时 NoClassDefFoundError。
#
# jar 命名（2026-11-05 修）：co-bootstrap/pom.xml 声明
# <finalName>campus-overflow</finalName>，Spring Boot repackage 后产物是
# campus-overflow.jar。旧写法 `COPY ... co-bootstrap-*.jar app.jar` 匹配不到任何
# 文件：镜像能 build 成功、uid / java -version 冒烟全过，真正启动才报
# `Unable to access jarfile app.jar`。现在构建期 cp 到固定路径并 test -f 硬校验。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 阿里云 Maven 镜像配置（#82 引入，rebase 时保留，勿丢）
COPY deploy/settings.xml /usr/share/maven/ref/settings.xml

COPY backend/pom.xml ./pom.xml
COPY backend/ ./
RUN mvn -B -DskipTests clean package \
    && test -f co-bootstrap/target/campus-overflow.jar \
    && cp co-bootstrap/target/campus-overflow.jar /app.jar

# ---- 定制 JRE：与运行时同为 musl ----
FROM eclipse-temurin:21-jdk-alpine AS jre-build
WORKDIR /work
COPY --from=build /app.jar /app.jar
RUN jar xf /app.jar BOOT-INF/lib BOOT-INF/classes \
    && MODS="$(jdeps --ignore-missing-deps --print-module-deps --multi-release 21 -q \
                --class-path "$(find BOOT-INF/lib -name '*.jar' | paste -sd: -):BOOT-INF/classes" \
                BOOT-INF/classes 2>/dev/null || true)" \
    && EXTRA="java.base,java.logging,java.sql,java.sql.rowset,java.naming,java.management,java.instrument,java.xml,java.xml.crypto,java.desktop,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.transaction.xa,java.compiler,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.management,jdk.jfr,jdk.net,jdk.zipfs,jdk.security.auth" \
    && ALL="$(printf '%s' "${MODS:+${MODS},}${EXTRA}" | tr ',' '\n' | sort -u | paste -sd, -)" \
    && echo "jdeps 静态模块: ${MODS:-（空，由兜底清单覆盖）}" \
    && echo "反射兜底模块: ${EXTRA}" \
    && jlink --add-modules "${ALL}" \
             --strip-debug --no-man-pages --no-header-files --compress zip-6 \
             --output /minijre \
    && test -x /minijre/bin/java \
    && /minijre/bin/java --list-modules | wc -l

# ---- 运行时：只有 alpine + 定制 JRE + fat jar ----
FROM alpine:3.20
RUN addgroup -S app && adduser -S app -G app \
    && apk add --no-cache wget freetype fontconfig
WORKDIR /app
COPY --from=jre-build /minijre /opt/jre
COPY --from=build /app.jar /app/app.jar
USER app
ENV PATH="/opt/jre/bin:$PATH" \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
