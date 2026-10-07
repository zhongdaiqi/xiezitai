// 后台编辑器「实时预览」里的代码块：语法高亮 + 一键复制
//
// 与前台（e2e/codeblock.cjs）的差别在于：后台预览是 ByteMD 受控组件，
// **每次输入都会把预览区整段重渲染**，所以这里重点验的是「重入正确性」——
// 内容一变要重新着色、工具条不能一次次往上堆、语言标记删掉后要退回纯文本。
//
// 覆盖：
//   ① 预览区里的代码块被增强（与前台同一套 .code-block / 工具条），挂在 .bytemd-preview 内
//   ② 标注语言的块被高亮（含 vendor 补充语言包里的 dockerfile）
//   ③ 未标注语言的块不高亮、无语言标签，但仍有复制按钮
//   ④ 编辑区（CodeMirror）完全不受影响 —— 不能被包成 .code-block
//   ⑤ 改一行内容 → 重新高亮，且工具条不重复堆叠、块数不翻倍、无嵌套残留
//   ⑥ 删掉语言标记 → 退回纯文本（hljs 类与高亮 span 都被撤掉），复制按钮仍在
//   ⑦ 复制到的是原文，不是高亮后的 HTML
//   ⑧ 全程无 JS 报错、无 HTTP >= 400
const { chromium } = require('playwright');
const { enterNewArticle } = require('./lib/admin-ui.cjs');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];

// ---------- 用例素材 ----------
const JAVA_1 = [
  'public class Hi {',
  '    // 中文注释',
  '    public static void main(String[] a) { System.out.println("hi"); }',
  '}'
].join('\n');

const JAVA_2 = [
  'public class Hi {',
  '    // 中文注释',
  '    public static void main(String[] a) { System.out.println("hello 写字台"); }',
  '}'
].join('\n');

const PLAIN = ['plain line one', 'plain line two'].join('\n');

const DOCKER = [
  'FROM eclipse-temurin:17-jre',
  'ENTRYPOINT ["java", "-jar", "app.jar"]'
].join('\n');

const MERMAID = 'graph TD; A-->B;';

/** 拼一份 markdown：java / 无语言 / dockerfile / mermaid */
const buildMd = java => [
  '# 后台预览代码块自测',
  '',
  '```java',
  java,
  '```',
  '',
  '```',
  PLAIN,
  '```',
  '',
  '```dockerfile',
  DOCKER,
  '```',
  '',
  '```mermaid',
  MERMAID,
  '```',
  ''
].join('\n');

