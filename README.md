# 写字台 (xiezitai)

SEO / AI 友好的自托管博客系统。官网：<https://xiezitai.cn>

- 技术栈：Java 17 · Spring Boot 3 · MySQL · JPA · JWT · Thymeleaf · ByteMD
- 仓库：<https://github.com/zhongdaiqi/xiezitai>
- 镜像：<https://hub.docker.com/r/zhongdaiqi/xiezitai>（GitHub Actions 自动构建，支持 amd64 / arm64）

## 部署方式怎么选

| 场景 | 用哪个 | 命令 |
| --- | --- | --- |
| 服务器部署（数据库也一起起，推荐） | 官方镜像 + 官方 MySQL 容器 | `docker compose -f docker-compose.hub.yml up -d` |
| **小机器：1 核 1G（甲骨文免费实例 / 低配 VPS）** | 官方镜像 + 本地 **MariaDB 11.4**（已按 1G 调优） | `docker compose -f docker-compose.mariadb.yml up -d` |
| **数据库在云端 / 已有 MySQL** | 官方镜像 + 你自己的库 | `docker compose -f docker-compose.external-db.yml up -d` |
| 个人 / NAS / 内网（连数据库都不要） | 官方镜像单容器（lite，内置 H2 文件库） | `docker compose -f docker-compose.lite.yml up -d` |
| 自己改代码 | 源码 compose（容器内编译） | `docker compose up -d --build` |
| 无 Docker | JAR 直跑（`MYSQL_*` 环境变量指外部库） | `java -jar target/xiezitai.jar` |

四份 compose 共用同一套环境变量，可在仓库根建一个 `.env` 集中填写（模板见 `.env.example`，
`.env` 已被 git 忽略）：`cp .env.example .env` 后按注释改成自己的值即可。

访问 `http://localhost:8080`，后台：`http://localhost:8080/admin.html`

默认管理员：`xiezitai / xiexiexie`（登录后请立即改密码并开启 TOTP）。

### 第一次启动会写入示例内容

首次启动、且数据库为空时，会自动写入一批示例内容，免得打开首页只看到一句「还没有发布的文章」：

| 内容 | 说明 |
| --- | --- |
| 4 篇文章 | 「欢迎来到写字台」「Markdown 写作速查」「自托管博客」「部署与维护清单」，覆盖表格 / 任务清单 / 代码块 / 引用等排版 |
| 2 个自定义页面 | 关于、友链，**顶部导航里直接可点** |
| 5 条评论 | 4 条已通过（含两级回复与「回复 @某人」）、1 条待审核，可去后台「评论」里体验审核流程 |

三个安全阀：只在文章表和页面表**都为空**时写入；写完打标记 `demo.seeded`，把示例删光后重启也不会再冒出来；
不想要示例内容就设 `XIEZITAI_SEED_DEMO=false`（或 yml 里 `xiezitai.seed-demo: false`）。

### 方式一：官方镜像 + MySQL（推荐服务器用）

不用装 JDK、不用等编译，直接拉 Actions 构建好的双架构镜像：

```bash
export MYSQL_PASSWORD='你的数据库密码'
export XIEZITAI_JWT_SECRET='至少32位随机字符串'
docker compose -f docker-compose.hub.yml up -d
```

升级：`docker compose -f docker-compose.hub.yml pull && docker compose -f docker-compose.hub.yml up -d`

> ⚠️ **一定要设 `XIEZITAI_JWT_SECRET`**：不设就会用镜像内置的默认值，任何人都能伪造登录令牌。
> 这些变量也可以写在仓库根的 `.env` 里（模板见 `.env.example`），批量部署更省事。

### 方式二：连接云端 / 外部 MySQL（数据库不在 Docker 里）

适合：数据库已经存在——云厂商托管 MySQL（阿里云 RDS、腾讯云 CDB、华为云 RDS、AWS RDS、Azure Database for MySQL…）、
公司内网的自建库、宿主机上已装好的 MySQL。这份编排**只起一个应用容器**，不再顺带起 `mysql:8.4`。
配置文件是 `docker-compose.external-db.yml`。

**第 1 步 · 数据库侧准备**（云控制台里建库，或让 DBA 执行）

