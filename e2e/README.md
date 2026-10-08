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
| `article-search-pager.cjs` | 后台文章列表：分页条（共 N 篇 / 第 x/y 页 / 首末页禁用态）、关键词搜索（标题+摘要+正文，命中数 / 空态 / 清空恢复）、状态筛选与搜索叠加、点「编辑」载入正文+视图互斥（进编辑隐藏列表 / 返回恢复）、「+ 新建文章」进编辑视图并清空表单；临时建的 12 篇跑完自删 |
| `admin-article-view.cjs` | 文章列表视图 / 编辑视图拆分：登录默认落列表（编辑器按需挂载）→ 点「+ 新建文章」进编辑 → 保存提示「保存成功」并自动回列表、新文章在首条 → 行内「编辑」回填 → 「← 返回列表」；临时建的 1 篇跑完自删 |
| `admin-article-tags.cjs` | 文章标签：chip 输入（回车/逗号提交、× 删除、计数 n/10）、重复标签被拦、超过 10 个加不进去（第 11 个被前端拦下）、保存后列表「标签」列出现徽章、后台搜索按标签命中、再编辑 chip 回填且删标签保存后徽章同步、前台文章页渲染标签徽章；临时建的 1 篇跑完自删 |
| `admin-editor-fullscreen.cjs` | 编辑器全屏不被吸顶顶栏遮挡：进入全屏态、z-index 高于 #topbar、**命中测试**证明工具栏没被顶栏吃掉、退出全屏恢复；顶栏仍可见（是层级生效而非隐藏） |
| `admin-page-list.cjs` | 页面面板的列表 / 搜索 / 筛选 / 分页 / 视图拆分：默认落列表（编辑器不预挂载）→ 关键词搜标题/slug/正文、搜不到给空态且计数 0、清空恢复 → 状态筛选与搜索叠加 → 超 10 条自动分页（首末页禁用态、翻页行数、筛选后页码越界自动回退）→ 「+ 新建页面」进编辑视图（此时才挂编辑器）/「← 返回列表」→ 保存提示「保存成功：<标题>」并自动回列表 → 行内「编辑」回填、行内「删除」生效；临时建的 13 个页面跑完自删 |
| `admin-page-editor.cjs` | 页面编辑改用 ByteMD：进编辑视图（点「+ 新建页面」）才挂载、工具栏按钮数 ≥ 8 且含图片/全屏、markdown 预览渲染（h1/表格）、「插入媒体」目标=页面编辑器、保存提示「保存成功：<标题>」+ 回列表，再点「编辑」正文回填；临时建的 1 个页面跑完自删 |
| `media.cjs` | 图片 / 视频上传与前台展示 |
| `cover.cjs` | 封面三种来源：本地上传 / 从媒体库选择 / 清除；放大预览灯箱（点缩略图、按钮、ESC / 遮罩关闭）；AI 封面只允许站内 `/media/` 地址、绝不返回第三方链接；发布后前台封面 + `og:image` 绝对地址 |
| `ai-seo.cjs` | AI 写作辅助：优化标题 / 提取 SEO 关键词 / 提取 SEO 描述 三个按钮 → 接口 → 字段回填；模型脏输出被洗净；**AI 不可用时只提示、不覆盖用户内容**；保存后落库并在前台 `meta keywords/description` 生效 |
| `video-insert.cjs` | 编辑器工具栏插入 `<video>` |
| `codeblock.cjs` | 文章代码块（前台）：语法高亮（标注语言才着色、dockerfile 走补充语言包）、语言标签、复制按钮（Clipboard API 与 execCommand 回退）、代码内 HTML 被转义不 XSS |
| `admin-codeblock.cjs` | 后台编辑器**实时预览**里的代码块：与前台同一套观感；重点验「边打字边重渲染」下的可重入性 —— 改内容后重新着色、工具条不重复堆叠、删掉语言标记退回纯文本、编辑区 CodeMirror 不受影响 |
| `password.cjs` | 修改密码流程 |
| `user-audit.cjs` | 注册审核：待审核拦截 → 后台「用户」页通过/驳回（含备注）→ 通过后可用、驳回后令牌立即失效 |
| `remember.cjs` | 记住登录（localStorage vs sessionStorage） |
| `totp.cjs` | TOTP 绑定 |
| `wp-associate.cjs` | WordPress 关联与导入：脚本内起本地 mock WP 站点（REST + 媒体文件）→ 面板空态 → 关联站点（接口不回显 token 字段）→ 浏览文章（12 篇分页）→ 单篇导入（**正文优先取 `content.raw` 的 Markdown 原文**而不是插件渲染后的 HTML、站点自身图片落盘换 /media/、外站图保留外链、无 wp-content 残留、标签来自 WP）→ 切「当前时间」导入→ 切「强制转 Markdown」（用 `content.rendered` 的 HTML 还原成 Markdown）→ 重复导入跳过 → 更新模式复用 id → 整站导入进度条到「整站导入完成：成功 9 跳过 3」；导入文章与站点跑完自删 |
| `cnblog-associate.cjs` | 博客园关联与导入（MetaWeblog XML-RPC）：脚本内起本地 mock XML-RPC 服务器 + 图片文件 → 面板空态 → 关联账号（接口不回显 appKey 字段）→ 浏览文章（3 篇）→ 单篇导入（cnblogs 域图片落盘换 /media/、外站图保留外链、标签/摘要/发布人/原发布时间）→ 切「当前时间」导入发布时间=今天 → 重复导入跳过 → 更新模式复用 id → 整站导入「成功 1 跳过 2」→ 纯中文标题 slug 稳定 cnblog-{postid} 且详情页 200；**服务端需以 `--xiezitai.cn-media-hosts=127.0.0.1` 启动**（放行 mock 主机下载图片，生产默认只认 *.cnblogs.com）；导入文章与账号跑完自删 |
| `demo-seed.cjs` | 空库首启示例内容（4 文章 / 2 页面 / 5 评论） |
| `shot-*.cjs` | 纯截图工具，无断言，供人工核对视觉 |

