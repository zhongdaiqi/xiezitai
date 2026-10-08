/* 后台「文章分发」端到端验证（→ WordPress 站点 / 博客园账号）
 *
 * 方案：脚本内起两个本地 mock 上游：
 *   ① 假 WP 站点：/wp-json/ 探测、/users/me 校验凭据、POST /posts 建文章、POST /posts/{id} 改文章、
 *      POST /tags 建标签 —— 所有写请求的请求体都记下来供断言；
 *   ② 假博客园：XML-RPC，blogger.getUsersBlogs / metaWeblog.newPost / editPost / getPost。
 *
 * 覆盖：
 *   ① 文章列表行有「分发」按钮；点开是分发弹窗，目标按「WordPress 站点 / 博客园账号」分组、均为「未分发过」
 *   ② 一次发往两个目标（转载 + Markdown）→ 全部成功；WP 正文媒体已绝对化、尾部带转载链接、
 *      标签同步成 term id；博客园正文是 Markdown 原文且分类带 [Markdown]
 *   ③ 关弹窗后列表行出现「已分发 · 目标名」徽标
 *   ④ 再打开弹窗：目标自动勾上、显示「已分发过 1 次」、处理方式默认「更新之前分发的文章」
 *   ⑤ 选「更新」+ 切回原文分发 → 走对方更新接口、远端 id 不变、正文不再带转载尾注
 *   ⑥ 改成「分发一个新文章」→ 远端 id 换新；只勾一个目标时只发一个
 *   ⑦ 全程 0 JS 错误、0 意外 HTTP>=400
 * 收尾：删掉文章与两个关联目标。
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
const WP_TOKEN = 'abcd efgh ijkl mnop';
const CN_KEY = 'e2e-cn-key-0123456789abcdef';
const SLUG = 'e2e-dist-article';
const TITLE = 'E2E 分发用例文章';

/** 收集请求体（节点 http 是流式的，必须等 end） */
function bodyOf(req) {
  return new Promise(resolve => {
    let raw = '';
    req.on('data', c => { raw += c; });
    req.on('end', () => resolve(raw));
  });
}

/* ---------- 假 WP 站点 ---------- */
function startMockWp() {
  const calls = [];
  let nextId = 900;
  let base = '';
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    const json = obj => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(obj)); };
    if (!String(req.headers['authorization'] || '').startsWith('Basic ')) {
      res.statusCode = 401;
      return json({ code: 'rest_forbidden' });
    }
    if (u.pathname === '/wp-json/') return json({ name: 'E2E Mock WP', description: 'mock' });
    if (u.pathname === '/wp-json/wp/v2/users/me') return json({ name: 'Bob' });
    if (u.pathname === '/wp-json/wp/v2/tags' && req.method === 'POST') {
      const body = JSON.parse(await bodyOf(req));
      calls.push({ kind: 'tag', body });
      res.statusCode = 201;
      return json({ id: 77, name: body.name });
    }
    if (u.pathname === '/wp-json/wp/v2/posts' && req.method === 'POST') {
      const body = JSON.parse(await bodyOf(req));
      nextId++;
      calls.push({ kind: 'create', id: nextId, body });
      res.statusCode = 201;
      return json({ id: nextId, link: base + '/?p=' + nextId });
    }
    const m = /^\/wp-json\/wp\/v2\/posts\/(\d+)$/.exec(u.pathname);
    if (m && req.method === 'POST') {
      const body = JSON.parse(await bodyOf(req));
      calls.push({ kind: 'update', id: Number(m[1]), body });
      return json({ id: Number(m[1]), link: base + '/?p=' + m[1] });
    }
    if (u.pathname === '/wp-json/wp/v2/posts') {
      res.setHeader('X-WP-Total', '0');
      res.setHeader('X-WP-TotalPages', '1');
      return json([]);
    }
    res.statusCode = 404;
    return json({});
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => {
    base = 'http://127.0.0.1:' + server.address().port;
    resolve({ server, calls, base });
  }));
}