```sql
-- 库必须是 utf8mb4，否则中文、emoji 会出错
CREATE DATABASE xiezitai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'xiezitai'@'%' IDENTIFIED BY '你的强密码';
-- 首次启动要自动建表，需要 CREATE / ALTER / INDEX 权限
GRANT ALL PRIVILEGES ON xiezitai.* TO 'xiezitai'@'%';
FLUSH PRIVILEGES;
```

再把**这台服务器的公网出口 IP** 加进云库白名单（同 VPC 访问则放内网段）。
出口 IP 可用 `curl -s ifconfig.me` 查。云库地址优先选控制台里的**内网地址**：更快，也不经公网。

**第 2 步 · 填配置**

```bash
cp .env.example .env
vi .env          # 最少填这三项：MYSQL_HOST、MYSQL_PASSWORD、XIEZITAI_JWT_SECRET
```

```ini
# 数据库（必填）
MYSQL_HOST=rm-xxxxxxxx.mysql.rds.aliyuncs.com
MYSQL_PORT=3306
MYSQL_DB=xiezitai
MYSQL_USER=xiezitai
MYSQL_PASSWORD=你的强密码
# 站点（必填）
XIEZITAI_JWT_SECRET=<openssl rand -hex 32 的输出>
XIEZITAI_SITE_URL=https://你的域名
APP_PORT=8080
```

> 这三项在编排里用的是「必填校验」写法：没填会直接报错退出并打印提示语，
> 不会拿内置的弱口令/默认密钥把服务起起来。

**第 3 步 · 起服务**

```bash
docker compose -f docker-compose.external-db.yml up -d
docker compose -f docker-compose.external-db.yml logs -f app      # 出现 Started XiezitaiApplication 即成功
```

访问 `http://服务器IP:8080`，后台 `/admin.html`，默认管理员 `xiezitai / xiexiexie`（**登录后立即改密码**）。
首次启动会自动在云库里建表，并写入 4 篇文章 + 关于/友链页面 + 几条评论作示例。

升级到新版本 / 回滚到固定版本：

```bash
docker compose -f docker-compose.external-db.yml pull && docker compose -f docker-compose.external-db.yml up -d
# 回滚：先在 .env 里指定 XIEZITAI_IMAGE=zhongdaiqi/xiezitai:1.2.3（或 :sha-abc1234），再执行上面两条
```

**连不上的话，对照排查**

| 现象 | 原因与解决 |
| --- | --- |
| `Communications link failure` / `Connection timed out` | 白名单没放行该出口 IP，或填了内网地址但机器不在同一 VPC |
| `Access denied for user 'xiezitai'@'1.2.3.4'` | 账号或密码错；或账号没授权给这个来源（要 `'xiezitai'@'%'`） |
| `Public Key Retrieval is not allowed` | 加 `allowPublicKeyRetrieval=true`（默认 URL 已带） |
| `Unknown character set` / 中文乱码 | 云的库不是 utf8mb4，或 `characterEncoding` 被改掉了 |
| `The server time zone value ... is unrecognized` | URL 加 `serverTimezone=Asia/Shanghai`（默认 URL 已带） |
| `SSL connection required` / TLS 握手失败 | 用 `SPRING_DATASOURCE_URL` 整条覆盖并把 `sslMode` 改成 `REQUIRED`，示例见 `docker-compose.external-db.yml` 末尾 |
| `Too many connections` / 连接被打满 | 下调 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`（见 `.env.example` 的 `DB_POOL_SIZE`） |

**备份**：数据库的备份与恢复交给云厂商的自动备份即可；本机只有上传的媒体文件，把数据卷打包带走就行：

```bash
VOL=$(docker volume ls --format '{{.Name}}' | grep 'xiezitai_data' | head -1)   # compose 会给卷加项目名前缀
docker compose -f docker-compose.external-db.yml stop
docker run --rm -v "$VOL":/data -v "$PWD":/backup alpine tar czf /backup/xiezitai-media.tgz -C /data .
docker compose -f docker-compose.external-db.yml start
```

> 想在生产再稳一点：把 `ports` 改成 `"127.0.0.1:8080:8080"`，只让本机 Nginx / Caddy 反代进来，
> 应用就不必直接暴露在公网。

### 方式三：单容器精简模式（lite，无需 MySQL）

只想跑**一个**容器，数据用内置 H2 文件库落在卷里：

```bash
export XIEZITAI_JWT_SECRET='至少32位随机字符串'
docker compose -f docker-compose.lite.yml up -d
```

> ⚠️ 同样要设 `XIEZITAI_JWT_SECRET`，不设会用镜像内置默认值（可被伪造令牌）。

数据（H2 库 + 上传的媒体）都在命名卷 `xiezitai_data` 的 `/app/data` 下：

```bash
# 备份：停容器后把整个数据目录打包
docker compose -f docker-compose.lite.yml stop
docker run --rm -v xiezitai_data:/data -v "$PWD":/backup alpine \
  tar czf /backup/xiezitai-backup.tgz -C /data .
