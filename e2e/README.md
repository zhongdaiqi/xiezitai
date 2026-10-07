# 端到端验证脚本（Playwright + 真实 Chrome）

用无头 Chrome 把关键流程真跑一遍，收集 `pageerror` / `console.error` / HTTP ≥ 400，
末尾打印 `PASS/FAIL` 汇总，截图落到 `e2e/out/`。

## 前置

- Node 22+ 与 Google Chrome
- 已安装 `playwright`（本机用托管 node 的 workspace 里的 node_modules）

## 怎么跑

**必须在项目根目录执行** —— 脚本里的截图路径是相对项目根的。

```bash
# 1) 先起一个被测服务
docker compose -f docker-compose.lite.yml up -d          # 默认 8080
# 或
java -jar target/xiezitai.jar --server.port=8099 --spring.profiles.active=lite

# 2) 跑脚本（E2E_BASE 不传则默认 http://localhost:8080）
node e2e/smoke.cjs
E2E_BASE=http://localhost:8099 node e2e/pager.cjs
```

Windows 本机（托管 node）示例：

```powershell
$env:NODE_PATH='C:\Users\Administrator\.workbuddy\binaries\node\workspace\node_modules'
$env:E2E_BASE='http://127.0.0.1:8099'
& 'C:\Users\Administrator\.workbuddy\binaries\node\versions\22.22.2-3\node.exe' e2e/demo-seed.cjs
```

## 脚本清单

| 脚本 | 覆盖点 |
| --- | --- |
| `smoke.cjs` | 登录 → 编辑器 → 建文发布 → 前台可见（基础冒烟） |
| `comment.cjs` | 评论提交、登录 / 匿名差异 |
| `comment-style.cjs` | 评论区样式 |
| `comment-thread.cjs` | 两级评论、回复 @、级联删 |
| `pager.cjs` | 首页分页、任务列表渲染 |
| `media.cjs` | 图片 / 视频上传与前台展示 |
| `video-insert.cjs` | 编辑器工具栏插入 `<video>` |
| `password.cjs` | 修改密码流程 |
| `user-audit.cjs` | 注册审核：待审核拦截 → 后台「用户」页通过/驳回（含备注）→ 通过后可用、驳回后令牌立即失效 |
| `remember.cjs` | 记住登录（localStorage vs sessionStorage） |
| `totp.cjs` | TOTP 绑定 |
| `demo-seed.cjs` | 空库首启示例内容（4 文章 / 2 页面 / 5 评论） |
| `shot-*.cjs` | 纯截图工具，无断言，供人工核对视觉 |

## 说明

- 被测服务需为**空态可控**的实例；`demo-seed.cjs` 要求空库（H2 文件库用 `XIEZITAI_DB_PATH` 指到临时目录即可）
- 截图输出目录 `e2e/out/` 已在 `.gitignore` 中忽略
- 默认管理员 `xiezitai / xiexiexie`
- **自助注册的用户是「待审核」状态，登录会被 403 挡下**：脚本里注册完普通用户后，
  必须先用管理员调一次审核接口放行才能登录 —— 统一走 `lib/audit.cjs` 的 `auditUser()`，
  它调的是真实后台接口，顺带把审核链路本身也覆盖了：

  ```js
  const { auditUser } = require('./lib/audit.cjs');
  await auditUser(BASE, adminToken, username);                       // 通过
  await auditUser(BASE, adminToken, username, 'REJECTED', '原因');    // 驳回
  ```
