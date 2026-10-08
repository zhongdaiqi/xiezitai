/* 后台「文章分发 → 写字台账号」端到端验证（本站 → 另一台写字台）
 *
 * 方案：脚本内起一个本地 mock「对方写字台」HTTP 服务器（127.0.0.1 随机端口），实现：
 *   - GET  /api/v1/articles            文章列表（关联账号时的连通性 / 账号校验用，需 X-API-Token）
 *   - POST /api/v1/publish             发布新文章 → 返回 {id, slug, url}
 *   - PUT  /api/v1/articles/{id}       更新自己发的文章 → 返回 {id, slug, url}
 *   所有写请求的请求体都记下来供断言。
 *   关联时用户填的是「发布接口地址」= MOCK/api/v1/publish，后端要能推导出 /api/v1 根。
 *
 * 覆盖：
 *   ① 关联写字台账号 → 文章列表行「分发」按钮打开弹窗，目标按「写字台账号」分组、显示「未分发过」
 *   ② 转载 + Markdown 分发 → 成功；对方 publish 收到 Markdown 原文（不是 HTML）、
 *      站内图片已绝对化、尾部带「本文由写字台首发 + 原文链接」；结果面板标「写字台 · <账号>」
 *   ③ 关弹窗后列表行出现「已分发 · 账号名」徽标
 *   ④ 再开弹窗：显示「已分发过 1 次」、自动勾选、处理方式默认「更新之前分发的文章」、给出已发文章链接
 *   ⑤ 选「更新」+ 切回原文分发 → 走对方 PUT、远端 id 不变、正文不再带转载尾注
 *   ⑥ 正文格式选「转成 HTML」→ 写字台仍按 Markdown 发（面板给出 ⚠ 说明），正文里没有 <p>
 *   ⑦ 选「分发一个新文章」→ 走对方 publish，拿到新的远端 id
 *   ⑧ 全程 0 JS 错误、0 意外 HTTP>=400
 * 收尾：删掉文章与关联的写字台账号。
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
const XZ_TOKEN = 'e2e-xz-dist-token-0123456789abcdef';
const XZ_USER = 'e2e-xz-remote';
const SLUG = 'e2e-dist-xz-article';
const TITLE = 'E2E 分发到写字台';

/** 收集请求体（node http 是流式的，必须等 end） */
function bodyOf(req) {
  return new Promise(resolve => {
    let raw = '';
    req.on('data', c => { raw += c; });
    req.on('end', () => resolve(raw));
  });
}