```

> 什么时候该换回 MySQL：需要多实例横向扩展、单库写入并发很高、或想用云数据库托管。
> 届时改用方式一，文章正文本来就是 Markdown 原文，迁移成本很低。

### 方式四：低配小机器（1 核 1G，MariaDB 11.4）

适合**甲骨文云免费实例**（E2.1.Micro 1 核 1G、A1 最低配）、1 核 1G 的 VPS、树莓派这类内存吃紧的机器：
数据库想跑在自己机器上（不买云数据库），但又不想用 lite 单容器模式。配置文件是 `docker-compose.mariadb.yml`。

**为什么低配机选 MariaDB 而不是 MySQL 8**

- `performance_schema` 在 MariaDB **默认关闭**；MySQL 8 默认开启，光这一项常驻 100~200MB —— 在 1G 机器上等于砍掉五分之一内存
- mysqld 进程基础占用更小（后台线程与内存池少得多）
- 11.4 是 LTS，官方镜像同时提供 amd64 与 arm64（甲骨文 A1 实例是 arm64）
- 连接协议与 MySQL 完全兼容，**应用侧一行代码都不用改**（JDBC 驱动仍是 Connector/J）

```bash
cp .env.example .env      # 必填 3 项：MYSQL_ROOT_PASSWORD、MYSQL_PASSWORD、XIEZITAI_JWT_SECRET
docker compose -f docker-compose.mariadb.yml up -d
docker compose -f docker-compose.mariadb.yml logs -f app      # 见 Started XiezitaiApplication 即成功
docker stats --no-stream                                      # 看两个容器真实占用
```

> ⚠️ **改过 `.env` 或升级版本后，一定要 pull + 重建**：Docker 不会自动更新本地镜像，只跑 `up -d`、`restart`
> 用的还是旧镜像与旧环境变量。典型症状就是「明明已经修好了，重启后还报一模一样的错」。正确姿势：
> `docker compose -f docker-compose.mariadb.yml pull && docker compose -f docker-compose.mariadb.yml up -d --force-recreate`

**内存预算（实机实测，非估算）**

| 组成 | 上限 | 空闲 | 压测后 |
| --- | --- | --- | --- |
| 系统 + sshd + dockerd | — | ~200MB | ~200MB |
| `db`（MariaDB 11.4） | 320m | 52.7MB | 53.0MB |
| `app`（JVM） | 512m | 256.8MB | 280.3MB |
| **合计** | 832m（天花板） | **~510MB** | **~540MB** |

压测 = 连打 300 次首页 + 60 次 API，全程 `OOMKilled=false`、重启 0 次。资源是惰性分配的，
两个 `mem_limit` 之和只是天花板、不是预订量。MariaDB 空闲值偏低是因为 InnoDB buffer pool 按需分配，
数据涨到几百 MB 后会稳定在 150~200MB，仍在 320m 之内。

**调了哪些参数**（完整逐项注释见 `docker-compose.mariadb.yml`，每项都写了默认值与理由）

| 位置 | 参数 | 默认 → 本编排 | 为什么 |
| --- | --- | --- | --- |
| MariaDB | `--innodb-buffer-pool-size` | 128M → **96M** | 整个库才几十 MB |
| MariaDB | `--innodb-buffer-pool-chunk-size` | 128M → **32M** | 默认比 pool 还大，不调会被自动降级 |
| MariaDB | `--innodb-buffer-pool-instances` | 8 → **1** | 多实例各有固定开销 |
| MariaDB | `--aria-pagecache-buffer-size` | 128M → **32M** | 系统表用的 Aria 引擎用不到这么大 |
| MariaDB | `--key-buffer-size` | 128M → **8M** | 本项目没有 MyISAM 表 |
| MariaDB | `--max-connections` | 151 → **32** | 每条连接都预留排序/网络缓冲 |
| MariaDB | `--thread-cache-size` | 151 → **8** | 别一直养一堆空闲线程 |
| MariaDB | `--table-open-cache` | 2000 → **64** | 小库不需要 |
| MariaDB | `--performance-schema` | 默认 OFF → **显式 OFF** | 防止别处配置把它重新打开 |
| MariaDB | `--innodb-flush-method` | fsync → **O_DIRECT** | 甲骨文免费机块存储 IOPS 低，避免同一份数据缓存两次 |
| MariaDB | `mem_limit` | 无 → **320m** | 不设会按宿主 1G 来算，能把自己撑爆并连累系统 |
| JVM | `-Xmx` | 按宿主自动 → **320m** | 1G 机器上自动算出来的堆是灾难 |
| JVM | `-XX:MaxMetaspaceSize` | 无限 → **112m** | Spring Boot + JPA + Thymeleaf 约 90~110M |
| JVM | `-XX:ReservedCodeCacheSize` | 240M → **48m** | 默认是给多核大内存机器的 |
| JVM | `-XX:+UseSerialGC` | G1 → **SerialGC** | 单核场景没有 GC 线程池开销，反而更快 |
| JVM | `-XX:ActiveProcessorCount=1` | 按宿主核数 → **1** | 否则 JVM 可能按宿主机核数放大默认线程数 |
| JVM | `-Xss512k` | 1m → **512k** | Tomcat 线程池 + JVM 内部线程，积少成多 |
| Tomcat | `SERVER_TOMCAT_THREADS_MAX` | 200 → **20** | 每个线程约 0.5M 栈 |
| 连接池 | Hikari `maximum-pool-size` | 10 → **4** | 1 核机器 4 条足够 |

**宿主机侧还要做的事**（容器里改不到的）

1. **加 swap** —— 1G 机器最有效的一步，不加 swap 遇到瞬时高峰必被 OOM Killer 干掉：

   ```bash
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile
   sudo swapon /swapfile && echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   sudo sysctl -w vm.swappiness=10          # 平时别往下换，只在内存真紧时才用
   ```

2. **甲骨文云特有的坑：安全列表放行 ≠ 机器放行**。VCN 安全列表加了 80/443 之后，
   实例内的 iptables 还会再拦一次（Ubuntu 云镜像自带规则）：

   ```bash
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save           # 持久化，重启不丢
   ```

   只有 22 端口能通、浏览器打不开，八成就是这一步没做。

3. **内核参数**（1G 内存 + 小站点，重点是别让内核太激进地回收）：

   ```bash
   sudo tee /etc/sysctl.d/99-xiezitai.conf <<'EOF'
   vm.swappiness=10
   vm.overcommit_memory=1        # 允许适度超额分配，避免 JVM 预留虚拟内存时失败
   net.core.somaxconn=512        # Tomcat 的 accept-count 受它限制，默认 128 偏小
   EOF
   sudo sysctl --system
   ```

4. **还嫌不够省**：换成 lite 单容器模式，省掉整个 MariaDB 的 ~50MB（见方式三）。

> 日志轮转（json-file 10m×3）本编排已配死 —— 低配机上默认不轮转的日志能把磁盘写满。
> 不映射 3306 到宿主机，数据库不暴露公网；要连进去调试用 `docker compose -f docker-compose.mariadb.yml exec db mariadb -uroot -p`。

**运维 / 排错**：备份、OOM 排查（`docker inspect` 看 `OOMKilled`）、升级、以及一张常见报错对照表，
都写在 `docker-compose.mariadb.yml` 文件末尾的「附 2」「附 3」「附 4」里。

### 方式五：源码构建（本地改代码用）

```bash
docker compose up -d --build
```

## 官方镜像标签与裸 docker run

不想用 compose，直接单命令起（MySQL 部署在别处）：

```bash
docker run -d --name xiezitai -p 8080:8080 \
  -e MYSQL_HOST=你的MySQL地址 -e MYSQL_DB=xiezitai \
  -e MYSQL_USER=xiezitai -e MYSQL_PASSWORD=你的数据库密码 \
  -e XIEZITAI_JWT_SECRET=一串至少32位的随机字符串 \
  -e XIEZITAI_SITE_URL=https://你的域名 \
  -v xiezitai_data:/app/data \
  --restart unless-stopped \
  zhongdaiqi/xiezitai:latest