/** 第三份：把 java 块的语言标记去掉，用来验「回退成纯文本」 */
const MD_NO_LANG = buildMd(JAVA_1).replace('```java', '```');

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE });
  const page = await context.newPage();

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  // ---------- 登录后台 ----------
  await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1000);
  if (!(await page.isVisible('#app').catch(() => false))) {
    await page.fill('#luser', 'xiezitai');
    await page.fill('#lpass', 'xiexiexie');
    await page.click('#login button');
  }
  await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
  // 文章面板默认落在列表视图 → 先进「+ 新建文章」的编辑视图，编辑器才存在
  await enterNewArticle(page);

  /** 把 markdown 写进编辑器（走 CodeMirror，等价于用户粘贴全文） */
  async function setMd(md, mustContain) {
    await page.evaluate(v => {
      document.querySelector('#editor .CodeMirror').CodeMirror.setValue(v);
    }, md);
    await page.waitForFunction(t =>
      (document.querySelector('.bytemd-preview .markdown-body') || {}).innerText
        ? document.querySelector('.bytemd-preview .markdown-body').innerText.includes(t) : false,
      mustContain, { timeout: 10000 }).catch(() => console.log('WAIT_PREVIEW_TIMEOUT'));
    await page.waitForTimeout(600);   // 等脚本的 160ms 防抖扫完 + 高亮落定
  }

  /** 预览区每个代码块的结构快照 */
  const snap = () => page.evaluate(() => Array.from(
    document.querySelectorAll('.bytemd-preview .markdown-body .code-block')
  ).map(w => {
    const pre = w.querySelector('pre');
    const code = pre && pre.firstElementChild && pre.firstElementChild.tagName === 'CODE'
      ? pre.firstElementChild : pre;
    return {
      text: code ? code.textContent : '',
      html: code ? code.innerHTML : '',
      hljs: !!(code && code.classList.contains('hljs')),
      tokens: code ? code.querySelectorAll('span[class^=hljs-]').length : 0,
      tokensWithSpan: code ? Array.from(code.querySelectorAll('span[class^=hljs-]'))
        .filter(s => s.querySelector('span')).length : 0,
      label: w.querySelector('.code-lang') ? w.querySelector('.code-lang').textContent.trim() : null,
      tools: w.querySelectorAll('.code-tools').length,
      copies: w.querySelectorAll('.code-copy').length
    };
  }));

  // ---------- ① 预览区增强 ----------
  await setMd(buildMd(JAVA_1), 'plain line one');
  const s1 = await snap();
  console.log('预览块数=' + s1.length + ' → ' + s1.map(s => s.label || '(无标签)').join(' / '));

  check('① 预览区四个代码块全部被增强', s1.length === 4, s1.length);
  check('① 每个块恰好一个工具条、一个复制按钮',
    s1.every(s => s.tools === 1 && s.copies === 1),
    s1.map(s => s.tools + '/' + s.copies).join(' '));
  check('① 增强只发生在预览区（编辑区不被包裹）',
    await page.evaluate(() => document.querySelectorAll('#editor .CodeMirror .code-block').length === 0));

  // ---------- ② 高亮 ----------
  check('② java 块被高亮（hljs 类 + token span）', s1[0].hljs && s1[0].tokens > 3, 'tokens=' + s1[0].tokens);
  check('② java 块语言标签 = java', s1[0].label === 'java', s1[0].label);
  check('② java 关键字被识别', /hljs-keyword/.test(s1[0].html));
  check('② 中文注释被当作注释', /hljs-comment/.test(s1[0].html));
  check('② dockerfile 块被高亮（vendor 补充语言包在后台同样生效）',
    s1[2].hljs && s1[2].tokens > 1, 'tokens=' + s1[2].tokens);
  check('② dockerfile 标签正确', s1[2].label === 'dockerfile', s1[2].label);

  // ---------- ③ 未标注语言 ----------
  check('③ 无语言块不高亮（不猜语言）', !s1[1].hljs && s1[1].tokens === 0, 'hljs=' + s1[1].hljs);
  check('③ 无语言块不显示语言标签', s1[1].label === null, String(s1[1].label));
  check('③ 无语言块仍有复制按钮', s1[1].copies === 1);
  check('③ mermaid 块被跳过（留给将来渲染成图）', !s1[3].hljs, 'hljs=' + s1[3].hljs);

  await page.screenshot({ path: 'e2e/out/23-admin-codeblock.png', fullPage: true });

  // ---------- ⑤ 改一行：重渲染后要重新着色，且不能堆叠 ----------
  await setMd(buildMd(JAVA_2), 'hello 写字台');
  const s2 = await snap();
  console.log('改行后：块数=' + s2.length + ' tokens=' + s2[0].tokens + ' 工具条=' + s2[0].tools);
  check('⑤ 改内容后仍是四个块（没有翻倍）', s2.length === 4, s2.length);
  check('⑤ 新内容被重新高亮（tokens > 3）', s2[0].hljs && s2[0].tokens > 3, 'tokens=' + s2[0].tokens);
  check('⑤ 预览文本已更新', s2[0].text.includes('hello 写字台'));
  check('⑤ 工具条没有一次次往上堆', s2.every(s => s.tools === 1 && s.copies === 1),
    s2.map(s => s.tools + '/' + s.copies).join(' '));
  check('⑤ 高亮结果无嵌套 token（没有二次高亮的残留）', s2.every(s => s.tokensWithSpan === 0),
    s2.map(s => s.tokensWithSpan).join(' '));

  // 同样的内容再设一遍：应当原地空转，不重复增强
  await setMd(buildMd(JAVA_2), 'hello 写字台');
  const s3 = await snap();
  check('⑤ 重复设置相同内容不会重复包裹/堆叠',
    s3.length === 4 && s3.every(s => s.tools === 1 && s.copies === 1),
    s3.map(s => s.tools + '/' + s.copies).join(' '));

  // ---------- ⑥ 删掉语言标记：退回纯文本 ----------
  await setMd(MD_NO_LANG, 'plain line one');
  const s4 = await snap();
  console.log('去标记后：块0 hljs=' + s4[0].hljs + ' tokens=' + s4[0].tokens + ' label=' + s4[0].label);
  check('⑥ 去掉语言标记后不再高亮', !s4[0].hljs && s4[0].tokens === 0, 'hljs=' + s4[0].hljs);
  check('⑥ 语言标签消失', s4[0].label === null, String(s4[0].label));
  check('⑥ 正文完整（高亮 span 已撤回纯文本）', s4[0].text.includes('System.out.println'), s4[0].text.slice(0, 40));
  check('⑥ 复制按钮仍在', s4[0].copies === 1);
  check('⑥ 其它三个块不受影响（仍是四个）', s4.length === 4, s4.length);

  // ---------- ⑦ 复制 ----------
  const norm = s => String(s == null ? '' : s).replace(/\r\n/g, '\n').trim();
  // 恢复到带语言的版本，复制第 0 块（java）
  await setMd(buildMd(JAVA_1), 'System.out.println');
  const btn = page.locator('.bytemd-preview .markdown-body .code-block').nth(0).locator('.code-copy');
  await btn.click();
  await page.waitForFunction(() => {
    const b = document.querySelectorAll('.bytemd-preview .markdown-body .code-block')[0].querySelector('.code-copy');
    return /已复制|复制失败/.test(b.textContent);
  }, { timeout: 5000 });
  const btnState = (await btn.textContent()).trim();
  const clip = await page.evaluate(() => navigator.clipboard.readText());
  check('⑦ 点复制后按钮给出反馈', btnState === '已复制', btnState);
  check('⑦ 剪贴板内容 = 代码原文（不含高亮标签）',
    norm(clip) === norm(JAVA_1) && !/<span/.test(clip), JSON.stringify(String(clip).slice(0, 40)));

  // 无语言块同样可复制
  const btn2 = page.locator('.bytemd-preview .markdown-body .code-block').nth(1).locator('.code-copy');
  await btn2.click();
  await page.waitForTimeout(400);
  const clip2 = await page.evaluate(() => navigator.clipboard.readText());
  check('⑦ 无语言块也能复制原文', norm(clip2) === norm(PLAIN), JSON.stringify(String(clip2).slice(0, 40)));

  // ---------- ⑧ 静默错误 ----------
  const vendor = await page.evaluate(async () => {
    const urls = ['/vendor/highlight/highlight.min.js', '/vendor/highlight/highlight-langs.min.js',
      '/vendor/highlight/github.min.css', '/js/codeblock.js'];
    const out = {};
    for (const u of urls) out[u] = (await fetch(u)).status;
    return out;
  });
  check('⑧ 高亮资源全部 200', Object.values(vendor).every(s => s === 200), JSON.stringify(vendor));
  check('⑧ 页面无 JS 报错', errors.length === 0, errors.slice(0, 4).join(' ; '));
  check('⑧ 无 HTTP >= 400', httpBad.length === 0, httpBad.slice(0, 4).join(' ; '));

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
