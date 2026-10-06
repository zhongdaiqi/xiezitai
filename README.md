# 写字台 (xiezitai)

SEO / AI 友好的自托管博客系统。官网：<https://xiezitai.cn>

- 技术栈：Java 17 · Spring Boot 3 · MySQL · JPA · JWT · Thymeleaf · ByteMD
- 仓库：<https://github.com/zhongdaiqi/xiezitai>
- 镜像：<https://hub.docker.com/r/zhongdaiqi/xiezitai>（GitHub Actions 自动构建，支持 amd64 / arm64）

## 快速开始（Docker）

```bash
# 建议先设置环境变量
export MYSQL_PASSWORD=你的数据库密码
export XIEZITAI_JWT_SECRET=一串足够长的随机字符串

docker compose up -d --build
```

访问 `http://localhost:8080`，后台：`http://localhost:8080/admin.html`

默认管理员：`xiezitai / xiexiexie`（登录后请立即改密码并开启 TOTP）。

## 使用官方镜像（Docker Hub）

不想自己编译的话，直接拉官方构建好的镜像（amd64 / arm64 双架构）：

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

可用标签：

| 标签 | 说明 |
| --- | --- |
| `latest` | 默认分支 `main` 的最新构建 |
| `main` | 同上，语义化命名 |
| `1.2.3` / `1.2` | 打 `v1.2.3` tag 时自动生成 |
| `sha-xxxxxxx` | 按提交哈希固化的版本，适合回滚 |

镜像里的 JAR **不包含**任何 profile 写死配置：数据库、JWT 密钥、站点地址全部通过
`MYSQL_*` / `XIEZITAI_*` 环境变量注入（见 `application.yml`），`/app/data` 建议挂卷持久化。

### 自动构建流程

`.github/workflows/docker-publish.yml`：

- push 到 `main` → 构建并推送 `latest` / `main` / `sha-*`
- push `v*.*.*` tag → 构建并推送语义化版本号
- Pull Request → 只构建校验，不推送
- 也可在 Actions 页面手动触发

构建方式是**先编译 JAR、再装镜像**：用原生 amd64 速度跑一次 Maven，然后把同一个 JAR
分别装进 amd64 和 arm64 的基础镜像，避免在 QEMU 模拟的 arm64 里跑 Maven（慢 5~10 倍）。

需要在仓库 Settings → Secrets and variables → Actions 里配置两个 Secret：

| Secret | 值 |
| --- | --- |
| `DOCKERHUB_USERNAME` | Docker Hub 用户名 |
| `DOCKERHUB_TOKEN` | Docker Hub Access Token（不是登录密码） |

## 服务器部署（非 Docker）

1. 准备 MySQL 8，建库 `xiezitai`（utf8mb4）
2. `mvn package -DskipTests`
3. `java -jar target/xiezitai.jar`（可用环境变量覆盖 `MYSQL_*`、`XIEZITAI_*` 配置）

## 功能一览

| 模块     | 说明                                                                               |
| ------ | -------------------------------------------------------------------------------- |
| 文章     | Markdown（ByteMD 编辑器）、slug、SEO 字段、浏览计数；**插入媒体**：媒体库弹窗上传/挑选，图片入 Markdown，视频入 `<video>` 标签；支持粘贴截图、拖拽图片/视频到编辑器自动入库；媒体输出支持 HTTP Range（视频可拖进度条）  |
| 页面     | 自定义页面（关于、友链等）                                                                    |
| 评论     | **仅限登录用户**评论（禁止匿名），作者名取自登录账号不可伪造；提交后待管理员审核，通过后公开展示 + 文章页内嵌登录/注册弹窗                                                                    |
| 用户     | 注册 / 角色（ADMIN/USER）/ 按用户限制上传类型                                                   |
| 文件     | 上传（默认仅图片/视频）、媒体经 Spring 输出并记录访问日志                                                |
| 安全     | TOTP 两步验证（扫码/密钥绑定）；密码错误 3 次锁 5 分钟、5 次锁 10 分钟、10 次锁 1 小时；全量请求日志；文件魔数扫描 + 孤立文件检测；防篡改基线校验    |
| 机器人    | 企业微信 webhook 通知（登录/文章/访问/注册/评论/上传），可逐项开关                                         |
| AI     | OpenAI 兼容接口：润色纠错、摘要、封面图（公众号 900×383）、请求日志风险分析                                    |
| 开放 API | `POST /api/v1/publish`，Header `X-API-Token`（后台「设置」页查看）                           |
| MCP    | `POST /api/v1/mcp`，JSON-RPC 2.0，工具：publish_article / list_articles / get_article |
| SEO    | 服务端渲染、robots.txt、sitemap.xml、OG 标签                                               |
| 前端资源 | ByteMD / github-markdown-css / Mermaid **全部本地内置**（`static/vendor/`），不依赖任何外部 CDN，可离线/内网部署 |

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

## MCP 接入示例

```bash
curl -X POST http://localhost:8080/api/v1/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Token: 你的token" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

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
媒体访问留痕、开放 API 与 MCP（握手/工具列表/调用）、请求日志、管理端权限。

- 测试类：`src/test/java/cn/xiezitai/XiezitaiApplicationTests.java`
- 测试配置：`src/test/resources/application-test.yml`

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
```