```

连数据库都不想要，只要加一个环境变量就能切到内置 H2 文件库（数据仍在卷里）：

```bash
docker run -d --name xiezitai -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=lite \
  -e XIEZITAI_JWT_SECRET=一串至少32位的随机字符串 \
  -v xiezitai_data:/app/data \
  --restart unless-stopped \
  zhongdaiqi/xiezitai:latest
```

可用标签：

| 标签 | 说明 |
| --- | --- |
| `latest` | **最新发布的版本**，只在打 `v*.*.*` tag 时更新 |
| `main` | 默认分支的最新构建，滚动更新，可能包含未发布的改动 |
| `1.2.3` / `1.2` | 打 `v1.2.3` tag 时自动生成 |
| `sha-xxxxxxx` | 按提交哈希固化的版本，适合回滚 |

想跑最新代码用 `main`，想要稳定就用 `latest`；对升级时机有要求的话直接钉版本号
（如 `zhongdaiqi/xiezitai:1.0.0`），什么时候升级完全由自己决定。

镜像里的 JAR **不包含**任何 profile 写死配置：数据库、JWT 密钥、站点地址全部通过
`MYSQL_*` / `XIEZITAI_*` 环境变量注入（见 `application.yml`），`/app/data` 建议挂卷持久化。

### 自动构建流程

`.github/workflows/docker-publish.yml`：

- push 到 `main` → 构建并推送 `main` / `sha-*`（滚动最新构建，**不会动 `latest`**）
- push `v*.*.*` tag → 构建并推送 `1.2.3` / `1.2` / `sha-*`，并把 `latest` 指向该版本
- Pull Request → 只构建校验，不推送
- 也可在 Actions 页面手动触发

构建方式是**先编译 JAR、再装镜像**：用原生 amd64 速度跑一次 Maven，然后把同一个 JAR
分别装进 amd64 和 arm64 的基础镜像，避免在 QEMU 模拟的 arm64 里跑 Maven（慢 5~10 倍）。

需要在仓库 Settings → Secrets and variables → Actions 里配置两个 Secret：

| Secret | 值 |
| --- | --- |
| `DOCKERHUB_USERNAME` | Docker Hub 用户名 |
| `DOCKERHUB_TOKEN` | Docker Hub Access Token（不是登录密码） |

### 自动部署流程

`.github/workflows/deploy.yml`：镜像构建成功后，自动 SSH 到服务器执行部署脚本。

| 触发方式 | 说明 |
| --- | --- |
| 上游「Build and Push Docker Image」成功结束 | 自动部署（PR 构建成功不部署） |
| Actions 页面手动 Run workflow | 强制部署一次 |

为什么挂在**构建完成之后**而不是也写 `on: push: [main]`：push 之后镜像还要编译 + 双平台构建
好几分钟才推上 Hub，两边同时起步的话服务器 pull 到的是**上一版镜像**，而且日志全绿、很难察觉。

需要在 Secrets 里配置（和构建用的一组分开）：

| Secret | 值 |
| --- | --- |
| `SSH_HOST` | 服务器地址 |
| `SSH_USER` | 登录用户 |
| `SSH_PORT` | SSH 端口 |
| `SSH_KEY` | 部署私钥全文（含 BEGIN/END 行） |

服务器侧 `~/.ssh/authorized_keys` 里这把公钥用 `command=` 强制指向部署脚本，
客户端发过去的命令会被忽略，所以 workflow 里的 `script` 只是占位；
**要改部署动作请改服务器上的那个脚本**。

> ⚠️ **注意镜像标签要对得上**：部署脚本若按 `docker-compose.mariadb.yml` 的默认值拉
> `:latest`，而 `latest` 只在打 `v*.*.*` tag 时才更新（见上节标签语义），
> 那么每次推 `main` 的自动部署都会**拉到同一个旧镜像、整条链空转**。
> 想做到「推 main 就上线」：在服务器 `.env` 里加 `XIEZITAI_IMAGE=zhongdaiqi/xiezitai:main`。
> 想做到「只有发版才上线」：把部署的触发条件收紧到 tag。

可选：在仓库 `Variables` 里配 `DEPLOY_HEALTHCHECK_URL`（如 `https://xiezitai.cn/`），
部署后会探测一次并只告警不判失败（境外 runner 探测国内域名可能超时）。

