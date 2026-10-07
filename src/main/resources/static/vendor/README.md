# vendor — 本地内置的第三方前端资源

写字台**不依赖任何外部 CDN**，以下资源全部随程序打包（`classpath:/static/vendor/**`），
通过 `/vendor/**` 由本程序直接输出（请求同样计入请求日志）。离线环境 / 内网部署可直接使用。

| 本地路径 | 组件 | 版本 | 许可 | 来源 |
|---|---|---|---|---|
| `bytemd/index.min.css` | ByteMD 主样式 | 1.21.0 | MIT | npm `bytemd` |
| `bytemd/bytemd.umd.js` | ByteMD 编辑器内核（UMD，含 CodeMirror 5 + highlight.js） | 1.21.0 | MIT | npm `bytemd` /dist/index.umd.min.js |
| `bytemd/plugin-gfm.umd.js` | GFM 插件（表格/任务列表/删除线等），全局名 `bytemdPluginGfm` | 1.21.0 | MIT | npm `@bytemd/plugin-gfm` |
| `bytemd/plugin-frontmatter.umd.js` | Frontmatter 插件，全局名 `bytemdPluginFrontmatter` | 1.21.0 | MIT | npm `@bytemd/plugin-frontmatter` |
| `github-markdown-css/github-markdown-light.min.css` | GitHub Markdown 浅色主题 | 5.6.1 | MIT | npm `github-markdown-css` |
| `mermaid/mermaid.min.js` | Mermaid 图表库（当前 admin.html 已引入，暂未调用） | 10.9.1 | MIT | npm `mermaid` |
| `highlight/highlight.min.js` | highlight.js 内核（浏览器构建，含 36 种常用语言） | 11.12.0 | **BSD-3-Clause** | npm `@highlightjs/cdn-assets` |
| `highlight/highlight-langs.min.js` | highlight.js 补充语言包（本地拼接，见下节） | 11.12.0 | **BSD-3-Clause** | 同上 `languages/*.min.js` |
| `highlight/github.min.css` | highlight.js 浅色主题（GitHub） | 11.12.0 | **BSD-3-Clause** | 同上 `styles/github.min.css` |

## 发文页的代码块高亮 / 复制（`vendor/highlight/**`）

公开页（`templates/article.html`、`templates/page.html`）用 **highlight.js + 本站脚本**
把 Markdown 里的围栏代码块渲染成带配色、带语言标签、带「复制」按钮的样子：

```html
<link rel="stylesheet" href="/vendor/github-markdown-css/github-markdown-light.min.css">
<link rel="stylesheet" href="/vendor/highlight/github.min.css">
<script defer src="/vendor/highlight/highlight.min.js"></script>
<script defer src="/vendor/highlight/highlight-langs.min.js"></script>
<script defer src="/vendor/highlight/codeblock.js"></script>   <!-- 实际路径是 /js/codeblock.js -->
```

- 增强逻辑在 `static/js/codeblock.js`（**本站自己的代码，不属于 vendor**）：给
  `.markdown-body pre` 包一层 `.code-block`，注入 `.code-tools`（语言标签 + 复制按钮），
  调 `hljs.highlight()` 着色，复制走 Clipboard API 并回退 `execCommand`。
- ⚠️ `highlight.min.js` 是**常用语言包（36 种）**，不含 dockerfile / nginx / .properties /
  powershell / groovy / scala / http / cmake / protobuf / apache 配置。这些由
  `highlight-langs.min.js` 补齐（拼接顺序照下面脚本里的数组）。
- ⚠️ 主题 CSS 里的 `pre code.hljs{padding:1em}` 与 `github-markdown-css` 的
  `.markdown-body pre>code{padding:0}` **特异度相同、靠加载顺序决胜**，所以两个模板的
  `<style>` 里都写了一条 `.markdown-body pre code.hljs{padding:0;background:transparent}` 定死。
  换主题文件时别把这条删了，否则代码块会多出一圈内边距。

