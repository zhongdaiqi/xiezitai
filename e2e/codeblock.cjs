// 文章代码块：语法高亮 + 一键复制
// 覆盖：① 标注语言的块被高亮（含 vendor 补充语言包里的 dockerfile）
//      ② 未标注语言的块不高亮但仍有复制按钮（刻意不猜语言，见 js/codeblock.js 注释）
//      ③ mermaid / 未知语言 不崩、不着色
//      ④ 代码里的 HTML 被转义，不产生 XSS
//      ⑤ 复制内容 = 作者原文（Clipboard API 与 execCommand 回退两条路都验）
//      ⑥ 页面无 JS 报错、无 4xx（含 vendor/highlight 资源 404）
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];

// ---------- 被测文章（markdown 原文，服务端 commonmark 渲染） ----------
const JAVA_CODE = [
  'public class Hello {',
  '    // 中文注释也不该影响高亮',
  '    private static final String NAME = "写字台";',
  '',
  '    public static void main(String[] args) {',
  '        System.out.println("hello " + NAME);',
  '    }',
  '}'
].join('\n');

const PLAIN_CODE = ['no language specified here', 'second line of plain text'].join('\n');

const DOCKER_CODE = [
  'FROM eclipse-temurin:17-jre',
  'WORKDIR /app',
  'COPY target/xiezitai.jar app.jar',
  'ENTRYPOINT ["java", "-jar", "app.jar"]'
].join('\n');

const XSS_CODE = ['<script>window.__xzXss = 1;</script>', '<b>bold</b>'].join('\n');

const MD = [
  '# 代码块高亮与复制自测',
  '',
  '下面依次是 Java（标注语言）、纯文本（未标注）、Dockerfile（vendor 补充语言包）、',
  'mermaid（跳过）、未知语言（跳过）、含 HTML 的 xml（转义检查）。',
  '',
  '```java',
  JAVA_CODE,
  '```',
  '',
  '```',
  PLAIN_CODE,
  '```',
  '',
  '```dockerfile',
  DOCKER_CODE,
  '```',
  '',
  '```mermaid',
  'graph TD; A-->B;',
  '```',
  '',
  '```foobar',
  'unknown language body',
  '```',
  '',
  '```html',
  XSS_CODE,
  '```',
  ''
].join('\n');

async function apiPublish() {
  const login = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'xiezitai', password: 'xiexiexie' })
  });
  if (!login.ok) throw new Error('管理员登录失败 ' + login.status);
  const { token } = await login.json();
  const me = await (await fetch(BASE + '/api/auth/me', { headers: { Authorization: 'Bearer ' + token } })).json();
  if (!me.apiToken) throw new Error('管理员没有 API Token，无法用开放接口建文章');
  const res = await fetch(BASE + '/api/v1/publish', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Token': me.apiToken },
    body: JSON.stringify({ title: '代码块高亮与复制自测', content: MD, slug: 'e2e-codeblock-' + Date.now() })
  });
  if (!res.ok) throw new Error('发布失败 ' + res.status + ' ' + (await res.text()).slice(0, 200));
  return (await res.json()).url;
}