## 服务器部署（非 Docker）

1. 准备 MySQL 8，建库 `xiezitai`（utf8mb4）
2. `mvn package -DskipTests`
3. `java -jar target/xiezitai.jar`（可用环境变量覆盖 `MYSQL_*`、`XIEZITAI_*` 配置）

## 功能一览

| 模块     | 说明                                                                               |
| ------ | -------------------------------------------------------------------------------- |
| 文章     | Markdown（ByteMD 编辑器）、slug、SEO 字段、浏览计数；**插入媒体**：媒体库弹窗上传/挑选，图片入 Markdown，视频入 `<video>` 标签；支持粘贴截图、拖拽图片/视频到编辑器自动入库；媒体输出支持 HTTP Range（视频可拖进度条）；**代码块**：前台文章页与后台编辑器实时预览都自动语法高亮（标注语言才着色，46 种语言）+ 语言标签 + 一键复制  |
| 页面     | 自定义页面（关于、友链等）                                                                    |
| 评论     | **仅限登录用户**评论（禁止匿名），作者名取自登录账号不可伪造；提交后待管理员审核，通过后公开展示 + 文章页内嵌登录/注册弹窗                                                                    |
| 用户     | **注册需审核**（新注册为「待审核」，站长通过后才能登录；驳回可填原因，用户登录时可见）/ 角色（ADMIN/USER）/ 按用户限制上传类型 |
| 文件     | 上传（默认仅图片/视频）、媒体经 Spring 输出并记录访问日志                                                |
| 安全     | TOTP 两步验证（扫码/密钥绑定）；密码错误 3 次锁 5 分钟、5 次锁 10 分钟、10 次锁 1 小时；全量请求日志；文件魔数扫描 + 孤立文件检测；防篡改基线校验    |
| 机器人    | 企业微信 webhook 通知（登录/文章/访问/注册/评论/上传），可逐项开关                                         |
| AI     | OpenAI 兼容接口：润色纠错、摘要、封面图（公众号 900×383）、请求日志风险分析                                    |
| 分发     | **把文章一键发到关联的 WordPress 站点 / 博客园账号**（可多选）：正文里的站内媒体自动补成绝对地址；可选**原文分发**或**转载分发**（文末附首发链接）；正文可按 Markdown 原文发或转成 HTML 发（发博客园时自动带 `[Markdown]` 分类，否则代码块/表格会被当 HTML 原样贴出）；**已分发过的目标会被记住**，下次可选「更新之前分发的文章」或「分发一个新文章」，文章列表行上用「已分发 · 站点名」徽标标出 |
| 开放 API | 对外接口（Header `X-API-Token`，后台「设置」页查看）：`POST /api/v1/publish` 发布、`GET /api/v1/articles` 列表、`GET /api/v1/articles/{id}` 单篇 |
| MCP    | `POST /api/v1/mcp`，JSON-RPC 2.0，工具：publish_article / list_articles / get_article |
| 写字台互导 | **关联多个写字台账号**（填接口地址 + 账号 + 对接密钥）：浏览对方站点文章 → **导入单篇** 或 **整站导入**（进度条 + 逐条日志）；正文本身就是 Markdown 原文，导入不需要任何格式转换；**属于对方站点的图片/附件下载落盘**到本站媒体库，**第三方图床保持外链**；本地已存在可选「跳过」或用对方版本更新，发布时间可选沿用原时间或当前时间；密钥只落库、接口不回显、不入 git |
| 搜索     | **首页站内搜索**（`/?q=`）：标题 / 摘要 / 正文 / 标签四处 like 命中，**只搜已发布**（草稿不露头）；纯 GET 表单 + 服务端渲染，链接可分享、爬虫可抓；关键词一路带进翻页 / canonical / rel prev·next（点下一页不丢条件）；搜索结果页自动 `noindex`（这类低质重复页不收进索引） |
| 后台操作  | 列表行内操作统一为图标按钮（分发 / 编辑 / 删除 / 评论通过·拒绝），带中文悬浮提示与 `aria-label` 无障碍名称                                 |
| SEO    | 服务端渲染、robots.txt、sitemap.xml、OG 标签                                               |
| 前端资源 | ByteMD / github-markdown-css / Mermaid / highlight.js **全部本地内置**（`static/vendor/`），不依赖任何外部 CDN，可离线/内网部署 |

