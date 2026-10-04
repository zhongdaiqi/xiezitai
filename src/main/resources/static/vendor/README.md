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
