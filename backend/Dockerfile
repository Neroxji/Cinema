# =====================================================================
# 电影票在线购票系统后端 · 多阶段镜像（task-01C 交付物 4）
#   阶段 1 build    ：Maven 3.9 + JDK 17 编译打 fat-jar；
#                     BuildKit cache mount 缓存 /root/.m2（重复构建不重下依赖）；
#                     容器内 Maven 走阿里云镜像源（docker/maven-settings.xml，
#                     与宿主 ~/.m2/settings.xml 同源）。
#   阶段 2 runtime  ：eclipse-temurin 17-jre 运行 fat-jar。
# 若 Docker Hub 拉取失败，可给 FROM 镜像加国内前缀（禁止改 daemon 配置），如：
#   docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17
#   docker.m.daocloud.io/library/eclipse-temurin:17-jre
# =====================================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 容器内 Maven 全局配置替换为阿里云 central 镜像（构建期专用，不参与运行）
COPY docker/maven-settings.xml /usr/share/maven/conf/settings.xml
# 先 pom 后 src：pom 未变时可命中层缓存
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/target/cinema-backend-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
# 默认即演示档（H2 文件库，写 /app/data）；compose 部署经 SPRING_PROFILES_ACTIVE=mysql
# 与 SPRING_DATASOURCE_URL 环境变量把连接指向 compose 服务名 mysql:3306
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