### 注册审核怎么走

访客注册后不是立刻可用，而是进待审队列 —— 防的是机器人灌水注册和蹭上传口的账号：

1. 文章页「注册」提交 → 账号状态为 `PENDING`，此时登录返回 403 并提示「等待管理员审核」
2. 站长在后台「用户」页处理：待审核的行会**黄色高亮并排在最前**，点「通过」或「驳回」（驳回可填原因）
3. 通过后才能登录；被驳回的用户登录时会看到你填的原因
4. 管理员账号不走这道闸门，也不允许被驳回（避免一次误操作把自己锁在门外）

> 审核状态对所有入口生效：一经驳回/停用，此前签发的 JWT 和 API Token **立即失效**，不必等它自然过期。
> 从旧版本升级不需要做数据迁移 —— 新增列的默认值是 `APPROVED`，历史用户不会被挡在门外。

## AI 大模型（默认接入魔搭 ModelScope）

首次启动会把以下默认 AI 配置写入 `sys_configs`（已存在则不覆盖，后台「设置」页可随时改成任意 OpenAI 兼容接口）：

| 配置项        | 默认值                                        |
| --------- | ------------------------------------------ |
| 接口地址      | `https://api-inference.modelscope.cn/v1`   |
| 对话模型      | `Qwen/Qwen3.8-27B`                         |
| 文生图模型     | `Qwen/Qwen-Image-2.1`                      |
| API Key   | 内置默认 Key，可用环境变量 `XIEZITAI_AI_API_KEY` 覆盖    |