(async () => {
  const url = await apiPublish();
  console.log('已发布测试文章: ' + url);

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE });
  const page = await context.newPage();

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  await page.goto(BASE + url, { waitUntil: 'load' });
  await page.waitForSelector('.code-block', { timeout: 10000 });

  // 每个代码块的结构快照
  const snap = await page.evaluate(() => Array.from(document.querySelectorAll('.markdown-body .code-block')).map(w => {
    const pre = w.querySelector('pre');
    const code = pre && pre.firstElementChild && pre.firstElementChild.tagName === 'CODE'
      ? pre.firstElementChild : pre;
    const langEl = w.querySelector('.code-lang');
    const btn = w.querySelector('.code-copy');
    return {
      code: code ? code.textContent : '',
      html: code ? code.innerHTML : '',
      langClass: code && code.className ? code.className : '',
      hljs: !!(code && code.classList.contains('hljs')),
      tokenCount: code ? code.querySelectorAll('[class^=hljs-]').length : 0,
      label: langEl ? langEl.textContent.trim() : null,
      hasCopy: !!btn,
      copyText: btn ? btn.textContent.trim() : '',
      toolsOnTop: (() => {
        const t = w.querySelector('.code-tools');
        if (!t) return false;
        const cs = getComputedStyle(t);
        return cs.position === 'absolute' && cs.top !== 'auto';
      })()
    };
  }).map(x => x));

  const log = (...a) => console.log(...a);
  log('代码块数量: ' + snap.length + ' → ' + snap.map(s => s.label || '(无标签)').join(' / '));

  check('① 六个代码块全部被包裹成 .code-block', snap.length === 6, snap.length);
  check('① 每个块都有复制按钮', snap.every(s => s.hasCopy), snap.filter(s => !s.hasCopy).length + ' 个缺失');
  check('① 工具栏绝对定位于块内（<pre> 横滚时不跟着滚）', snap.every(s => s.toolsOnTop));

  // ② Java 块
  check('② java 块被高亮（有 hljs 类与 token span）', snap[0].hljs && snap[0].tokenCount > 3,
    'tokens=' + snap[0].tokenCount);
  check('② java 块语言标签 = java', snap[0].label === 'java', snap[0].label);
  check('② java 块关键字被识别（含 hljs-keyword）', /hljs-keyword/.test(snap[0].html));
  check('② java 块中文注释被当作注释渲染', /hljs-comment/.test(snap[0].html));

  // ③ 未标注语言：不高亮，但可复制
  check('③ 无语言块不着色（刻意不猜语言）', !snap[1].hljs && snap[1].tokenCount === 0, 'hljs=' + snap[1].hljs);
  check('③ 无语言块不显示语言标签', snap[1].label === null, snap[1].label);
  check('③ 无语言块仍有复制按钮', snap[1].hasCopy);

  // ④ 补充语言包（dockerfile 不在 highlight.js 常用包里）
  check('④ dockerfile 块被高亮（补充语言包生效）', snap[2].hljs && snap[2].tokenCount > 2,
    'tokens=' + snap[2].tokenCount);
  check('④ dockerfile 标签正确', snap[2].label === 'dockerfile', snap[2].label);

  // ⑤ mermaid 与未知语言：不崩、不着色
  check('⑤ mermaid 块被跳过（留给将来渲染成图）', !snap[3].hljs, 'hljs=' + snap[3].hljs);
  check('⑤ 未知语言块被跳过而非乱猜', !snap[4].hljs, 'hljs=' + snap[4].hljs);
  check('⑤ 未知语言块正文完整', /unknown language body/.test(snap[4].code));

  // ⑥ XSS：代码里的 HTML 必须转义成实体
  check('⑥ 代码里的 <script> 被转义（innerHTML 里没有裸 <script）', !/<script/i.test(snap[5].html));
  check('⑥ 代码里的 <script> 被转义（textContent 是原文）', snap[5].code.includes('<script>window.__xzXss = 1;</script>'));
  check('⑥ 页面没有被注入的脚本执行', await page.evaluate(() => window.__xzXss === undefined));
  check('⑥ 正文里没有多出 script 元素',
    await page.evaluate(() => document.querySelectorAll('.markdown-body script').length === 0));

  // ⑦ 复制（Clipboard API）
  const btnPlain = page.locator('.code-block').nth(1).locator('.code-copy');
  await btnPlain.click();
  await page.waitForFunction(() => {
    const b = document.querySelectorAll('.code-block')[1].querySelector('.code-copy');
    return /已复制|复制失败/.test(b.textContent);
  }, { timeout: 5000 });
  const btnState = await btnPlain.textContent();
  const clipApi = await page.evaluate(() => navigator.clipboard.readText());
  // ⚠️ Windows 上 Chromium 写系统剪贴板会把 \n 规范成 \r\n，比较前统一换行，别把它当成功能问题
  const norm = s => String(s == null ? '' : s).replace(/\r\n/g, '\n').trim();
  check('⑦ 点复制后按钮给出反馈', /已复制/.test(btnState), btnState.trim());
  check('⑦ 剪贴板内容 = 未标注语言块原文', norm(clipApi) === norm(PLAIN_CODE), JSON.stringify(clipApi));

  const btnJava = page.locator('.code-block').nth(0).locator('.code-copy');
  await btnJava.click();
  await page.waitForTimeout(300);
  const clipJava = await page.evaluate(() => navigator.clipboard.readText());
  check('⑦ 高亮块复制到的是原文而非带标签的 HTML',
    norm(clipJava) === norm(JAVA_CODE) && !/<span/.test(clipJava), JSON.stringify(clipJava.slice(0, 40)));

  // ⑧ 反馈自动复原
  await page.waitForTimeout(1800);
  check('⑧ 复制按钮 1.8s 后复原为「复制」',
    (await page.locator('.code-block').nth(0).locator('.code-copy').textContent()).trim() === '复制');

  await page.screenshot({ path: 'e2e/out/22-codeblock.png', fullPage: true });

  // ⑨ 回退路径：没有 Clipboard API（非安全上下文）时走 execCommand
  const ctx2 = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
  await ctx2.addInitScript(() => {
    try { Object.defineProperty(navigator, 'clipboard', { get: () => undefined }); } catch (e) { }
  });
  const page2 = await ctx2.newPage();
  page2.on('pageerror', e => errors.push('PAGEERROR(fallback): ' + e.message));
  await page2.goto(BASE + url, { waitUntil: 'load' });
  await page2.waitForSelector('.code-block', { timeout: 10000 });
  const hasClipboardApi = await page2.evaluate(() => !!navigator.clipboard);
  await page2.locator('.code-block').nth(1).locator('.code-copy').click();
  await page2.waitForFunction(() => {
    const b = document.querySelectorAll('.code-block')[1].querySelector('.code-copy');
    return /已复制|复制失败/.test(b.textContent);
  }, { timeout: 5000 });
  const fb = (await page2.locator('.code-block').nth(1).locator('.code-copy').textContent()).trim();
  check('⑨ 无 Clipboard API 时回退 execCommand 仍可复制', !hasClipboardApi && fb === '已复制',
    'clipboardAPI=' + hasClipboardApi + ' btn=' + fb);

  // ⑩ 资源与静默错误
  const vendor = await page.evaluate(async () => {
    const urls = ['/vendor/highlight/highlight.min.js', '/vendor/highlight/highlight-langs.min.js',
      '/vendor/highlight/github.min.css', '/js/codeblock.js'];
    const out = {};
    for (const u of urls) out[u] = (await fetch(u)).status;
    return out;
  });
  check('⑩ 高亮资源全部 200', Object.values(vendor).every(s => s === 200), JSON.stringify(vendor));
  check('⑩ 页面无 JS 报错', errors.length === 0, errors.slice(0, 4).join(' ; '));
  check('⑩ 无 HTTP >= 400', httpBad.length === 0, httpBad.slice(0, 4).join(' ; '));

  await browser.close();

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
  if (failed.length) { failed.forEach(f => console.log('  FAILED: ' + f.name)); process.exit(1); }
})().catch(e => {
  console.error('SCRIPT_ERROR', e && e.message ? e.message : e);
  errors.slice(0, 8).forEach(x => console.error('  PAGEERR: ' + x));
  httpBad.slice(0, 8).forEach(x => console.error('  HTTPBAD: ' + x));
  process.exit(2);
});
