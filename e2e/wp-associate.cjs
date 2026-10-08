/* 后台「WordPress」面板：关联站点 / 浏览 / 单篇导入 / 整站导入进度 —— 端到端验证
 *
 * 方案：脚本内起一个本地 mock WP REST 站点（127.0.0.1 随机端口）：
 *   - /wp-json/ 根（连通性探测）
 *   - /wp-json/wp/v2/posts 列表（14 篇，分页）
 *   - /wp-json/wp/v2/posts/{id} 单篇（content.raw = Markdown 原文，content.rendered = 渲染后的 HTML，
 *     两者各带 RAW-MD / RENDERED-HTML 标记 —— 模拟装了 Markdown 插件的站点）
 *   - /wp-json/wp/v2/posts/13：作者把 Markdown 粘进古腾堡的形态 —— 每行一个 <p>、
 *     行首 # 被吃成 <strong>、** 与 ``` 与 ![..](..) 原样留成字面文本（验证「还原式」转换）
 *   - /wp-json/wp/v2/posts/14：slug 是 WP 原样 %xx 字面串（老数据），本地已有一条同 slug 的文章
 *   - /wp-content/uploads/e2e.png 站点自身图片（会被落盘换 /media/）
 *   外站图片用 http://cdn.external-wp-e2e.test/（不可达也无妨 —— 规则是外站保留外链，不下载）。
 *
 * 覆盖：
 *   ① 「WordPress」tab 存在，进面板站点列表为空态
 *   ② 关联站点（只填网址、匿名）→ 表格出现该站点；接口返回/列表永不含 token 明文（本例无 token）
 *   ③ 浏览文章 → mock 站点 14 篇渲染，分页「共 14 篇」
 *   ④ 单篇导入 → toast 导入成功；正文取 content.raw（Markdown 原文，不是渲染后的 HTML）；
 *      正文图片被本地化为 /media/、外站图保留外链、无 wp-content 残留
 *   ⑤ 重复导入 → 提示跳过（不建重复文章）
 *   ⑤c 老数据：本地已有 slug 为 %xx 字面串的文章 → skip 判「已存在」不重复建，
 *      update 复用同一条并把 slug 归一成真中文；后台列表链接不得出现双重编码 %25（会 400）
 *   ⑥ 整站导入 → 进度区出现并最终显示「整站导入完成：成功 X 跳过 Y」
 *   ⑥c 古腾堡粘 Markdown 的那篇 → 标题/粗体/围栏/字面图片/表格都还原成真 Markdown
 *   ⑦ 全程 0 JS 错误、0 意外 HTTP>=400
 * 收尾：删掉导入的文章与关联站点。
 */
const { chromium } = require('playwright');
const http = require('http');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];
const TOTAL_POSTS = 13;
/** mock 站点的文章总数（13 篇常规 + 1 篇老数据样板） */
const MOCK_POSTS = TOTAL_POSTS + 1;
/** 中文 %xx slug 的样板文章（第 12 篇，验证前台路由多候选匹配） */
const CN_POST = 12;
/** 「Markdown 粘进古腾堡」的样板文章 */
const GUTENBERG_POST = 13;
/** 老数据样板：WP 原样 %xx 字面串 slug（本地已存在同 slug 文章） */
const LEGACY_POST = 14;
const LEGACY_SLUG = 'e2e-legacy-%e4%b8%ad%e6%96%87';          // 归一后应为 e2e-legacy-中文
const LEGACY_TITLE = 'E2E WP Legacy Pct';
/** WP 侧的 slug（第 14 篇故意用「%xx 字面串」这种老形态） */
const slugFor = id => id === CN_POST ? 'e2e-wp-' + encodeURIComponent('文章十二')
  : id === LEGACY_POST ? LEGACY_SLUG : 'e2e-wp-' + id;
const titleFor = id => id === LEGACY_POST ? LEGACY_TITLE : 'E2E WP Post ' + String(id).padStart(2, '0');
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');