> 还没有大模型额度？可通过 <https://www.modelscope.cn/register?inviteCode=freetoken2208&invitorName=AIDever> 注册 ModelScope 申请（含免费额度），
> 在「访问令牌 / API-KEY」里创建自己的 Key 后填入后台即可。

说明：魔搭的文生图（Qwen-Image 系列）是**异步任务**协议（`POST /images/generations` 返回 `task_id`，再轮询 `GET /tasks/{id}`），
本项目已自动适配，同时兼容 OpenAI 的同步 `data[0].url` 返回。

## 开放 API：发布与读取

| 接口 | 说明 |
| --- | --- |
| `POST /api/v1/publish` | 发布文章：`title` / `content` 必填，可选 `slug` / `summary` / `cover` / `keywords` |
| `GET /api/v1/articles?page=1&size=20&q=` | 文章列表（JSON，仅已发布；`q` 在标题 / 摘要 / 正文 / 标签里匹配） |
| `GET /api/v1/articles/{id}` | 单篇文章详情（正文为 Markdown 原文） |
| `POST /api/v1/mcp` | MCP 服务（JSON-RPC 2.0） |

均以 `X-API-Token` 头鉴权（Token 等同账号，别贴到公开的地方）：

```bash
curl -s 'https://xiezitai.cn/api/v1/articles?page=1&size=5' -H 'X-API-Token: <你的 Token>'
```

