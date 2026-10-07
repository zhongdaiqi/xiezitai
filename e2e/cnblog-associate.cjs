/* 后台「博客园」面板：关联账号 / 浏览 / 单篇导入 / 整站导入进度 —— 端到端验证
 *
 * 方案：脚本内起一个本地 mock 博客园 MetaWeblog XML-RPC 服务器（127.0.0.1 随机端口）：
 *   - blogger.getUsersBlogs（校验密钥，返回 blogid）
 *   - metaWeblog.getRecentPosts（3 篇）
 *   - metaWeblog.getPost（单篇：正文含 mock 主机图片 + 外站图片）
 *   - /media-e2e.png 图片文件（会被落盘换 /media/；需服务端
 *     xiezitai.cn-media-hosts=127.0.0.1 放行 mock 主机，生产默认只认 *.cnblogs.com）
 *   外站图片用 http://cdn.external-cn-e2e.test/（不可达也无妨 —— 外站保留外链，不下载）。
 *
 * 覆盖：
 *   ① 「博客园」tab 存在，进面板账号列表为空态
 *   ② 关联账号 → 表格出现该账号；接口列表永不含密钥明文
 *   ③ 浏览文章 → 3 篇渲染，分页「共 3 篇」；发布时间/冲突选择器存在
 *   ④ 单篇导入 → 导入成功；图片落盘 /media/、外站图保留外链、标签导入
 *   ④b 发布时间选「当前时间」→ 导入第二篇发布时间=今天
 *   ⑤ 重复导入 → 提示跳过；「更新」模式复用原文章 id
 *   ⑥ 整站导入 → 进度区出现并最终「整站导入完成：成功 1，跳过 2」
 *   ⑦ 全程 0 JS 错误、0 意外 HTTP>=400
 * 收尾：删掉导入的文章与关联账号。
 */
const { chromium } = require('playwright');
const http = require('http');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };
const CN_KEY = 'e2e-cn-key-0123456789abcdef';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');

/* ---------- XML-RPC 工具 ---------- */
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const vStr = s => '<value><string>' + esc(s) + '</string></value>';
const rpcResp = inner => '<?xml version="1.0"?><methodResponse><params><param>' + inner + '</param></params></methodResponse>';
const rpcFault = msg => '<?xml version="1.0"?><methodResponse><fault><value><struct>'
  + '<member><name>faultCode</name><value><int>1</int></value></member>'
  + '<member><name>faultString</name>' + vStr(msg) + '</member></struct></value></fault></methodResponse>';

function cnPostXml(p) {
  return '<value><struct>'
    + '<member><name>postid</name><value><int>' + p.id + '</int></value></member>'
    + '<member><name>title</name>' + vStr(p.title) + '</member>'
    + '<member><name>description</name>' + vStr(p.description) + '</member>'
    + '<member><name>mt_text_more</name>' + vStr('') + '</member>'
    + '<member><name>mt_excerpt</name>' + vStr(p.excerpt) + '</member>'
    + '<member><name>categories</name><value><array><data>'
    + p.categories.map(c => vStr(c)).join('')
    + '</data></array></value></member>'
    + '<member><name>dateCreated</name><value><dateTime.iso8601>' + p.date + '</dateTime.iso8601></value></member>'
    + '<member><name>link</name>' + vStr(p.link) + '</member>'
    + '</struct></value>';
}

