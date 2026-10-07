/* 后台「WordPress」面板：关联站点 / 浏览 / 单篇导入 / 整站导入进度 —— 端到端验证
 *
 * 方案：脚本内起一个本地 mock WP REST 站点（127.0.0.1 随机端口）：
 *   - /wp-json/ 根（连通性探测）
 *   - /wp-json/wp/v2/posts 列表（12 篇，分页）
 *   - /wp-json/wp/v2/posts/{id} 单篇（正文含站点自身图片 + 外站图片）
 *   - /wp-content/uploads/e2e.png 站点自身图片（会被落盘换 /media/）
 *   外站图片用 http://cdn.external-wp-e2e.test/（不可达也无妨 —— 规则是外站保留外链，不下载）。
 *
 * 覆盖：
 *   ① 「WordPress」tab 存在，进面板站点列表为空态
 *   ② 关联站点（只填网址、匿名）→ 表格出现该站点；接口返回/列表永不含 token 明文（本例无 token）
 *   ③ 浏览文章 → mock 站点 12 篇渲染，分页「共 12 篇」
 *   ④ 单篇导入 → toast 导入成功；正文图片被本地化为 /media/、外站图保留外链、无 wp-content 残留
 *   ⑤ 重复导入 → 提示跳过（不建重复文章）
 *   ⑥ 整站导入 → 进度区出现并最终显示「整站导入完成：成功 X 跳过 Y」
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
const TOTAL_POSTS = 12;
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
      for (let i = 1; i <= TOTAL_POSTS; i++) {
        all.push({ id: i, title: { rendered: 'E2E WP Post ' + String(i).padStart(2, '0') },
          // 第 12 篇用 WP 风格的 %xx 中文 slug（WP 对中文标题就这么生成），验证前台路由多候选匹配
          slug: i === TOTAL_POSTS ? 'e2e-wp-' + encodeURIComponent('文章十二') : 'e2e-wp-' + i,
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
      return json({
        id, title: { rendered: 'E2E WP Post ' + String(id).padStart(2, '0') },
        slug: id === TOTAL_POSTS ? 'e2e-wp-' + encodeURIComponent('文章十二') : 'e2e-wp-' + id,
        status: 'publish', date_gmt: '2026-01-01T08:00:00',
        content: { rendered: '<p>正文 ' + id + '</p>'
          + '<p><img src="' + 'http://127.0.0.1:' + server.address().port + '/wp-content/uploads/e2e.png"/></p>'
          + '<p><img src="http://cdn.external-wp-e2e.test/x.png"/></p>' },
        excerpt: { rendered: '<p>摘要 ' + id + '</p>' },
        _embedded: { 'wp:term': [ [], [{ name: 'E2ETag', taxonomy: 'post_tag' }] ] }
      });
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
    check('③ 浏览到 mock 站点文章（共 12 篇）', /共 12 篇/.test(pagerInfo), pagerInfo.trim());
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

    // ---------- ⑥ 整站导入 + 进度 ----------
    await page.getByRole('button', { name: '整站导入', exact: true }).click();   // confirm 已自动 accept
    await page.waitForSelector('#wp-progress', { state: 'visible', timeout: 10000 });
    await page.waitForFunction(() =>
      document.getElementById('wp-progress-log').textContent.includes('整站导入完成'),
      { timeout: 60000 });
    const logText = await page.textContent('#wp-progress-log');
    check('⑥ 进度日志出现「整站导入完成」', logText.includes('整站导入完成'), logText.split('\n').pop());
    const doneLine = logText.split('\n').find(l => l.includes('整站导入完成')) || '';
    // 12 篇里 2 篇已导入（跳过 2），其余 10 篇成功
    check('⑥ 成功 10 篇、跳过 2 篇', /成功 10，更新 0，跳过 2/.test(doneLine), doneLine);
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
      // 整站导入建的那 11 篇按 slug 前缀清
      try {
        const r = await fetch(BASE + '/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
        const d = await r.json();
        for (const a of d.content.filter(x => x.slug.startsWith('e2e-wp-'))) {
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