> **导出时正文与封面里的站内资源会补成绝对地址**（`/media/x.png` → `https://站点/media/x.png`）——
> 对端（例如另一台写字台做「跨站导入」）据此判断哪些是本站自身文件、需要下载落盘，哪些是第三方外链应当原样保留。

## MCP 接入示例

后台「设置 → 开放 API / MCP」会按当前登录账号把下面三份配置**实时生成好并支持一键复制**
（Token、站点地址都替你填进去，不用手抄）。命令行自测：

```bash
curl -X POST https://xiezitai.cn/api/v1/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Token: 你的token" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

直接支持远程 HTTP MCP 的客户端（Cursor / VS Code / Claude Code / WorkBuddy 自定义连接器）：

```json
{
  "mcpServers": {
    "xiezitai": {
      "type": "http",
      "url": "https://xiezitai.cn/api/v1/mcp",
      "headers": { "X-API-Token": "你的token" }
    }
  }
}
```

Claude Desktop 只认 stdio 子进程，远程 HTTP 服务要用 `mcp-remote` 桥一下
（写进 macOS `~/Library/Application Support/Claude/claude_desktop_config.json` /
Windows `%APPDATA%\Claude\claude_desktop_config.json`，改完重启；需要本机有 Node.js）：

```json
{
  "mcpServers": {
    "xiezitai": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "https://xiezitai.cn/api/v1/mcp",
               "--header", "X-API-Token:你的token"]
    }
  }
}
```

> Token 等同于账号（可发布文章），别贴到公开的地方；怀疑泄露时在后台改一次密码即可让它失效。

## 本地开发与自测

无需 MySQL，用内存 H2 直接跑：

```bash
# 启动（H2 内存库，上传目录 data/uploads-dev）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 端到端集成测试（MockMvc + H2，不需要开放端口）
mvn test
```

集成测试覆盖：SEO 页面、登录与失败锁定（3/5/10 次）、TOTP 两步验证、
文章发布与草稿隔离、登录用户评论待审与审核（禁止匿名）、页面上线、上传类型限制与魔数扫描、
媒体访问留痕、开放 API 与 MCP（握手/工具列表/调用）、**首页站内搜索**（命中范围 / 草稿隔离 / 分页带 q / noindex / 空态）、
**写字台跨站导入**（开放 API 导出 JSON 与媒体绝对化 / 账号关联脱敏 / 单篇导入媒体落盘与外链保留 / 整站导入进度）、
请求日志、管理端权限。

- 测试类：`src/test/java/cn/xiezitai/XiezitaiApplicationTests.java`
- 测试配置：`src/test/resources/application-test.yml`

### 端到端验证（Playwright + 真实 Chrome）

`e2e/` 下有 30 余个脚本（含纯截图工具），覆盖后台建文发布、评论两级与审核、首页分页、媒体上传、
视频插入、改密、记住登录、TOTP 绑定、示例内容种子、**写字台跨站导入**、文章分发等场景，跑完打印 `PASS/FAIL` 汇总。
**必须在项目根目录执行**（截图输出到 `e2e/out/`）：

```bash
docker compose -f docker-compose.lite.yml up -d     # 或被测实例
node e2e/smoke.cjs
E2E_BASE=http://localhost:8099 node e2e/pager.cjs
```

详见 [`e2e/README.md`](e2e/README.md)。运维 / 联调小工具（CI 与镜像状态查询、AI 能力实调）
见 [`tools/README.md`](tools/README.md)。

## 目录

```
src/main/java/cn/xiezitai/
 ├─ controller/   接口（含开放 API / MCP / 媒体输出）
 ├─ service/      业务（Markdown、通知、AI、安全扫描、防篡改）
 ├─ security/     JWT、TOTP、登录失败锁定、请求日志过滤器
 ├─ repository/   Spring Data JPA
 ├─ entity/       实体
 └─ config/       默认数据初始化
src/main/resources/
 ├─ templates/    SEO 服务端渲染模板
 └─ static/       admin.html（ByteMD 管理后台）+ vendor/（本地内置前端依赖，无 CDN）
e2e/             Playwright 端到端脚本（截图输出到 e2e/out/）
tools/           CI 与联调小工具
```