/* ---------- mock WP 站点 ---------- */
function startMockWp() {
  const server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    const json = obj => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(obj)); };
    if (u.pathname === '/wp-json/') return json({ name: 'E2E Mock WP', description: 'mock' });
    if (u.pathname === '/wp-content/uploads/e2e.png') {
      res.setHeader('Content-Type', 'image/png');
      return res.end(PNG_1X1);
    }
    if (u.pathname === '/wp-json/wp/v2/posts') {
      const page = parseInt(u.searchParams.get('page') || '1', 10);
      const per = parseInt(u.searchParams.get('per_page') || '10', 10);
      const all = [];
      // 第 12 篇用 WP 风格的 %xx 中文 slug（WP 对中文标题就这么生成），验证前台路由多候选匹配；
      // 第 14 篇是「老数据」：slug 为 WP 原样 %xx 字面串，本地已有一条同 slug 的文章
      for (let i = 1; i <= MOCK_POSTS; i++) {
        all.push({ id: i, title: { rendered: titleFor(i) }, slug: slugFor(i),
          date: '2026-01-01T08:00:00', status: 'publish', link: 'http://x/?p=' + i });
      }
      const totalPages = Math.ceil(all.length / per);
      const slice = all.slice((page - 1) * per, page * per);
      res.setHeader('X-WP-Total', String(all.length));
      res.setHeader('X-WP-TotalPages', String(totalPages));
      res.setHeader('Content-Type', 'application/json');
      return res.end(JSON.stringify(page > totalPages ? [] : slice));
    }
    const m = /^\/wp-json\/wp\/v2\/posts\/(\d+)$/.exec(u.pathname);
    if (m) {
      const id = parseInt(m[1], 10);
      const port = server.address().port;
      const meta = {
        id, title: { rendered: titleFor(id) },
        slug: slugFor(id), status: 'publish', date_gmt: '2026-01-01T08:00:00',
        excerpt: { rendered: '<p>摘要 ' + id + '</p>' },
        _embedded: { 'wp:term': [ [], [{ name: 'E2ETag', taxonomy: 'post_tag' }] ] }
      };
      if (id === GUTENBERG_POST) {
        // 作者把 Markdown 粘进古腾堡后的真实形态：每行一个 <p>，行首 # 被吃成 <strong>，
        // **、```、![..](..)、|表格| 原样留成字面文本。raw 与 rendered 都是这个样子。
        const g = [
          '<p class="wp-block-paragraph"><strong># 标题一</strong></p>',
          '<p class="wp-block-paragraph">正文：<strong>**要点**</strong>。</p>',
          '<p class="wp-block-paragraph">1. <strong>**甲**</strong>：说明</p>',
          '<p class="wp-block-paragraph">2. <strong>**乙**</strong>：说明</p>',
          '<p class="wp-block-paragraph">```python</p>',
          '<p class="wp-block-paragraph"># 注释别当标题</p>',
          '<p class="wp-block-paragraph">&nbsp; &nbsp; x = 1</p>',
          '<p class="wp-block-paragraph">```</p>',
          '<p class="wp-block-paragraph">![外站图](http://cdn.external-wp-e2e.test/md.png)</p>',
          '<p class="wp-block-paragraph"><img src="http://127.0.0.1:' + port + '/wp-content/uploads/e2e.png"/></p>',
          '<p class="wp-block-paragraph">| 列A | 列B |</p>',
          '<p class="wp-block-paragraph">|&#8212;&#8212;&#8212;|&#8212;&#8212;&#8212;|</p>',
          '<p class="wp-block-paragraph">| <strong>**甲**</strong> | 1 |</p>'
        ].join('\n');
        return json(Object.assign(meta, { content: { raw: g, rendered: g } }));
      }
      // 模拟装了 Markdown 插件的站点：content.raw 是编辑器里的 Markdown 原文，
      // content.rendered 是插件渲染后的 HTML。两段内容各带一个独有标记，便于断言导入取了哪一份。
      const raw = '正文 ' + id + ' RAW-MD\n\n'
        + '![图](http://127.0.0.1:' + port + '/wp-content/uploads/e2e.png)\n\n'
        + '![](http://cdn.external-wp-e2e.test/x.png)\n';
      const rendered = '<p>正文 ' + id + ' RENDERED-HTML</p>'
        + '<h2>小标题</h2>'
        + '<p><img src="http://127.0.0.1:' + port + '/wp-content/uploads/e2e.png"/></p>'
        + '<p><img src="http://cdn.external-wp-e2e.test/x.png"/></p>';
      return json(Object.assign(meta, { content: { raw, rendered } }));
    }
    res.statusCode = 404; res.end('{}');
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());
  const log = (...a) => console.log(...a);

  const mock = await startMockWp();
  const wpBase = 'http://127.0.0.1:' + mock.address().port;
  log('MOCK_WP=' + wpBase);

  const importedIds = [];
  let siteId = null;
  let legacyId = null;   // 预置的「%xx 老文章」id
  try {
    // ---------- 登录 ----------
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    if (!(await page.isVisible('#app').catch(() => false))) {
      await page.fill('#luser', ADMIN.u);
      await page.fill('#lpass', ADMIN.p);
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });

    // ---------- ① 进面板：tab 存在、空态 ----------
    await page.click('.tab[data-p="wp"]');
    await page.waitForSelector('#p-wp.active', { timeout: 8000 });
    await page.waitForFunction(() =>
      !!document.querySelector('#wplist tbody tr'), { timeout: 10000 });
    const emptyTip = await page.textContent('#wplist tbody');
    check('① 进面板站点列表为空态提示', emptyTip.includes('还没有关联'), emptyTip.trim().slice(0, 30));

    // ---------- ② 关联站点 ----------
    await page.fill('#wp-url', wpBase);
    await page.getByRole('button', { name: '关联站点', exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelectorAll('#wplist tbody tr').length > 0
      && !document.querySelector('#wplist tbody').textContent.includes('还没有关联'),
      { timeout: 15000 });
    const rowText = await page.textContent('#wplist tbody tr');
    check('② 关联后站点出现在列表（含网址）', rowText.includes(wpBase), rowText.slice(0, 60));
    // token 脱敏：接口响应里不允许出现 token 字段明文（本例未填 token，验证 hasToken=false 且无 token 键）
    const sitesJson = await page.evaluate(async () => {
      const r = await fetch('/api/admin/wp/sites', { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    });
    check('② 站点列表不回显 token 明文字段',
      Array.isArray(sitesJson) && sitesJson.every(s => !('apiToken' in s) && !('token' in s)),
      JSON.stringify(sitesJson).slice(0, 120));
    siteId = sitesJson.length ? sitesJson[0].id : null;

    // ---------- ③ 浏览文章 ----------
    await page.getByRole('button', { name: '浏览文章', exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelectorAll('#wpposts tbody tr').length > 0, { timeout: 15000 });
    const pagerInfo = await page.textContent('#wpposts-pager .pinfo');
    check('③ 浏览到 mock 站点文章（共 14 篇）', /共 14 篇/.test(pagerInfo), pagerInfo.trim());
    check('③ 每行有「导入」按钮', await page.evaluate(() =>
      document.querySelectorAll('#wpposts tbody button').length === 10));
    check('③ 发布时间选择器存在且默认「WP 原发布时间」', await page.evaluate(() => {
      const s = document.getElementById('wp-date-mode');
      return !!s && s.value === 'wp' && s.options.length === 2;
    }));
    check('③ 冲突选择器存在且默认「跳过」', await page.evaluate(() => {
      const s = document.getElementById('wp-conflict');
      return !!s && s.value === 'skip' && s.options.length === 2;
    }));
    check('③ 正文格式选择器存在且默认「自动识别」', await page.evaluate(() => {
      const s = document.getElementById('wp-content-mode');
      return !!s && s.value === 'auto' && s.options.length === 3;
    }));
    await page.screenshot({ path: OUT + '/40-wp-browse.png', fullPage: true });

    // ---------- ④ 单篇导入 ----------
    const importRespPromise = page.waitForResponse(
      r => r.url().includes('/import') && r.request().method() === 'POST', { timeout: 60000 });
    await page.locator('#wpposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const importResp = await importRespPromise;
    const importData = await importResp.json();
    check('④ 导入请求体默认带 useWpDate=true',
      importResp.request().postDataJSON() && importResp.request().postDataJSON().useWpDate === true,
      importResp.request().postData());
    check('④ 导入请求体默认带 contentMode=auto',
      importResp.request().postDataJSON() && importResp.request().postDataJSON().contentMode === 'auto',
      importResp.request().postData());
    check('④ 单篇导入接口 imported=true', importData.imported === true, JSON.stringify(importData).slice(0, 120));
    if (importData.articleId) importedIds.push(importData.articleId);
    await page.waitForFunction(() =>
      document.getElementById('toast') && document.getElementById('toast').textContent.includes('导入成功'),
      { timeout: 8000 });
    check('④ 提示「导入成功」', true);

    // 导入结果核验：正文媒体本地化（走 Node 侧 API，不污染 console）
    const art = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, importData.articleId);
    check('④ 站点自身图片已落盘为 /media/', art.content.includes('/media/'), (art.content.match(/\/media\/[\w.]+/g) || []).join(','));
    check('④ 正文无 wp-content 残留', !art.content.includes('wp-content'));
    check('④ 外站图片保留外链', art.content.includes('http://cdn.external-wp-e2e.test/x.png'));
    check('④ 标签来自 WP 分类/标签', (art.tags || '').includes('E2ETag'), art.tags);
    // Markdown 插件场景：auto 模式应取 content.raw（Markdown 原文），而不是渲染后的 HTML
    check('④ 正文取 WP 原文（Markdown），不是渲染后的 HTML',
      art.content.includes('RAW-MD') && !art.content.includes('RENDERED-HTML')
      && !art.content.includes('<p>') && art.content.includes('![图](/media/'),
      art.content.slice(0, 120));

    // ---------- ④b 发布时间选「当前时间」导入第二篇 ----------
    await page.selectOption('#wp-date-mode', 'now');
    const impNowPromise = page.waitForResponse(
      r => r.url().includes('/import') && r.request().method() === 'POST', { timeout: 60000 });
    await page.locator('#wpposts tbody tr').nth(1).getByRole('button', { name: '导入', exact: true }).click();
    const impNow = await (await impNowPromise).json();
    check('④b 切换「当前时间」后导入成功', impNow.imported === true, JSON.stringify(impNow).slice(0, 120));
    if (impNow.articleId) importedIds.push(impNow.articleId);
    const artNow = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, impNow.articleId);
    const d = new Date();
    const today = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');  // 本地时区日期（toISOString 是 UTC，会差一天）
    check('④b useWpDate=false 时发布时间=今天（非 WP 的 2026-01-01）',
      (artNow.publishedAt || '').startsWith(today) && !artNow.publishedAt.startsWith('2026-01-01'),
      artNow.publishedAt);
    await page.selectOption('#wp-date-mode', 'wp');   // 还原默认，避免影响后续整站导入

    // ---------- ④c 正文格式选「强制转 Markdown」：忽略原文、改用渲染结果，HTML 应被还原成 Markdown ----------
    await page.selectOption('#wp-content-mode', 'html2md');
    const impConvPromise = page.waitForResponse(
      r => r.url().includes('/import') && r.request().method() === 'POST', { timeout: 60000 });
    await page.locator('#wpposts tbody tr').nth(2).getByRole('button', { name: '导入', exact: true }).click();
    const impConv = await (await impConvPromise).json();
    check('④c 强制转 Markdown 导入成功', impConv.imported === true, JSON.stringify(impConv).slice(0, 120));
    if (impConv.articleId) importedIds.push(impConv.articleId);
    const artConv = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, impConv.articleId);
    check('④c 用渲染结果并还原成 Markdown（标题/图片/无 HTML 残留）',
      artConv.content.includes('RENDERED-HTML') && artConv.content.includes('## 小标题')
      && artConv.content.includes('![](') && !artConv.content.includes('<p>') && !artConv.content.includes('<h2>'),
      artConv.content.slice(0, 160));
    await page.selectOption('#wp-content-mode', 'auto');   // 还原默认，避免影响后续整站导入

    // ---------- ⑤ 重复导入 → 跳过 ----------
    const againPromise = page.waitForResponse(
      r => r.url().includes('/import') && r.request().method() === 'POST', { timeout: 60000 });
    await page.locator('#wpposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const againData = await (await againPromise).json();
    check('⑤ 重复导入 imported=false（跳过）', againData.imported === false, (againData.message || '').slice(0, 60));

    // ---------- ⑤b 冲突改「更新」再导入：复用原文章 id，不新建 ----------
    await page.selectOption('#wp-conflict', 'update');
    const updPromise = page.waitForResponse(
      r => r.url().includes('/import') && r.request().method() === 'POST', { timeout: 60000 });
    await page.locator('#wpposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const updData = await (await updPromise).json();
    check('⑤b 更新模式 imported=true 且 updated=true',
      updData.imported === true && updData.updated === true, JSON.stringify(updData).slice(0, 120));
    check('⑤b 更新模式复用原文章 id（不新建）',
      updData.articleId === importData.articleId, updData.articleId + ' vs ' + importData.articleId);
    await page.selectOption('#wp-conflict', 'skip');   // 还原默认，避免影响整站导入

    // ---------- ⑤c 老数据：库里 slug 仍是 WP 原样 %xx 字面串 ----------
    //     ① 再导入同一篇不能被判成「新文章」而多建一份；
    //     ② 后台文章列表点出来的链接不能是双重编码 %25（会被安全防火墙 400 拒掉）；
    //     ③ update 模式要复用同一条并把 slug 归一成真中文。
    const legacyPrep = await page.evaluate(async pre => {
      const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };
      const r0 = await fetch('/api/admin/articles', {
        method: 'POST', headers: H,
        body: JSON.stringify({ title: pre.title, slug: pre.slug, content: 'legacy body v1',
                               summary: 'legacy', status: 'PUBLISHED' })
      });
      const created = await r0.json();
      const r1 = await fetch('/api/admin/wp/sites/' + pre.siteId + '/import', {
        method: 'POST', headers: H, body: JSON.stringify({ postId: pre.postId })
      });
      const skip = await r1.json();
      return { created, skip };
    }, { title: LEGACY_TITLE, slug: LEGACY_SLUG, siteId, postId: LEGACY_POST });
    legacyId = legacyPrep.created.id;
    check('⑤c 预置 %xx 老文章成功', !!legacyId, JSON.stringify(legacyPrep.created).slice(0, 80));
    check('⑤c skip 模式判定为已存在（不建重复文章）',
      legacyPrep.skip.imported === false && legacyPrep.skip.reason === 'exists',
      JSON.stringify(legacyPrep.skip).slice(0, 140));

    // 后台文章列表：切 tab 不会自动重拉（只有 wp / cnblog 面板会），手动刷一次再找那行
    await page.click('.tab[data-p="articles"]');
    await page.evaluate(() => { if (typeof loadArticles === 'function') loadArticles(0); });
    await page.waitForFunction(title => {
      const tr = [...document.querySelectorAll('#alist tbody tr')]
        .find(r => r.textContent.includes(title));
      return !!tr;
    }, LEGACY_TITLE, { timeout: 15000 });
    const legacyHref = await page.evaluate(title => {
      const tr = [...document.querySelectorAll('#alist tbody tr')]
        .find(r => r.textContent.includes(title));
      const a = tr && tr.querySelector('a');
      return a ? a.getAttribute('href') : null;
    }, LEGACY_TITLE);
    check('⑤c 后台列表链接不含双重编码 %25', !!legacyHref && !legacyHref.includes('%25'), legacyHref);
    const legacyOpen = await page.evaluate(async href => {
      const r = await fetch(href, { redirect: 'follow' });
      const html = await r.text();
      return { status: r.status, url: r.url, hasTitle: html.includes('E2E WP Legacy Pct') };
    }, legacyHref);
    check('⑤c 该链接能打开详情页（200，非 400/不跳首页）',
      legacyOpen.status === 200 && legacyOpen.hasTitle, JSON.stringify(legacyOpen).slice(0, 140));

    // update 模式再导入：复用同一条 + slug 归一
    const legacyUpd = await page.evaluate(async pre => {
      const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };
      const r = await fetch('/api/admin/wp/sites/' + pre.siteId + '/import', {
        method: 'POST', headers: H, body: JSON.stringify({ postId: pre.postId, onConflict: 'update' })
      });
      return await r.json();
    }, { siteId, postId: LEGACY_POST });
    check('⑤c update 复用老文章 id 且 slug 归一为真中文',
      legacyUpd.updated === true && legacyUpd.articleId === legacyId && legacyUpd.slug === 'e2e-legacy-中文',
      JSON.stringify(legacyUpd).slice(0, 160));
    const legacyCount = await page.evaluate(async pre => {
      const H = { Authorization: 'Bearer ' + token };
      const r = await fetch('/api/admin/articles?size=200', { headers: H });
      const d = await r.json();
      return d.content.filter(a => a.slug.startsWith('e2e-legacy-')).length;
    }, {});
    check('⑤c 全库 e2e-legacy-* 只有 1 篇（更新没建重复）', legacyCount === 1, 'count=' + legacyCount);

    await page.click('.tab[data-p="wp"]');
    await page.waitForSelector('#p-wp.active', { timeout: 8000 });

    // ---------- ⑥ 整站导入 + 进度 ----------
    await page.getByRole('button', { name: '整站导入', exact: true }).click();   // confirm 已自动 accept
    await page.waitForSelector('#wp-progress', { state: 'visible', timeout: 10000 });
    await page.waitForFunction(() =>
      document.getElementById('wp-progress-log').textContent.includes('整站导入完成'),
      { timeout: 60000 });
    const logText = await page.textContent('#wp-progress-log');
    check('⑥ 进度日志出现「整站导入完成」', logText.includes('整站导入完成'), logText.split('\n').pop());
    const doneLine = logText.split('\n').find(l => l.includes('整站导入完成')) || '';
    // 14 篇里 4 篇已存在（④ / ④b / ④c 单篇导入的 3 篇 + ⑤c 的老数据 1 篇）→ 跳过 4，其余 10 篇成功
    check('⑥ 成功 10 篇、跳过 4 篇', /成功 10，更新 0，跳过 4/.test(doneLine), doneLine);
    await page.screenshot({ path: OUT + '/41-wp-progress-done.png', fullPage: true });

    // 清点导入的文章数（slug 前缀 e2e-wp-）
    const count = await page.evaluate(async () => {
      const r = await fetch('/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
      const d = await r.json();
      return d.content.filter(a => a.slug.startsWith('e2e-wp-')).length;
    });
    check('⑥ 本站共 12 篇 e2e-wp-* 文章', count === TOTAL_POSTS, 'count=' + count);

    // ---------- ⑥b 中文 slug 路由：WP 风格 %xx slug 已解码为中文入库，详情页应能打开（不 400/不跳首页） ----------
    const cnSlug = 'e2e-wp-文章十二';   // 导入时 normalizeWpSlug 已把 %E6%96%87... 解码回中文
    const artCn = await page.evaluate(async s => {
      const r = await fetch('/article/' + encodeURIComponent(s), { redirect: 'follow' });
      const html = await r.text();
      return { status: r.status, finalUrl: r.url, hasTitle: html.includes('E2E WP Post 12') };
    }, cnSlug);
    check('⑥b WP 风格中文 slug 详情页 200 且内容正确',
      artCn.status === 200 && artCn.hasTitle && !artCn.finalUrl.endsWith('/'),
      JSON.stringify(artCn).slice(0, 120));

    // ---------- ⑥c 「Markdown 粘进古腾堡」的文章：必须按还原式转换，不能把 Markdown 语法转义掉 ----------
    const artMd = await page.evaluate(async () => {
      const r = await fetch('/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
      const d = await r.json();
      const a = d.content.find(x => x.slug === 'e2e-wp-13');
      return a ? a.content : '';
    });
    check('⑥c 标题/粗体还原（无 **# 标题**、无 HTML 与实体残留）',
      artMd.includes('# 标题一') && artMd.includes('正文：**要点**。')
      && !artMd.includes('**# 标题一**') && !artMd.includes('wp-block-paragraph') && !artMd.includes('&nbsp;'),
      artMd.slice(0, 120));
    check('⑥c 列表项紧凑（1. **甲**：说明\\n2. **乙**：说明）',
      artMd.includes('1. **甲**：说明\n2. **乙**：说明'), artMd.slice(0, 200));
    check('⑥c 围栏变成真代码块（块内 # 注释与缩进保留）',
      artMd.includes('```python\n# 注释别当标题\n    x = 1\n```'), artMd.slice(0, 200));
    check('⑥c 字面 Markdown 图片保留外链、站点图落盘为 /media/',
      artMd.includes('![外站图](http://cdn.external-wp-e2e.test/md.png)') && /!\[\]\(\/media\/[\w.]+\)/.test(artMd),
      artMd.slice(0, 200));
    check('⑥c 表格行连续、分隔行还原为 ---（GFM 表格可渲染）',
      artMd.includes('| 列A | 列B |\n|---|---|\n| **甲** | 1 |'), artMd.slice(0, 200));
    // 详情页真实渲染一遍：标题 / 代码块 / 表格 / 图片都要出现在 HTML 里
    const artPage = await page.evaluate(async () => {
      const r = await fetch('/article/e2e-wp-13');
      const html = await r.text();
      return { status: r.status, html };
    });
    check('⑥c 详情页渲染出 h1 / 代码块 / 表格 / 图片',
      artPage.status === 200 && artPage.html.includes('<h1>标题一</h1>')
      && artPage.html.includes('<pre') && artPage.html.includes('<table>')
      && artPage.html.includes('<img src="/media/'), 'status=' + artPage.status);

    // ---------- ⑦ 控制台干净 ----------
    check('⑦ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑦ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
    // ---------- 清理：删文章 + 删站点 ----------
    try {
      const token = await page.evaluate(() =>
        localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
      for (const id of importedIds) {
        try {
          const r = await fetch(BASE + '/api/admin/articles/' + id,
            { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
          console.log('CLEANUP article id=' + id + ' status=' + r.status);
        } catch (e) { console.log('CLEANUP_FAIL article id=' + id + ' ' + (e && e.message)); }
      }
      // 整站导入建的那批 + ⑤c 预置的 %xx 老文章（归一后是 e2e-legacy-中文），按 slug 前缀清
      try {
        const r = await fetch(BASE + '/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
        const d = await r.json();
        for (const a of d.content.filter(x => x.slug.startsWith('e2e-wp-') || x.slug.startsWith('e2e-legacy-'))) {
          const dr = await fetch(BASE + '/api/admin/articles/' + a.id,
            { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
          console.log('CLEANUP e2e-wp article id=' + a.id + ' status=' + dr.status);
        }
      } catch (e) { console.log('CLEANUP_LIST_FAIL ' + (e && e.message)); }
      if (siteId) {
        const r = await fetch(BASE + '/api/admin/wp/sites/' + siteId,
          { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
        console.log('CLEANUP site id=' + siteId + ' status=' + r.status);
      }
    } catch (e) { console.log('CLEANUP_TOKEN_FAIL ' + (e && e.message)); }
    mock.close();
    await browser.close();
    const failed = checks.filter(c => !c.ok);
    console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
    if (failed.length) { failed.forEach(f => console.log('  FAILED: ' + f.name)); process.exit(1); }
  }
})().catch(e => {
  console.error('SCRIPT_ERROR', e && e.message ? e.message : e);
  errors.slice(0, 8).forEach(x => console.error('  PAGEERR: ' + x));
  httpBad.slice(0, 8).forEach(x => console.error('  HTTPBAD: ' + x));
  process.exit(2);
});
