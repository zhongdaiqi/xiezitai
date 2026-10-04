# 写字台 (xiezitai)

SEO / AI 友好的自托管博客系统。官网：https://xiezitai.cn

- 技术栈：Java 17 · Spring Boot 3 · MySQL · JPA · JWT · Thymeleaf · ByteMD
- 仓库：https://github.com/zhongdaiqi/xiezitai

## 快速开始（Docker）

```bash
# 建议先设置环境变量
export MYSQL_PASSWORD=你的数据库密码
export XIEZITAI_JWT_SECRET=一串足够长的随机字符串

docker compose up -d --build
```

访问 `http://localhost:8080`，后台：`http://localhost:8080/admin.html`

默认管理员：`xiezitai / xiexiexie`（登录后请立即改密码并开启 TOTP）。

## 服务器部署（非 Docker）

1. 准备 MySQL 8，建库 `xiezitai`（utf8mb4）
2. `mvn package -DskipTests`
3. `java -jar target/xiezitai.jar`（可用环境变量覆盖 `MYSQL_*`、`XIEZITAI_*` 配置）

## 功能一览

| 模块 | 说明 |
|---|---|
| 文章 | Markdown（ByteMD 编辑器）、slug、SEO 字段、浏览计数 |
| 页面 | 自定义页面（关于、友链等） |
| 评论 | 游客评论 + 管理员审核 |
| 用户 | 注册 / 角色（ADMIN/USER）/ 按用户限制上传类型 |
| 文件 | 上传（默认仅图片/视频）、媒体经 Spring 输出并记录访问日志 |
| 安全 | TOTP 两步验证；密码错误 3 次锁 5 分钟、5 次锁 10 分钟、10 次锁 1 小时；全量请求日志；文件魔数扫描 + 孤立文件检测；防篡改基线校验 |
| 机器人 | 企业微信 webhook 通知（登录/文章/访问/注册/评论/上传），可逐项开关 |
| AI | OpenAI 兼容接口：润色纠错、摘要、封面图（公众号 900×383）、请求日志风险分析 |
| 开放 API | `POST /api/v1/publish`，Header `X-API-Token`（后台"设置"页查看） |
| MCP | `POST /api/v1/mcp`，JSON-RPC 2.0，工具：publish_article / list_articles / get_article |
| SEO | 服务端渲染、robots.txt、sitemap.xml、OG 标签 |

## MCP 接入示例

```bash
curl -X POST http://localhost:8080/api/v1/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Token: 你的token" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 目录

```
src/main/java/cn/xiezitai
 ├─ controller/   接口层（Auth/Article/Page/Comment/File/Media/Admin/OpenApi/页面渲染）
 ├─ service/      业务层（通知/AI/扫描/防篡改/Markdown）
 ├─ security/     JWT、TOTP、登录锁定、请求日志
 ├─ entity/ repository/ config/
src/main/resources
 ├─ templates/    SEO 服务端渲染模板
 └─ static/       admin.html（ByteMD 管理后台）
```