## AI 相关脚本：用假上游跑，别依赖真实大模型

`ai-seo.cjs`（以及 `cover.cjs` 的 AI 封面那步）需要一个**输出固定**的大模型上游，否则断言没有基准，
还会把真实第三方服务（默认魔搭 ModelScope）的延迟、限流、配额带进 E2E。
`lib/fake-ai.cjs` 就是本机假上游：OpenAI 兼容的 `chat/completions` + `images/generations`，
返回**故意带格式问题**的内容（包裹引号、「优化后的标题：」标签、带序号的列表），用来验证清洗逻辑。

```bash
# ① 起假上游（默认 127.0.0.1:8123，可用 FAKE_AI_PORT 改）
node e2e/lib/fake-ai.cjs

# ② 起被测实例时把大模型指过来（⚠️ 必须用全新空库，见下）
java -jar target/xiezitai.jar --server.port=8099 --spring.profiles.active=lite \
  --XIEZITAI_AI_BASE_URL=http://127.0.0.1:8123/v1   # 生产用环境变量注入，别写进命令行
```

⚠️ **必须用全新的空库**：`sys_configs` 里的 `ai.*` 只在「键不存在」时由 `DataInitializer` 写入，
复用一个跑过的库，新环境变量不会生效（仍旧指向真实大模型）。把 `XIEZITAI_DB_PATH`
指到一个新的临时目录即可：

```bash
export XIEZITAI_DB_PATH='E:/tmp/xz-e2e/db/xiezitai'
export XIEZITAI_UPLOAD_DIR='E:/tmp/xz-e2e/uploads'
export XIEZITAI_AI_BASE_URL='http://127.0.0.1:8123/v1'
export XIEZITAI_AI_API_KEY='fake-key'
export XIEZITAI_AI_MODEL='fake-chat'
export XIEZITAI_AI_IMAGE_MODEL='fake-image'
```

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