/* ---------- 假博客园（XML-RPC） ---------- */
function startMockCn() {
  const calls = [];
  let base = '';
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const str = s => '<value><string>' + esc(s) + '</string></value>';
  const member = (n, v) => '<member><name>' + n + '</name>' + v + '</member>';
  const resp = inner => '<?xml version="1.0"?><methodResponse><params><param>' + inner + '</param></params></methodResponse>';
  const fault = msg => '<?xml version="1.0"?><methodResponse><fault><value><struct>'
    + member('faultCode', '<value><int>1</int></value>') + member('faultString', str(msg))
    + '</struct></value></fault></methodResponse>';
  const postXml = () => '<value><struct>'
    + member('postid', '<value><int>555</int></value>')
    + member('title', str('CN Post'))
    + member('description', str('<p>x</p>'))
    + member('mt_text_more', str(''))
    + member('mt_excerpt', str(''))
    + member('categories', '<value><array><data></data></array></value>')
    + member('dateCreated', '<value><dateTime.iso8601>20260101T00:00:00</dateTime.iso8601></value>')
    + member('link', str(base + '/p/555'))
    + '</struct></value>';

  const server = http.createServer(async (req, res) => {
    const raw = await bodyOf(req);
    const method = (/<methodName>([\w.]+)<\/methodName>/.exec(raw) || [, ''])[1];
    calls.push({ method, raw });
    res.setHeader('Content-Type', 'text/xml');
    if (!raw.includes(CN_KEY)) return res.end(fault('密钥错误'));
    if (method === 'blogger.getUsersBlogs') {
      return res.end(resp('<value><array><data><value><struct>'
        + member('blogid', '<value><string>879366</string></value>')
        + member('url', str(base + '/'))
        + member('blogName', str('E2E MockCN'))
        + '</struct></value></data></array></value>'));
    }
    if (method === 'metaWeblog.newPost') return res.end(resp('<value><string>555</string></value>'));
    if (method === 'metaWeblog.editPost') return res.end(resp('<value><boolean>1</boolean></value>'));
    // getPost 返回**单个 struct**、getRecentPosts 返回 array —— 别搞混，
    // 返回错形态会让「取远端链接」静默降级成空（文章其实已经发出去了，只是列表里没链接）
    if (method === 'metaWeblog.getPost') return res.end(resp(postXml()));
    if (method === 'metaWeblog.getRecentPosts') {
      return res.end(resp('<value><array><data>' + postXml() + '</data></array></value>'));
    }
    return res.end(fault('unknown method: ' + method));
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => {
    base = 'http://127.0.0.1:' + server.address().port;
    resolve({ server, calls, base });
  }));
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());

  const mockWp = await startMockWp();
  const mockCn = await startMockCn();
  console.log('MOCK_WP=' + mockWp.base + '  MOCK_CN=' + mockCn.base);

  let articleId = null, wpSiteId = null, cnSiteId = null;
  const jwtOf = () => page.evaluate(() =>
    localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
  // 行内操作按钮已改成图标按钮（没有文字），按 data-act 这个稳定钩子点
  const clickRowButton = (act) => page.evaluate(([t, a]) => {
    const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
    tr.querySelector('button[data-act="' + a + '"]').click();
  }, [TITLE, act]);

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

    // ---------- 准备：关联两个目标 + 建一篇文章（Node 侧调 API，不污染页面 console） ----------
    const jwt = await jwtOf();
    const api = async (path, body, method = 'POST') => {
      const r = await fetch(BASE + path, {
        method,
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + jwt },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      const t = await r.text();
      if (!r.ok) throw new Error(path + ' -> ' + r.status + ' ' + t.slice(0, 200));
      return t ? JSON.parse(t) : null;
    };
    const wpSite = await api('/api/admin/wp/sites', { url: mockWp.base, username: 'bob', token: WP_TOKEN });
    wpSiteId = wpSite.site.id;
    const cnSite = await api('/api/admin/cnblogs/sites', {
      url: mockCn.base + '/metaweblog/bob', username: 'bob', token: CN_KEY
    });
    cnSiteId = cnSite.site.id;
    const art = await api('/api/admin/articles', {
      title: TITLE, slug: SLUG, status: 'PUBLISHED', summary: '摘要',
      tags: 'Java,AI', content: '正文首段。\n\n![演示图](/media/e2e-dist.png)\n'
    });
    articleId = art.id;
    const siteUrl = (await api('/api/admin/dist/targets?articleId=' + articleId, undefined, 'GET')).siteUrl;
    check('① 预置文章与两个关联目标成功', !!articleId && !!wpSiteId && !!cnSiteId,
      'article=' + articleId + ' wp=' + wpSiteId + ' cn=' + cnSiteId + ' siteUrl=' + siteUrl);

    // ---------- ① 文章列表行出现「分发」按钮 ----------
    await page.click('.tab[data-p="articles"]');
    await page.evaluate(() => { if (typeof loadArticles === 'function') loadArticles(0); });
    await page.waitForFunction(t => [...document.querySelectorAll('#alist tbody tr')]
      .some(r => r.textContent.includes(t)), TITLE, { timeout: 15000 });
    check('① 文章列表行有「分发」图标按钮（svg + aria-label）', await page.evaluate(t => {
      const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
      const b = tr && tr.querySelector('button[data-act="dist"]');
      return !!b && !!b.querySelector('svg') && (b.getAttribute('aria-label') || '').includes('分发');
    }, TITLE));

    await clickRowButton('dist');
    await page.waitForSelector('#dist-modal.open', { timeout: 8000 });
    await page.waitForFunction(() => document.querySelectorAll('#dist-targets .dist-row input[type=checkbox]').length > 0,
      { timeout: 15000 });
    const t0 = await page.evaluate(() => ({
      title: document.getElementById('dist-title').textContent,
      rows: [...document.querySelectorAll('#dist-targets .dist-row')].map(r => ({
        state: r.querySelector('.dstate').textContent.trim(),
        checked: r.querySelector('input[type=checkbox]').checked,
        hasAct: !!r.querySelector('.dact select')
      })),
      groups: [...document.querySelectorAll('#dist-targets .dist-group')].map(g => g.textContent)
    }));
    check('① 弹窗标题带文章名', t0.title.includes(TITLE), t0.title);
    check('① 目标按「WordPress 站点 / 博客园账号」分组', t0.groups.length === 2, t0.groups.join(' / '));
    check('① 两个目标都列出且均为「未分发过」',
      t0.rows.length === 2 && t0.rows.every(r => r.state.includes('未分发过')),
      JSON.stringify(t0.rows.map(r => r.state)));
    check('① 未分发过的目标默认不勾选、且不显示处理方式下拉',
      t0.rows.every(r => !r.checked && !r.hasAct));
    await page.screenshot({ path: OUT + '/50-dist-modal-fresh.png', fullPage: true });

    // ---------- ② 一次分发到两个目标（转载 + Markdown） ----------
    await page.selectOption('#dist-mode', 'repost');
    await page.evaluate(() => document.querySelectorAll('#dist-targets .dist-row input[type=checkbox]')
      .forEach(b => { if (!b.disabled) b.checked = true; }));
    const runResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const runJson = await (await runResp).json();
    check('② 分发接口 ok=2 且无失败', runJson.ok === 2 && runJson.failed === 0, JSON.stringify(runJson).slice(0, 160));
    await page.waitForFunction(() => document.querySelectorAll('#dist-result .dline').length === 2, { timeout: 15000 });
    const resultText = await page.textContent('#dist-result');
    check('② 结果面板两条成功、含「打开」链接',
      (resultText.match(/✓/g) || []).length === 2 && resultText.includes('打开'), resultText.slice(0, 120));

    const wpCreate = mockWp.calls.find(c => c.kind === 'create');
    check('② WP 正文里站内图片已绝对化为本站地址',
      !!wpCreate && wpCreate.body.content.includes(siteUrl + '/media/e2e-dist.png')
      && !wpCreate.body.content.includes('](/media/'),
      wpCreate ? wpCreate.body.content.slice(0, 180) : '(no create call)');
    check('② WP 正文尾部带转载链接（含原文 slug）',
      !!wpCreate && wpCreate.body.content.includes('本文由') && wpCreate.body.content.includes('/article/' + SLUG),
      wpCreate ? wpCreate.body.content.slice(-140) : '');
    check('② WP 标签已同步为 term id',
      mockWp.calls.some(c => c.kind === 'tag' && c.body.name === 'Java')
      && !!wpCreate && Array.isArray(wpCreate.body.tags) && wpCreate.body.tags.length === 2,
      JSON.stringify(wpCreate ? wpCreate.body.tags : null));
    const cnNew = mockCn.calls.find(c => c.method === 'metaWeblog.newPost');
    check('② 博客园收到 Markdown 原文 + [Markdown] 分类 + 转载尾注',
      !!cnNew && cnNew.raw.includes('<string>[Markdown]</string>')
      && cnNew.raw.includes('![演示图](') && cnNew.raw.includes('本文由'),
      cnNew ? cnNew.raw.slice(0, 200) : '(no newPost call)');

    // ---------- ③ 关弹窗后列表行出现「已分发」徽标 ----------
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.waitForFunction(t => {
      const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
      return tr && tr.querySelectorAll('.dist-badge .dtag').length === 2;
    }, TITLE, { timeout: 15000 });
    const badges = await page.evaluate(t => {
      const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
      return [...tr.querySelectorAll('.dist-badge .dtag')].map(d => d.textContent);
    }, TITLE);
    check('③ 列表行显示两个「已分发」徽标',
      badges.length === 2 && badges.every(b => b.startsWith('已分发 ·')), badges.join(' | '));
    await page.screenshot({ path: OUT + '/51-dist-list-badges.png', fullPage: true });

    // ---------- ④ 再打开弹窗：自动勾上 + 处理方式默认「更新」 ----------
    await clickRowButton('dist');
    await page.waitForFunction(() => document.querySelectorAll('#dist-targets .dist-row').length === 2
      && document.querySelector('#dist-targets .dstate').textContent.includes('已分发过'), { timeout: 15000 });
    const t1 = await page.evaluate(() => [...document.querySelectorAll('#dist-targets .dist-row')].map(r => ({
      state: r.querySelector('.dstate').textContent.trim(),
      checked: r.querySelector('input[type=checkbox]').checked,
      action: r.querySelector('.dact select') ? r.querySelector('.dact select').value : null,
      link: r.querySelector('.dstate a') ? r.querySelector('.dstate a').getAttribute('href') : null
    })));
    check('④ 已分发过的目标显示「已分发过 1 次」', t1.every(r => r.state.includes('已分发过 1 次')),
      JSON.stringify(t1.map(r => r.state)));
    check('④ 已分发过的目标自动勾选且默认「更新之前分发的文章」',
      t1.every(r => r.checked && r.action === 'update'), JSON.stringify(t1.map(r => r.action)));
    check('④ 提供已发文章的链接', t1.every(r => r.link && r.link.startsWith('http')),
      JSON.stringify(t1.map(r => r.link)));

    // ---------- ⑤ 选「更新」+ 切回原文分发 ----------
    const wpId1 = runJson.results.find(r => r.channel === 'wp').remotePostId;
    await page.selectOption('#dist-mode', 'original');
    mockWp.calls.length = 0;
    mockCn.calls.length = 0;
    const updResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    await (await updResp).json();
    await page.waitForFunction(() => document.querySelectorAll('#dist-result .dline').length === 2, { timeout: 30000 });
    const updText = await page.textContent('#dist-result');
    check('⑤ 更新模式两条都成功、提示「已更新原文章」',
      (updText.match(/✓/g) || []).length === 2 && updText.includes('已更新原文章'), updText.slice(0, 140));
    check('⑤ WP 走更新接口（同一篇，未新建）',
      mockWp.calls.some(c => c.kind === 'update' && c.id === wpId1)
      && !mockWp.calls.some(c => c.kind === 'create'),
      JSON.stringify(mockWp.calls.map(c => c.kind + ':' + c.id)));
    const wpUpdate = mockWp.calls.find(c => c.kind === 'update');
    check('⑤ 原文分发不再带转载尾注', !!wpUpdate && !wpUpdate.body.content.includes('本文由'),
      wpUpdate ? wpUpdate.body.content.slice(-80) : '');
    check('⑤ 博客园走 editPost', mockCn.calls.some(c => c.method === 'metaWeblog.editPost'),
      JSON.stringify(mockCn.calls.map(c => c.method)));

    // ---------- ⑥ 改成「分发一个新文章」，且只勾 WordPress 一行 ----------
    await page.evaluate(() => {
      document.querySelector('#dist-targets .dist-row .dact select').value = 'create';
      [...document.querySelectorAll('#dist-targets .dist-row')]
        .forEach((r, i) => { r.querySelector('input[type=checkbox]').checked = (i === 0); });
    });
    mockWp.calls.length = 0;
    mockCn.calls.length = 0;
    const run3 = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const run3Json = await (await run3).json();
    const wpId2 = run3Json.results[0].remotePostId;
    check('⑥ 只勾 WP 时只发一个目标',
      run3Json.ok === 1 && run3Json.results.length === 1 && run3Json.results[0].channel === 'wp',
      JSON.stringify(run3Json.results.map(r => r.channel)));
    check('⑥ 博客园未被误发', !mockCn.calls.some(c => c.method === 'metaWeblog.newPost'
      || c.method === 'metaWeblog.editPost'));
    check('⑥ 「分发新文章」拿到新的远端 id',
      mockWp.calls.some(c => c.kind === 'create') && wpId2 !== wpId1, wpId1 + ' -> ' + wpId2);
    await page.screenshot({ path: OUT + '/52-dist-modal-done.png', fullPage: true });

    await page.getByRole('button', { name: '关闭', exact: true }).click();

    // ---------- ⑦ 控制台干净 ----------
    check('⑦ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑦ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
  } catch (e) {
    check('用例执行未抛异常', false, String(e && e.message || e).split('\n')[0]);
  } finally {
    try {
      const jwt = await jwtOf();
      const del = async p => {
        const r = await fetch(BASE + p, { method: 'DELETE', headers: { Authorization: 'Bearer ' + jwt } });
        console.log('CLEANUP ' + p + ' -> ' + r.status);
      };
      if (articleId) await del('/api/admin/articles/' + articleId);
      if (wpSiteId) await del('/api/admin/wp/sites/' + wpSiteId);
      if (cnSiteId) await del('/api/admin/cnblogs/sites/' + cnSiteId);
    } catch (e) { console.log('CLEANUP_FAIL ' + (e && e.message)); }
    mockWp.server.close();
    mockCn.server.close();
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