/* ---------- 假「对方写字台」开放 API ---------- */
function startMockXz() {
  const calls = [];
  let nextId = 500;
  let base = '';
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    const raw = await bodyOf(req);
    const json = (code, obj) => {
      res.statusCode = code;
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.end(JSON.stringify(obj));
    };
    if ((req.headers['x-api-token'] || '') !== XZ_TOKEN) return json(401, { error: '无效的 API Token' });
    if (req.method === 'GET' && u.pathname === '/api/v1/articles') {
      return json(200, {
        site: base, user: XZ_USER, page: 1, size: 1, total: 0, totalPages: 1, items: []
      });
    }
    if (req.method === 'POST' && u.pathname === '/api/v1/publish') {
      calls.push({ kind: 'publish', body: JSON.parse(raw) });
      nextId++;
      return json(200, { id: nextId, slug: 'xz-dist-' + nextId, url: '/xz-dist-' + nextId });
    }
    const m = /^\/api\/v1\/articles\/(\d+)$/.exec(u.pathname);
    if (m && req.method === 'PUT') {
      const id = Number(m[1]);
      calls.push({ kind: 'update', id, body: JSON.parse(raw) });
      return json(200, { id, slug: 'xz-dist-' + id, url: '/xz-dist-' + id });
    }
    return json(404, {});
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

  const mock = await startMockXz();
  console.log('MOCK_XZ=' + mock.base);

  let articleId = null, siteId = null;
  const jwtOf = () => page.evaluate(() =>
    localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
  const clickRowButton = (act) => page.evaluate(([t, a]) => {
    const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
    tr.querySelector('button[data-act="' + a + '"]').click();
  }, [TITLE, act]);
  // 列表是异步渲染的：「正在加载…」那一行也是 .dist-row 且没有 .dstate，
  // 所以取值一律走可选链，否则会在加载态上抛 TypeError 把整个用例带崩。
  const distRows = () => page.evaluate(() => [...document.querySelectorAll('#dist-targets .dist-row')].map(r => ({
    state: r.querySelector('.dstate') ? r.querySelector('.dstate').textContent.trim() : '',
    checked: r.querySelector('input[type=checkbox]') ? r.querySelector('input[type=checkbox]').checked : false,
    disabled: r.querySelector('input[type=checkbox]') ? r.querySelector('input[type=checkbox]').disabled : true,
    action: r.querySelector('.dact select') ? r.querySelector('.dact select').value : null,
    link: r.querySelector('.dstate a') ? r.querySelector('.dstate a').getAttribute('href') : null
  })));

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

    // ---------- 准备：关联一个写字台账号 + 建一篇文章（Node 侧调 API，不污染页面 console） ----------
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
    const site = await api('/api/admin/xz/sites', {
      url: mock.base + '/api/v1/publish', username: XZ_USER, token: XZ_TOKEN
    });
    siteId = site.site.id;
    const siteName = site.site.name;
    const art = await api('/api/admin/articles', {
      title: TITLE, slug: SLUG, status: 'PUBLISHED', summary: '摘要',
      tags: 'Java,AI', content: '正文首段。\n\n![演示图](/media/e2e-dist-xz.png)\n'
    });
    articleId = art.id;
    const targetInfo = await api('/api/admin/dist/targets?articleId=' + articleId, undefined, 'GET');
    const siteUrl = targetInfo.siteUrl;
    check('① 预置写字台账号与文章成功', !!articleId && !!siteId,
      'article=' + articleId + ' site=' + siteId + ' name=' + siteName + ' siteUrl=' + siteUrl);

    // ---------- ① 文章列表行「分发」按钮 → 弹窗里出现「写字台账号」分组 ----------
    await page.click('.tab[data-p="articles"]');
    await page.evaluate(() => { if (typeof loadArticles === 'function') loadArticles(0); });
    await page.waitForFunction(t => [...document.querySelectorAll('#alist tbody tr')]
      .some(r => r.textContent.includes(t)), TITLE, { timeout: 15000 });

    await clickRowButton('dist');
    await page.waitForSelector('#dist-modal.open', { timeout: 8000 });
    await page.waitForFunction(() => document.querySelectorAll('#dist-targets .dist-row input[type=checkbox]').length > 0,
      { timeout: 15000 });
    const groups = await page.evaluate(() =>
      [...document.querySelectorAll('#dist-targets .dist-group')].map(g => g.textContent));
    check('① 目标按「写字台账号」分组（含分组名与数量）',
      groups.length === 1 && groups[0].includes('写字台账号') && groups[0].includes('1'), groups.join(' / '));
    const t0 = await distRows();
    check('① 写字台账号列出且为「未分发过」、默认不勾选',
      t0.length === 1 && t0[0].state.includes('未分发过') && !t0[0].checked && !t0[0].disabled,
      JSON.stringify(t0));
    check('① 未分发过时不显示「处理方式」下拉', t0[0].action === null);
    await page.screenshot({ path: OUT + '/60-dist-xz-modal-fresh.png', fullPage: true });

    // ---------- ② 转载 + Markdown → 成功，对方收到 Markdown 原文 + 转载尾注 ----------
    await page.selectOption('#dist-mode', 'repost');
    await page.evaluate(() => document.querySelectorAll('#dist-targets .dist-row input[type=checkbox]')
      .forEach(b => { if (!b.disabled) b.checked = true; }));
    const runResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const runJson = await (await runResp).json();
    check('② 分发接口 ok=1 且无失败', runJson.ok === 1 && runJson.failed === 0, JSON.stringify(runJson).slice(0, 200));
    await page.waitForFunction(() => document.querySelectorAll('#dist-result .dline').length === 1, { timeout: 15000 });
    const resultText = await page.textContent('#dist-result');
    check('② 结果面板标「写字台 · 账号名」且给出「打开」链接',
      resultText.includes('写字台 · ' + siteName) && resultText.includes('打开') && resultText.includes('已发布新文章'),
      resultText.slice(0, 160));

    const pub = mock.calls.find(c => c.kind === 'publish');
    check('② 对方 publish 收到 Markdown 原文（站内图已绝对化、无 HTML 包裹）',
      !!pub && pub.body.content.includes(siteUrl + '/media/e2e-dist-xz.png')
      && !pub.body.content.includes('](/media/') && !pub.body.content.includes('<p>'),
      pub ? pub.body.content.slice(0, 180) : '(no publish call)');
    check('② 转载分发尾部带「本文由写字台首发 + 原文链接」',
      !!pub && pub.body.content.includes('本文由') && pub.body.content.includes(siteUrl + '/' + SLUG),
      pub ? pub.body.content.slice(-140) : '');
    check('② 不传 slug（同一台写字台发多篇也不会撞 slug）',
      !!pub && pub.body.slug === undefined, JSON.stringify(Object.keys(pub ? pub.body : {})));
    const remoteId1 = runJson.results[0].remotePostId;
    check('② 回填远端链接', runJson.results[0].remoteUrl === mock.base + '/xz-dist-' + remoteId1,
      runJson.results[0].remoteUrl);

    // ---------- ③ 关弹窗后列表行出现「已分发」徽标 ----------
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.waitForFunction(t => {
      const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
      return tr && tr.querySelectorAll('.dist-badge .dtag').length === 1;
    }, TITLE, { timeout: 15000 });
    const badge = await page.evaluate(t => {
      const tr = [...document.querySelectorAll('#alist tbody tr')].find(r => r.textContent.includes(t));
      return tr.querySelector('.dist-badge .dtag').textContent;
    }, TITLE);
    check('③ 列表行徽标显示「已分发 · 账号名」',
      badge === '已分发 · ' + siteName, badge);
    await page.screenshot({ path: OUT + '/61-dist-xz-list-badge.png', fullPage: true });

    // ---------- ④ 再开弹窗：自动勾选 + 处理方式默认「更新」+ 已发文章链接 ----------
    await clickRowButton('dist');
    await page.waitForFunction(() => {
      const s = document.querySelector('#dist-targets .dist-row .dstate');
      return !!s && s.textContent.includes('已分发过');
    }, null, { timeout: 15000 });
    const t1 = await distRows();
    check('④ 显示「已分发过 1 次」', t1[0].state.includes('已分发过 1 次'), t1[0].state);
    check('④ 自动勾选且处理方式默认「更新之前分发的文章」',
      t1[0].checked && t1[0].action === 'update', JSON.stringify(t1[0]));
    check('④ 给出已发文章链接（指向对方站点）',
      t1[0].link === mock.base + '/xz-dist-' + remoteId1, String(t1[0].link));

    // ---------- ⑤ 更新 + 原文分发 → 走对方 PUT，远端 id 不变 ----------
    await page.selectOption('#dist-mode', 'original');
    mock.calls.length = 0;
    const updResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const updJson = await (await updResp).json();
    await page.waitForFunction(() => document.querySelectorAll('#dist-result .dline').length === 1, { timeout: 30000 });
    const updText = await page.textContent('#dist-result');
    check('⑤ 更新成功且提示「已更新原文章」',
      updJson.ok === 1 && updText.includes('已更新原文章'), updText.slice(0, 140));
    check('⑤ 走对方 PUT /articles/{id}，未新建',
      mock.calls.some(c => c.kind === 'update' && c.id === remoteId1)
      && !mock.calls.some(c => c.kind === 'publish'),
      JSON.stringify(mock.calls.map(c => c.kind + ':' + c.id)));
    check('⑤ 远端 id 不变', updJson.results[0].remotePostId === remoteId1,
      remoteId1 + ' -> ' + updJson.results[0].remotePostId);
    const updCall = mock.calls.find(c => c.kind === 'update');
    check('⑤ 原文分发不再带转载尾注',
      !!updCall && !updCall.body.content.includes('本文由'), updCall ? updCall.body.content.slice(-80) : '');

    // ---------- ⑥ 正文格式选「转成 HTML」→ 写字台仍按 Markdown 发 + 面板给出 ⚠ ----------
    mock.calls.length = 0;
    await page.selectOption('#dist-format', 'html');
    const htmlResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const htmlJson = await (await htmlResp).json();
    await page.waitForFunction(() => document.querySelectorAll('#dist-result .dline').length === 1, { timeout: 30000 });
    const htmlText = await page.textContent('#dist-result');
    check('⑥ 面板提示「写字台正文一律按 Markdown 发送」',
      htmlText.includes('Markdown'), htmlText.slice(0, 200));
    const updCall2 = mock.calls.find(c => c.kind === 'update');
    check('⑥ 选「转成 HTML」时对方收到的仍是 Markdown（没有 <p> 标签）',
      !!updCall2 && !updCall2.body.content.includes('<p>') && updCall2.body.content.includes('正文首段。'),
      updCall2 ? updCall2.body.content.slice(0, 120) : '(no update call)');
    check('⑥ 本次仍是「更新」路径（id 未换）', htmlJson.results[0].remotePostId === remoteId1,
      String(htmlJson.results[0].remotePostId));

    // ---------- ⑦ 改成「分发一个新文章」→ 又走 publish，新远端 id ----------
    await page.evaluate(() => { document.querySelector('#dist-targets .dact select').value = 'create'; });
    mock.calls.length = 0;
    const run3 = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const run3Json = await (await run3).json();
    const remoteId2 = run3Json.results[0].remotePostId;
    check('⑦ 「分发一个新文章」走 publish 且拿到新远端 id',
      mock.calls.some(c => c.kind === 'publish') && remoteId2 !== remoteId1 && run3Json.results[0].updated === false,
      remoteId1 + ' -> ' + remoteId2);
    await page.screenshot({ path: OUT + '/62-dist-xz-done.png', fullPage: true });
    await page.getByRole('button', { name: '关闭', exact: true }).click();

    // ---------- ⑧ 控制台干净 ----------
    check('⑧ 无 JS 错误', errors.length === 0, errors.slice(0, 3).join(' ; '));
    check('⑧ 无意外 HTTP>=400', httpBad.length === 0, httpBad.slice(0, 3).join(' ; '));
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
      if (siteId) await del('/api/admin/xz/sites/' + siteId);
    } catch (e) { console.log('CLEANUP_FAIL ' + (e && e.message)); }
    mock.server.close();
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