### 重建 `highlight-langs.min.js`

每个 `languages/*.min.js` 都是独立的 `hljs.registerLanguage("x", function(){…})` 语句，直接按序
拼接即可（首个文件前保留版权注释头）：

```powershell
$ver='11.12.0'; $dst='src/main/resources/static/vendor/highlight/highlight-langs.min.js'
$langs='dockerfile','nginx','properties','powershell','groovy','scala','http','cmake','protobuf','apache'
$base="https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@$ver/languages"
New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
$sb = New-Object System.Text.StringBuilder
foreach ($l in $langs) {
  $t = (Invoke-WebRequest "$base/$l.min.js" -UseBasicParsing).Content
  [void]$sb.AppendLine($t)
}
[System.IO.File]::WriteAllText($dst, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
# 校验：加载内核 + 拼接文件后，语言数应从 36 涨到 46
```


## ⚠️ 对 `bytemd.umd.js` 做过的本地补丁（升级时必须重新施加）

ByteMD 的 UMD 包里打进了 `@popperjs/core` + `tippy.js`，二者在**模块初始化阶段**直接读取
`process.env.NODE_ENV`（形如 `"production"!==process.env.NODE_ENV&&(Wn=new Set)`）。
浏览器里没有 `process` 全局，脚本一执行就抛 `ReferenceError: process is not defined`，
**整个 UMD 工厂中断 → `bytemd.Editor` 从未导出 → 编辑器空白**（控制台报错，页面无提示）。

处理方式：把这些 dev-only 判断在本地构建里**常量折叠**掉（等价于真正的生产构建，不引入全局
`process` 污染）：

```powershell
$f='src/main/resources/static/vendor/bytemd/bytemd.umd.js'
$t=[System.IO.File]::ReadAllText($f)
$t=$t.Replace('process.env.NODE_ENV','"production"')          # 共 21 处
[System.IO.File]::WriteAllText($f,$t,(New-Object System.Text.UTF8Encoding($false)))
# 校验：应输出 0
([regex]::Matches([System.IO.File]::ReadAllText($f),'process\.env')).Count
```

如果不想改动 vendor 文件，也可以改成在 bytemd 之前插一行 shim（代价是全局多一个 `process`）：

```html
<script>window.process = window.process || { env: { NODE_ENV: 'production' } };</script>
```

`plugin-gfm.umd.js` / `plugin-frontmatter.umd.js` / `github-markdown-css` / `mermaid.min.js`
经检查**不含** `process.env` 引用，无需补丁。

## 用法约定（重要）

- 主包 UMD **只暴露具名成员**：`bytemd.Editor` / `bytemd.Viewer` / `bytemd.getProcessor`，
  **没有 `default` 导出**，不要写 `new bytemd.default(...)`。
- 页面里**不要声明名为 `bytemd` 的全局变量**（如 `let bytemd = null`），
  全局 `let` 会遮蔽 UMD 的全局命名空间，导致 `bytemd.Editor` 取不到。
- 插件 UMD 挂载的全局名：`bytemdPluginGfm()`、`bytemdPluginFrontmatter()`。
- CSS 内的 `url()` 均为内联 `data:` URI，无二次外部依赖。

## 升级方式

替换同目录文件即可（保持文件名不变），升级后建议校验 UMD 的导出形态是否变化：

```powershell
# 例：拉取新版（jsDelivr 与 npmmirror 互为备份）
Invoke-WebRequest -Uri 'https://cdn.jsdelivr.net/npm/bytemd@1.21.0/dist/index.umd.min.js' `
  -OutFile 'src/main/resources/static/vendor/bytemd/bytemd.umd.js' -UseBasicParsing
```

## 许可

以上组件均为 MIT 许可，版权归各自作者所有，可随本项目一并分发。各组件完整许可文本见其 npm 包内 `LICENSE`。