/* ---------- mock 博客园 MetaWeblog 服务器 ---------- */
function startMockCn() {
  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      // 图片文件：博客园自身资源会被下载落盘（需服务端 cn-media-hosts 放行 127.0.0.1）
      if (req.url === '/media-e2e.png') {
        res.setHeader('Content-Type', 'image/png');
        return res.end(PNG_1X1);
      }
      res.statusCode = 404;
      return res.end('not found');
    }
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      const m = /<methodName>([\w.]+)<\/methodName>/.exec(body);
      const method = m ? m[1] : '';
      let resp;
      if (!body.includes(CN_KEY)) {
        resp = rpcFault('密钥错误');
      } else if (method === 'blogger.getUsersBlogs') {
        resp = rpcResp('<value><array><data><value><struct>'
          + '<member><name>blogid</name>' + vStr('879366') + '</member>'
          + '<member><name>url</name>' + vStr('http://127.0.0.1/') + '</member>'
          + '<member><name>blogName</name>' + vStr('E2E Mock CN') + '</member>'
          + '</struct></value></data></array></value>');
      } else if (method === 'metaWeblog.getRecentPosts') {
        resp = rpcResp('<value><array><data>' + POSTS.map(cnPostXml).join('') + '</data></array></value>');
      } else if (method === 'metaWeblog.getPost') {
        const idm = /<params><param><value><string>(\d+)<\/string>/.exec(body);   // postid 是第一个参数（string 形态）
        const id = idm ? parseInt(idm[1], 10) : 0;
        const p = POSTS.find(x => x.id === id);
        resp = p ? rpcResp(cnPostXml(p)) : rpcFault('post not found: ' + body.slice(0, 80));
      } else {
        resp = rpcFault('unknown method: ' + method);
      }
      res.setHeader('Content-Type', 'text/xml; charset=utf-8');
      res.end(resp);
    });
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

