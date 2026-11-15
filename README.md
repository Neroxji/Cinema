# 电影票在线购票系统（cinema）

面向对象课程设计 · 后端 Spring Boot 3.3.5（JDK 17）· 双数据库轨制：开发演示用 H2 文件库，正式档/容器化部署用 MySQL 8。

## 端口与凭据约定（task-01C 环境契约）

| 项 | 值 |
| --- | --- |
| 应用端口 | `8080` |
| MySQL 端口 | `3306`（host 与容器一致） |
| MySQL 容器 | `cinema-mysql`（独立常驻） / compose 服务 `mysql` |
| MySQL 凭据 | root / `cinema2026` |
| 业务库名 | `cinema` |
| 字符集/时区 | 服务器级 `utf8mb4` + `utf8mb4_unicode_ci`，`TZ=Asia/Shanghai` |
| 数据卷 | `cinema-mysql-data`（独立容器与 compose 共用同一命名卷） |
| 镜像 | 应用 `backend/Dockerfile` 多阶段构建（Maven 构建 → JRE 17 运行）；数据库 `mysql:8` |

## 演示账号（DataSeeder 启动时幂等写入）

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `audience` | `audience123` | 观众 |
| `scheduler` | `scheduler123` | 排片员 |
| `checker` | `checker123` | 检票员 |

种子数据：3 影片 / 3 影厅 / 6 场次（含 1 个满座场）/ 292 座位（已售 50）/ 1 笔已出票订单（取票码 `86420195`）。

---

## 路线 A：有 Docker —— compose 一键起 2 容器（app + mysql）

前置：Docker Desktop 已就绪（engine ≥ 20.10，compose v2）。

```bash
# 1)（仅当独立常驻容器 cinema-mysql 正在运行时需要）让出 3306 端口
docker stop cinema-mysql

# 2) 一键起 2 容器；首次需要构建应用镜像，加 --build（Maven 依赖下载约几分钟，
#    BuildKit cache mount 已缓存 ~/.m2，二次构建很快）
docker compose up -d            # 首次：docker compose up -d --build

# 3) 等待就绪（mysql 先 healthy，app 随后 healthy / Up）
docker compose ps

# 4) 验证：浏览器访问 http://localhost:8080 ，或查看启动日志
docker compose logs app | grep "Started CinemaApplication"

# 停止（容器移除，数据卷 cinema-mysql-data 保留，下次 up 数据仍在）
docker compose down
```

说明：

- compose 项目名固定为 `cinema`，容器名为 `cinema-mysql-1` / `cinema-app-1`；app 通过服务名 `mysql:3306` 连库（容器间不可用 localhost），profile 为 `mysql`，启动时自动重放 `schema.sql`（全表 IF NOT EXISTS 幂等）并由 DataSeeder 播种，重复 up 不产生重复数据。
- compose 的 mysql 与独立常驻容器 `cinema-mysql` 共用同一数据卷 `cinema-mysql-data`，两套路线看到同一份数据。
- 应用镜像多阶段构建：`backend/Dockerfile`（阶段 1 `maven:3.9-eclipse-temurin-17` 编译，阶段 2 `eclipse-temurin:17-jre` 运行）；`.dockerignore` 已排除 `target/`、`data/`、`logs/` 等本地垃圾。镜像源拉取失败时可把 FROM 镜像加国内前缀（如 `docker.m.daocloud.io/library/...`）。

## 路线 B：无 Docker —— JDK17 + Maven + java -jar（H2 文件档）

前置：JDK 17、Maven 3.9（或任意能跑 `mvn` 的环境）。

```bash
cd backend
mvn package -DskipTests          # 打 fat-jar（也可直接 mvn package 连测试一起跑）
java -jar target/cinema-backend-0.1.0-SNAPSHOT.jar
```

- 默认即演示档：H2 文件库落在 `backend/data/booking`（`*.db` 已被 .gitignore 忽略），启动自动建表 + 播种，重复启动幂等。
- 验证：浏览器访问 `http://localhost:8080`，或等日志出现 `Started CinemaApplication`。
- 若想连外部 MySQL（含上面的容器），加 `--spring.profiles.active=mysql`（默认连 `localhost:3306`，凭据见 `backend/src/main/resources/application-mysql.yml`）。

## 测试复跑（对真 MySQL 执行种子断言）

前置：常驻容器运行中——`docker start cinema-mysql`。

```bash
cd backend
mvn test -Dtest=SeedDataAssertMySQLTest -Dcinema.mysql.tests=true   # 仅 MySQL 档 8 用例
mvn test -Dcinema.mysql.tests=true                                  # H2 内存档 + MySQL 档整套复跑
```

- 默认 `mvn test` 只跑 H2 内存档（`SeedDataAssertTest`），不依赖 Docker；`SeedDataAssertMySQLTest` 由开关 `-Dcinema.mysql.tests=true` 显式启用，对容器 MySQL 执行同一套 8 用例。
- 幂等安全：`schema.sql` 全表 IF NOT EXISTS + DataSeeder 幂等 + 测试事务回滚，持久库重复复跑不污染数据。

## 数据重置

| 轨制 | 方法 |
| --- | --- |
| H2 文件档（路线 B） | 停应用 → 删除 `backend/data/*.db` → 重启（重新建表播种） |
| MySQL 容器（路线 A/独立容器） | `docker compose down`（或 `docker rm -f cinema-mysql`）→ `docker volume rm cinema-mysql-data` → 重新 `docker compose up -d --build` 或重建独立容器，启动后自动重新建表播种 |

## 常见问题（FAQ）

1. **`mvn spring-boot:run` 在本仓库根目录报错/行为异常**：仓库路径含中文（`D:\大三上实验\面向对象`），spring-boot:run 的工作目录/编码处理易出问题——**请改用 `mvn package` + `java -jar`**（见路线 B），jar 方式从 `backend/` 目录启动无此问题。
2. **3306 端口被占用**：先停掉另一套 MySQL——`docker stop cinema-mysql`（独立常驻容器）再 `docker compose up -d`；反向同理。
3. **基础镜像拉取失败/超时**：把 Dockerfile/compose 中的镜像加国内前缀重试，如 `docker.m.daocloud.io/library/mysql:8`；不要改 Docker daemon 配置。
4. **Windows 控制台中文乱码**：`java -jar` 时加 `-Dfile.encoding=UTF-8`（Dockerfile 入口已内置）；Maven 侧工程编码已固定 UTF-8。
5. **数据"丢了"**：路线 A 数据在命名卷 `cinema-mysql-data`，`docker compose down` 不删卷；误删卷则按上文"数据重置"重建即可自动再播种。