let mockBase = '';
const POSTS = [
  { id: 1, title: 'E2E CN Post 01', excerpt: 'CN 摘要 01',
    description: '<p>正文 1</p><p><img src="IMG_SELF"/></p><p><img src="http://cdn.external-cn-e2e.test/x.png"/></p>',
    categories: ['E2ECnTag'], date: '20260101T08:00:00', link: '' },
  { id: 2, title: 'E2E CN Post 02', excerpt: '', description: '<p>正文 2</p>',
    categories: [], date: '20260101T09:00:00', link: '' },
  { id: 3, title: '中文博客园文章', excerpt: '', description: '<p>中文正文</p>',
    categories: [], date: '20260101T10:00:00', link: '' },
];

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());
  const log = (...a) => console.log(...a);

  const mock = await startMockCn();
  mockBase = 'http://127.0.0.1:' + mock.address().port;
  POSTS.forEach(p => { p.link = mockBase + '/p/' + p.id; p.description = p.description.replace('IMG_SELF', mockBase + '/media-e2e.png'); });
  log('MOCK_CN=' + mockBase);

  const importedIds = [];
  let siteId = null;
  // 清理本测试创建的所有文章（发布人=itbuddy-e2e）：开头清一次防上次运行残留，结尾再清一次
  const cleanupArticles = async () => {
    try {
      return await page.evaluate(async () => {
        const r = await fetch('/api/admin/articles?size=200', { headers: { Authorization: 'Bearer ' + token } });
        const data = await r.json();
        let ok = 0;
        for (const a of (data.content || [])) {
          if (a.author === 'itbuddy-e2e' || /e2e-cn-post-|^cnblog-\d+$/.test(a.slug || '')) {
            const dr = await fetch('/api/admin/articles/' + a.id, {
              method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
            if (dr.ok) ok++;
          }
        }
        return ok;
      });
    } catch (e) { return -1; }
  };
  await cleanupArticles();
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
    await page.click('.tab[data-p="cnblog"]');
    await page.waitForSelector('#p-cnblog.active', { timeout: 8000 });
    await page.waitForFunction(() => !!document.querySelector('#cnlist tbody tr'), { timeout: 10000 });
    const emptyTip = await page.textContent('#cnlist tbody');
    check('① 进面板账号列表为空态提示', emptyTip.includes('还没有关联'), emptyTip.trim().slice(0, 30));

    // ---------- ② 关联账号 ----------
    await page.fill('#cn-url', mockBase + '/metaweblog/itbuddy-e2e');
    await page.fill('#cn-user', 'itbuddy-e2e');
    await page.fill('#cn-token', CN_KEY);
    await page.getByRole('button', { name: '关联账号', exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelectorAll('#cnlist tbody tr').length > 0
      && !document.querySelector('#cnlist tbody').textContent.includes('还没有关联'),
      { timeout: 15000 });
    const rowText = await page.textContent('#cnlist tbody tr');
    check('② 关联后账号出现在列表（含用户名）', rowText.includes('itbuddy-e2e'), rowText.slice(0, 60));
    const sitesJson = await page.evaluate(async () => {
      const r = await fetch('/api/admin/cnblogs/sites', { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    });
    check('② 账号列表不回显密钥明文字段',
      Array.isArray(sitesJson) && sitesJson.every(s => !('appKey' in s) && !('token' in s)),
      JSON.stringify(sitesJson).slice(0, 140));
    check('② hasToken=true 且不涉明文', sitesJson.length === 1 && sitesJson[0].hasToken === true);
    check('② 列表不含密钥字符串', !JSON.stringify(sitesJson).includes(CN_KEY));
    siteId = sitesJson.length ? sitesJson[0].id : null;

    // ---------- ③ 浏览文章 ----------
    await page.getByRole('button', { name: '浏览文章', exact: true }).click();
    await page.waitForFunction(() =>
      document.querySelectorAll('#cnposts tbody tr').length > 0, { timeout: 15000 });
    const pagerInfo = await page.textContent('#cnposts-pager .pinfo');
    check('③ 浏览到 mock 账号文章（共 3 篇）', /共 3 篇/.test(pagerInfo), pagerInfo.trim());
    check('③ 每行有「导入」按钮', await page.evaluate(() =>
      document.querySelectorAll('#cnposts tbody button').length === 3));
    check('③ 发布时间选择器存在且默认「博客园原发布时间」', await page.evaluate(() => {
      const s = document.getElementById('cn-date-mode');
      return !!s && s.value === 'wp' && s.options.length === 2;
    }));
    check('③ 冲突选择器存在且默认「跳过」', await page.evaluate(() => {
      const s = document.getElementById('cn-conflict');
      return !!s && s.value === 'skip' && s.options.length === 2;
    }));
    await page.screenshot({ path: OUT + '/50-cn-browse.png', fullPage: true });

    // ---------- ④ 单篇导入 ----------
    const importRespPromise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#cnposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const importResp = await importRespPromise;
    const importData = await importResp.json();
    check('④ 导入请求体默认带 useWpDate=true',
      importResp.request().postDataJSON() && importResp.request().postDataJSON().useWpDate === true,
      importResp.request().postData());
    check('④ 单篇导入接口 imported=true', importData.imported === true, JSON.stringify(importData).slice(0, 140));
    if (importData.articleId) importedIds.push(importData.articleId);
    await page.waitForFunction(() =>
      document.getElementById('toast') && document.getElementById('toast').textContent.includes('导入成功'),
      { timeout: 8000 });
    check('④ 提示「导入成功」', true);

    const art = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, importData.articleId);
    check('④ 发布人=博客园用户名', art.author === 'itbuddy-e2e', art.author);
    check('④ mock 主机图片已落盘为 /media/', art.content.includes('/media/'), (art.content.match(/\/media\/[\w.]+/g) || []).join(','));
    check('④ 正文无 mock 图片 URL 残留', !art.content.includes('/media-e2e.png'));
    check('④ 外站图片保留外链', art.content.includes('http://cdn.external-cn-e2e.test/x.png'));
    check('④ 标签来自博客园分类', (art.tags || '').includes('E2ECnTag'), art.tags);
    check('④ 摘要来自 mt_excerpt', (art.summary || '') === 'CN 摘要 01', art.summary);
    check('④ 发布时间沿用博客园原时间', (art.publishedAt || '').startsWith('2026-01-01T08:00'), art.publishedAt);

    // ---------- ④b 发布时间选「当前时间」导入第二篇 ----------
    await page.selectOption('#cn-date-mode', 'now');
    const impNowPromise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#cnposts tbody tr').nth(1).getByRole('button', { name: '导入', exact: true }).click();
    const impNow = await (await impNowPromise).json();
    check('④b 切换「当前时间」后导入成功', impNow.imported === true, JSON.stringify(impNow).slice(0, 140));
    if (impNow.articleId) importedIds.push(impNow.articleId);
    const artNow = await page.evaluate(async id => {
      const r = await fetch('/api/admin/articles/' + id, { headers: { Authorization: 'Bearer ' + token } });
      return await r.json();
    }, impNow.articleId);
    const today = new Date();
    const todayStr = today.getFullYear() + '-'
      + String(today.getMonth() + 1).padStart(2, '0') + '-'
      + String(today.getDate()).padStart(2, '0');     // 本地时区（UTC 会差一天）
    check('④b useWpDate=false 时发布时间=今天',
      (artNow.publishedAt || '').startsWith(todayStr) && !(artNow.publishedAt || '').startsWith('2026-01-01'),
      artNow.publishedAt);
    await page.selectOption('#cn-date-mode', 'wp');

    // ---------- ⑤ 重复导入 → 跳过；更新模式复用 id ----------
    const againPromise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#cnposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const againData = await (await againPromise).json();
    check('⑤ 重复导入 imported=false（跳过）', againData.imported === false, (againData.message || '').slice(0, 60));

    await page.selectOption('#cn-conflict', 'update');
    const updPromise = page.waitForResponse(
      r => r.url().includes('/import') && !r.url().includes('import-all') && r.request().method() === 'POST',
      { timeout: 60000 });
    await page.locator('#cnposts tbody tr').first().getByRole('button', { name: '导入', exact: true }).click();
    const updData = await (await updPromise).json();
    check('⑤b 更新模式 imported=true 且 updated=true', updData.imported === true && updData.updated === true,
      JSON.stringify(updData).slice(0, 100));
    check('⑤b 更新模式复用原文章 id（不新建）',
      updData.articleId === importData.articleId, updData.articleId + ' vs ' + importData.articleId);
    await page.selectOption('#cn-conflict', 'skip');

    // ---------- ⑥ 整站导入 → 进度到 DONE ----------
    await page.getByRole('button', { name: '整站导入', exact: true }).click();
    await page.waitForFunction(() =>
      document.getElementById('cn-progress') && document.getElementById('cn-progress').style.display !== 'none',
      { timeout: 8000 });
    const done = await page.waitForFunction(() => {
      const el = document.getElementById('cn-progress-log');
      return el && el.textContent.includes('整站导入完成') ? el.textContent : null;
    }, { timeout: 60000 });
    const doneLine = done.jsonValue().then ? await done.jsonValue() : done;
    check('⑥ 进度日志出现「整站导入完成」', true);
    // 3 篇里 2 篇已导入（跳过 2），第 3 篇新导入
    check('⑥ 成功 1 篇、跳过 2 篇', /成功 1，更新 0，跳过 2/.test(doneLine), doneLine.trim().split('\n').pop());
    await page.waitForTimeout(500);
    await page.screenshot({ path: OUT + '/51-cn-progress-done.png', fullPage: true });

    // 中文标题文章 slug 稳定（cnblog-3），详情页可打开
    const artCn = await page.evaluate(async slug => {
      const r = await fetch('/article/' + encodeURIComponent(slug), { redirect: 'follow' });
      const html = await r.text();
      return { status: r.status, hasTitle: html.includes('中文博客园文章') };
    }, 'cnblog-3');
    check('⑥b 纯中文标题文章 slug=cnblog-3 且详情页 200',
      artCn.status === 200 && artCn.hasTitle, JSON.stringify(artCn).slice(0, 120));

    // ---------- ⑦ 全程 0 JS 错误、0 意外 HTTP>=400 ----------
    const realErrors = errors.filter(e => !e.includes('favicon'));
    const realBad = httpBad.filter(u => !u.includes('/favicon'));
    check('⑦ 无 JS 错误', realErrors.length === 0, realErrors.join(' | ').slice(0, 120));
    check('⑦ 无意外 HTTP>=400', realBad.length === 0, realBad.join(' | ').slice(0, 120));
  } finally {
    // ---------- 收尾 ----------
    try {
      const del = await cleanupArticles();
      log('CLEANUP_ARTICLES=' + del);
      if (siteId) {
        const r = await page.evaluate(async sid =>
          (await fetch('/api/admin/cnblogs/sites/' + sid, {
            method: 'DELETE', headers: { Authorization: 'Bearer ' + token } })).status, siteId);
        log('CLEANUP_SITE=' + r);
      }
    } catch (e) { log('CLEANUP_FAIL=' + e.message); }
    await browser.close();
    mock.close();
    const failed = checks.filter(c => !c.ok);
    console.log(failed.length ? 'FAILED=' + failed.length : 'ALL_PASS=' + checks.length);
    process.exit(failed.length ? 1 : 0);
  }
})();
