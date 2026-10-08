/* 后台「Blogger」面板：Google OAuth 关联 / 浏览 / 单篇导入 / 整站导入进度 / 分发到 Blogger
 *
 * 方案：脚本内起一个本地 mock「Google OAuth + Blogger API v3」（127.0.0.1:18633）。
 *   端口必须固定 —— 这几个地址是**应用级配置**（不像 WP / 写字台那样能把地址存进数据库再改），
 *   所以要用它就得在**启动被测实例时**把端点指过来：
 *     XIEZITAI_GOOGLE_AUTH_URI=http://127.0.0.1:18633/o/oauth2/v2/auth
 *     XIEZITAI_GOOGLE_TOKEN_URI=http://127.0.0.1:18633/token
 *     XIEZITAI_GOOGLE_API_BASE=http://127.0.0.1:18633/blogger/v3
 *     XIEZITAI_GOOGLE_CLIENT_ID=e2e-client-id.apps.googleusercontent.com
 *     XIEZITAI_GOOGLE_CLIENT_SECRET=e2e-client-secret
 *     XIEZITAI_BLOGGER_MEDIA_HOSTS=127.0.0.1,blogspot.com,googleusercontent.com
 *   详见 e2e/README.md。
 *
 * 覆盖：
 *   ① 「Blogger」tab 存在，进面板为空态，且提示已配置 OAuth 客户端
 *   ② 点「关联 Google 账号」→ 弹窗打开授权页 → 在弹窗里点「同意」
 *      → 回调落到本站 /google/auth/redirect → 弹窗自动关闭 → 父窗口收到 postMessage 并刷新列表
 *   ③ 一个 Google 账号下的 2 个博客都出现在表格里，且按账号分组；接口不回显令牌明文
 *   ④ 浏览文章 → 列表渲染（Blogger 的 pageToken 翻页：第 1 页 2 条、能翻到第 2 页 1 条）
 *   ⑤ 单篇导入 → 成功提示；Blogger 自家的图落盘 /media/、外站图保留外链
 *   ⑥ 整站导入 → 进度区出现并跑到「已完成」（总数 3、跳过 1、成功 2）
 *   ⑦ 文章列表分发：目标里出现「谷歌 Blogger」分组 → 转载分发成功 → 结果标「Blogger · 博客名」
 *      → 列表行出现「已分发」徽标
 *   ⑧ 全程 0 JS 错误、0 意外 HTTP>=400
 * 收尾：删掉导入的文章、解除博客与账号关联。
 */
const { chromium } = require('playwright');
const http = require('http');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const OUT = 'e2e/out';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };

/** mock 的固定端口（必须与启动实例时的 XIEZITAI_GOOGLE_* 配置一致） */
const MOCK_PORT = Number(process.env.E2E_BLOGGER_MOCK_PORT || 18633);
const MOCK_BASE = 'http://127.0.0.1:' + MOCK_PORT;

const EMAIL = 'e2e-blogger@example.com';
// 故意用 19 位数字串当 id：验证它们不会因为塞进 long 而丢精度
const BLOG_A = '1234567890123456789';
const BLOG_B = '9876543210987654321';
const POSTS = [
  { id: '1111111111111111111', title: 'Blogger E2E 第一篇', url: 'https://e2e-blog-one.blogspot.com/2026/01/blogger-e2e-post-01.html', date: '2026-01-01T08:00:00+08:00', labels: ['E2ETag', 'Java'] },
  { id: '2222222222222222222', title: 'Blogger E2E Second Post', url: 'https://e2e-blog-one.blogspot.com/2026/02/blog-post.html', date: '2026-02-02T09:30:00+08:00', labels: [] },
  { id: '3333333333333333333', title: '中文博客文章', url: 'https://e2e-blog-one.blogspot.com/2026/03/blog-post_20.html', date: '2026-03-03T10:00:00+08:00', labels: ['中文标签'] },
];
const NEW_POST_ID = '4444444444444444444';
const EXT_IMG = 'http://cdn.external-blogger-e2e.test/ext.png';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', 'base64');

/** 收集请求体（node http 是流式的，必须等 end） */
function bodyOf(req) {
  return new Promise(resolve => {
    let raw = '';
    req.on('data', c => { raw += c; });
    req.on('end', () => resolve(raw));
  });
}

/** 假 Blogger 文章正文（第 1 篇：自家图应落盘 + 外站图应留外链 + 非媒体链接不应下载） */
function contentOf(i) {
  if (i !== 0) return '<p>第 ' + (i + 1) + ' 篇正文，<strong>加粗</strong>。</p>';
  return '<h2>小标题</h2><p>开头一段正文。</p>'
    + '<p><img src="' + MOCK_BASE + '/img/photo.png" alt="本站图"></p>'
    + '<p><img src="' + EXT_IMG + '" alt="外站图"></p>'
    + '<p><a href="' + MOCK_BASE + '/2026/01/other-post.html">站内非媒体链接</a></p>';
}

const briefOf = i => ({
  id: POSTS[i].id, title: POSTS[i].title, url: POSTS[i].url,
  published: POSTS[i].date, updated: POSTS[i].date, labels: POSTS[i].labels,
});

/** 假「Google OAuth + Blogger API」服务 */
function startMockGoogle() {
  const calls = [];                 // 分发 / 导入落下的写请求，供断言
  const server = http.createServer(async (req, res) => {
    const path = req.url.split('?')[0];
    const qs = new URLSearchParams(req.url.split('?')[1] || '');
    const json = (obj, code = 200) => {
      const buf = Buffer.from(JSON.stringify(obj), 'utf8');
      res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': buf.length });
      res.end(buf);
    };

    // ---- 同意页：把用户点「同意」变成一次带 code 的回调跳转 ----
    if (path === '/o/oauth2/v2/auth') {
      const redirect = qs.get('redirect_uri') || '';
      const state = qs.get('state') || '';
      const target = redirect + '?code=e2e-auth-code&state=' + encodeURIComponent(state);
      const html = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>Google 登录</title></head>'
        + '<body style="font:15px sans-serif;padding:24px">'
        + '<p>模拟 Google 同意页（E2E）</p>'
        + '<a id="agree" href="' + target.replace(/&/g, '&amp;') + '">同意并继续</a>'
        + '</body></html>';
      const buf = Buffer.from(html, 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': buf.length });
      return res.end(buf);
    }

    // ---- 令牌端点（授权码换令牌 / refresh_token 续期）----
    if (path === '/token') {
      const form = await bodyOf(req);
      calls.push({ kind: 'token', form });
      const idToken = 'h.' + Buffer.from(JSON.stringify({ email: EMAIL, name: 'E2E Blogger' }))
        .toString('base64url') + '.s';
      return json({
        access_token: 'e2e-mock-access-token', refresh_token: 'e2e-mock-refresh-token',
        expires_in: 3600, id_token: idToken,
      });
    }

    // ---- Blogger 自家图片（host=127.0.0.1 已在 blogger-media-hosts 里）----
    if (path === '/img/photo.png') {
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': PNG_1X1.length });
      return res.end(PNG_1X1);
    }

    if (path === '/blogger/v3/users/self/blogs') {
      return json({ items: [
        { id: BLOG_A, name: 'E2E Blogger 一号', url: 'https://e2e-blog-one.blogspot.com/', posts: { totalItems: 3 } },
        { id: BLOG_B, name: 'E2E Blogger 二号', url: 'https://e2e-blog-two.blogspot.com/', posts: { totalItems: 0 } },
      ] });
    }

    const m = /^\/blogger\/v3\/blogs\/([^/]+)(?:\/posts(?:\/([^/]+))?)?$/.exec(path);
    if (m) {
      const blogId = decodeURIComponent(m[1]);
      const postId = m[2] ? decodeURIComponent(m[2]) : null;
      // 博客信息（含文章总数 = 进度条分母）
      if (!postId && !/\/posts$/.test(path)) {
        return json({ id: blogId, name: blogId === BLOG_A ? 'E2E Blogger 一号' : 'E2E Blogger 二号',
          url: blogId === BLOG_A ? 'https://e2e-blog-one.blogspot.com/' : 'https://e2e-blog-two.blogspot.com/',
          posts: { totalItems: blogId === BLOG_A ? 3 : 0 } });
      }
      if (postId) {
        if (req.method === 'PATCH') {
          calls.push({ kind: 'update', blogId, postId, body: JSON.parse(await bodyOf(req) || '{}') });
          return json({ id: postId, url: 'https://e2e-blog-one.blogspot.com/2026/10/dist-e2e.html' });
        }
        const i = POSTS.findIndex(p => p.id === postId);
        return json(Object.assign(briefOf(i), { content: contentOf(i) }));
      }
      if (req.method === 'POST') {
        calls.push({ kind: 'create', blogId, body: JSON.parse(await bodyOf(req) || '{}') });
        return json({ id: NEW_POST_ID, url: 'https://e2e-blog-one.blogspot.com/2026/10/dist-e2e.html' });
      }
      // 列表：第 1 页 2 条 + nextPageToken，第 2 页 1 条
      const token = qs.get('pageToken') || '';
      if (blogId !== BLOG_A) return json({ items: [] });
      return token
        ? json({ items: [briefOf(2)] })
        : json({ items: [briefOf(0), briefOf(1)], nextPageToken: 'E2E-PAGE-2' });
    }

    json({ error: 'not found' }, 404);
  });
  return new Promise(resolve => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  page.on('dialog', d => d.accept());
  const log = (...a) => console.log(...a);

  let mock;
  try {
    mock = await startMockGoogle();
  } catch (e) {
    console.log('SCRIPT_ERROR 假 Google 服务起不来（端口 ' + MOCK_PORT + ' 被占？）：' + e.message);
    console.log('提示：请确认被测实例是用 XIEZITAI_GOOGLE_API_BASE=' + MOCK_BASE + '/blogger/v3 启动的');
    process.exit(1);
  }
  log('MOCK_GOOGLE=' + MOCK_BASE);

  let articleId = null, siteAId = null;
  const jwtOf = () => page.evaluate(() =>
    localStorage.getItem('xz_token') || sessionStorage.getItem('xz_token'));
  let jwt = '';

  const posts = (q) => fetch(BASE + q, { headers: { Authorization: 'Bearer ' + jwt } });

  /** 清掉本用例导入的文章（按 slug 前缀）与上次残留的关联 */
  const cleanup = async () => {
    if (!jwt) return;
    try {
      const r = await posts('/api/admin/articles?size=200');
      const data = await r.json();
      for (const a of (data.content || [])) {
        if (/^blogger-|^中文博客文章$|^dist-blogger-e2e$/.test(a.slug || '')
            || a.author === 'E2E Blogger 一号' || a.title === '分发到 Blogger 的文章') {
          await fetch(BASE + '/api/admin/articles/' + a.id, {
            method: 'DELETE', headers: { Authorization: 'Bearer ' + jwt } });
        }
      }
      const sr = await posts('/api/admin/blogger/sites');
      for (const s of (await sr.json()) || []) {
        await fetch(BASE + '/api/admin/blogger/sites/' + s.id, {
          method: 'DELETE', headers: { Authorization: 'Bearer ' + jwt } });
      }
    } catch (e) { /* 忽略 */ }
  };

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
    jwt = await jwtOf();
    await cleanup();

    // ---------- ① 进面板：空态 ----------
    check('① 「Blogger」tab 存在', await page.locator('.tab[data-p="blogger"]').count() === 1);
    await page.click('.tab[data-p="blogger"]');
    await page.waitForSelector('#p-blogger.active', { timeout: 8000 });
    await page.waitForFunction(() => !!document.querySelector('#gblist tbody tr'), { timeout: 10000 });
    const emptyTip = await page.textContent('#gblist tbody');
    check('① 空态提示', emptyTip.includes('还没有关联'), emptyTip.trim().slice(0, 26));
    check('① 已配置 OAuth 客户端（按钮可点、无「未配置」警告）',
      !(await page.isDisabled('#gb-oauth')) && !(await page.textContent('#gb-status')).includes('尚未配置'));
    await page.screenshot({ path: OUT + '/70-blogger-empty.png', fullPage: true });

    // ---------- ② 授权：弹窗 → 同意 → 回调 → 自动关闭 → 父窗口刷新 ----------
    const [popup] = await Promise.all([
      page.waitForEvent('popup', { timeout: 15000 }),
      page.click('#gb-oauth'),
    ]);
    const popupUrl = popup.url() || (await popup.waitForEvent('load').then(() => popup.url()));
    check('② 弹窗打开的是 Google 授权地址', popupUrl.startsWith(MOCK_BASE + '/o/oauth2/v2/auth'), popupUrl.slice(0, 80));
    check('② 授权地址带 client_id / redirect_uri / offline',
      popupUrl.includes('client_id=e2e-client-id.apps.googleusercontent.com')
      && popupUrl.includes('redirect_uri=') && popupUrl.includes('access_type=offline'),
      popupUrl.slice(0, 200));

    await popup.waitForSelector('#agree', { timeout: 15000 });
    await popup.click('#agree');
    // 回调页会 postMessage 给父窗口并把自己关掉
    await popup.waitForEvent('close', { timeout: 20000 }).catch(() => { /* 有的环境关不掉，下面照样验列表 */ });

    await page.bringToFront();
    await page.waitForFunction(() =>
      document.querySelectorAll('#gblist tbody tr').length > 0
      && !document.querySelector('#gblist tbody').textContent.includes('还没有关联'),
      { timeout: 20000 });
    const listText = await page.textContent('#gblist tbody');
    check('② 回调后父窗口列表自动刷新出 2 个博客',
      listText.includes('E2E Blogger 一号') && listText.includes('E2E Blogger 二号'), listText.replace(/\s+/g, ' ').slice(0, 90));
    check('② 按 Google 账号分组显示', listText.includes('Google 账号：' + EMAIL), '');

    const sites = await (await posts('/api/admin/blogger/sites')).json();
    check('② 接口列出 2 个博客且标记已授权',
      sites.length === 2 && sites.every(s => s.hasAuth === true), JSON.stringify(sites.map(s => [s.blogId, s.hasAuth])));
    check('② 接口不回显令牌明文',
      !JSON.stringify(sites).includes('e2e-mock-refresh-token') && !JSON.stringify(sites).includes('e2e-mock-access-token'));
    siteAId = (sites.find(s => s.blogId === BLOG_A) || {}).id;
    await page.screenshot({ path: OUT + '/71-blogger-associated.png', fullPage: true });

    // ---------- ③ 浏览文章（pageToken 翻页） ----------
    await page.evaluate(id => browseBloggerSite(id), siteAId);
    await page.waitForFunction(() => document.querySelectorAll('#gbposts tbody tr').length === 2, { timeout: 15000 });
    const page1 = await page.textContent('#gbposts tbody');
    check('③ 第 1 页列出 2 篇（含标签列）',
      page1.includes('Blogger E2E 第一篇') && page1.includes('E2ETag,Java'), page1.replace(/\s+/g, ' ').slice(0, 80));
    check('③ 底部显示总数与页码', /共 3 篇/.test(await page.textContent('#gbposts-pager')),
      (await page.textContent('#gbposts-pager')).replace(/\s+/g, ' ').slice(0, 60));
    await page.getByRole('button', { name: '下一页', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('#gbposts tbody tr').length === 1, { timeout: 15000 });
    check('③ 翻到第 2 页（pageToken）拿到第 3 篇',
      (await page.textContent('#gbposts tbody')).includes('中文博客文章'));
    await page.screenshot({ path: OUT + '/72-blogger-browse.png', fullPage: true });

    // ---------- ④ 单篇导入：媒体本地化 ----------
    await page.getByRole('button', { name: '首页', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('#gbposts tbody tr').length === 2, { timeout: 15000 });
    const impResp = page.waitForResponse(r => r.url().includes('/import'), { timeout: 60000 });
    await page.evaluate(() => gbImportOne('1111111111111111111'));
    const impJson = await (await impResp).json();
    check('④ 单篇导入成功且 slug 取链接末段',
      impJson.imported === true && impJson.slug === 'blogger-e2e-post-01', JSON.stringify(impJson).slice(0, 140));
    const art = await page.evaluate(async () => {
      const r = await fetch('/api/admin/articles?size=200', {
        headers: { Authorization: 'Bearer ' + localStorage.getItem('xz_token') } });
      const d = await r.json();
      return (d.content || []).find(a => a.slug === 'blogger-e2e-post-01') || null;
    });
    check('④ HTML 还原成 Markdown（标题变 ## 小标题）', !!art && art.content.includes('## 小标题'),
      art ? art.content.slice(0, 60) : '(not found)');
    check('④ Blogger 自家的图落盘成本站 /media/', !!art && art.content.includes('![本站图](/media/'), art ? art.content.slice(0, 200) : '');
    check('④ 外站图保留外链', !!art && art.content.includes(EXT_IMG));
    check('④ 站内非媒体链接不下载', !!art && art.content.includes('/2026/01/other-post.html'));
    check('④ 发布人用博客名（不暴露 Google 邮箱）', !!art && art.author === 'E2E Blogger 一号', art ? art.author : '');
    check('④ 标签与摘要导入', !!art && art.tags === 'E2ETag,Java' && art.summary === '开头一段正文。',
      art ? art.tags + ' / ' + art.summary : '');
    articleId = art ? art.id : null;

    // ---------- ⑤ 整站导入 + 进度 ----------
    const allResp = page.waitForResponse(r => r.url().includes('/import-all'), { timeout: 30000 });
    await page.evaluate(() => gbImportAll());
    check('⑤ 整站导入返回 202 已启动', (await allResp).status() === 202);
    await page.waitForFunction(() => {
      const t = document.querySelector('#gb-progress-text');
      return t && /已完成/.test(t.textContent);
    }, null, { timeout: 90000 });
    const progText = await page.textContent('#gb-progress-text');
    const progLog = await page.textContent('#gb-progress-log');
    check('⑤ 进度跑到「已完成」并显示总数', /已完成/.test(progText) && /3\/3/.test(progText.replace(/\s+/g, '')), progText.trim());
    check('⑤ 日志含「整站导入完成」', progLog.includes('整站导入完成'), progLog.trim().split('\n').pop());
    const prog = await (await posts('/api/admin/blogger/sites/' + siteAId + '/progress')).json();
    check('⑤ 统计：总数 3 / 成功 2 / 跳过 1 / 失败 0',
      prog.total === 3 && prog.imported === 2 && prog.skipped === 1 && prog.failed === 0,
      'total=' + prog.total + ' imported=' + prog.imported + ' skipped=' + prog.skipped + ' failed=' + prog.failed);
    const slugs = await page.evaluate(async () => {
      const r = await fetch('/api/admin/articles?size=200', {
        headers: { Authorization: 'Bearer ' + localStorage.getItem('xz_token') } });
      return (await r.json()).content.map(a => a.slug);
    });
    check('⑤ 三篇 slug 分别来自：链接末段 / 标题转写 / 文章 id 兜底',
      slugs.includes('blogger-e2e-post-01') && slugs.includes('blogger-e2e-second-post')
      && slugs.includes('blogger-3333333333333333333'), slugs.filter(s => s.startsWith('blogger-')).join(' | '));
    await page.screenshot({ path: OUT + '/73-blogger-progress.png', fullPage: true });

    // ---------- ⑥ 分发到 Blogger ----------
    await page.click('.tab[data-p="articles"]');
    await page.evaluate(() => { if (typeof loadArticles === 'function') loadArticles(0); });
    await page.waitForFunction(() => [...document.querySelectorAll('#alist tbody tr')]
      .some(r => r.textContent.includes('Blogger E2E 第一篇')), { timeout: 15000 });

    await page.evaluate(() => {
      const tr = [...document.querySelectorAll('#alist tbody tr')]
        .find(r => r.textContent.includes('Blogger E2E 第一篇'));
      tr.querySelector('button[data-act="dist"]').click();
    });
    await page.waitForSelector('#dist-modal.open', { timeout: 8000 });
    await page.waitForFunction(() => document.querySelectorAll('#dist-targets .dist-row input[type=checkbox]').length > 0,
      { timeout: 15000 });
    const groups = await page.evaluate(() =>
      [...document.querySelectorAll('#dist-targets .dist-group')].map(g => g.textContent));
    check('⑥ 目标清单里有「谷歌 Blogger」分组', groups.some(g => g.includes('谷歌 Blogger')), groups.join(' / '));
    const rows = await page.evaluate(() => [...document.querySelectorAll('#dist-targets .dist-row')].map(r => ({
      name: r.querySelector('.dname') ? r.querySelector('.dname').textContent : '',
      state: r.querySelector('.dstate') ? r.querySelector('.dstate').textContent.trim() : '',
      checked: r.querySelector('input[type=checkbox]') ? r.querySelector('input[type=checkbox]').checked : false,
    })));
    check('⑥ 列出 Blogger 博客且为「未分发过」、默认不勾选',
      rows.some(r => r.name.includes('E2E Blogger 一号') && r.state.includes('未分发过') && !r.checked),
      JSON.stringify(rows));
    await page.screenshot({ path: OUT + '/74-blogger-dist-modal.png', fullPage: true });

    await page.selectOption('#dist-mode', 'repost');
    await page.evaluate(() => document.querySelectorAll('#dist-targets .dist-row input[type=checkbox]')
      .forEach(b => { if (!b.disabled) b.checked = true; }));
    const runResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const runJson = await (await runResp).json();
    check('⑥ 分发 ok=1、failed=0',
      runJson.ok === 1 && runJson.failed === 0, JSON.stringify(runJson).slice(0, 160));
    const r0 = (runJson.results || [])[0] || {};
    check('⑥ 结果里的远端 id 是字符串形态（长数字不丢精度）', r0.remoteId === NEW_POST_ID, String(r0.remoteId));
    check('⑥ 提示 Blogger 正文按 HTML 发送、忽略「Markdown 原文」',
      (r0.warnings || []).some(w => w.includes('Blogger')), JSON.stringify(r0.warnings));
    const createCall = mock.calls.find(c => c.kind === 'create');
    check('⑥ Blogger 收到 HTML 正文 + 标签',
      !!createCall && createCall.body.content.includes('<h1>') && createCall.body.content.includes('<img')
      && Array.isArray(createCall.body.labels) && createCall.body.labels.includes('E2ETag'),
      createCall ? createCall.body.content.slice(0, 120) : '(no create call)');
    check('⑥ 转载分发带原文链接', !!createCall && createCall.body.content.includes('本文由')
      && createCall.body.content.includes('/blogger-e2e-post-01'), createCall ? createCall.body.content.slice(-120) : '');

    await page.waitForFunction(() => document.querySelectorAll('#dist-result .dline').length >= 1, { timeout: 15000 });
    const resultText = await page.textContent('#dist-result');
    check('⑥ 结果面板标「Blogger · 博客名」',
      resultText.includes('Blogger · ') && resultText.includes('E2E Blogger 一号') && resultText.includes('已发布新文章'),
      resultText.slice(0, 140));

    // 关弹窗 → 列表行出现「已分发」徽标
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.waitForFunction(() => {
      const tr = [...document.querySelectorAll('#alist tbody tr')]
        .find(r => r.textContent.includes('Blogger E2E 第一篇'));
      return tr && tr.querySelectorAll('.dist-badge .dtag').length === 1;
    }, null, { timeout: 15000 });
    const badge = await page.evaluate(() => {
      const tr = [...document.querySelectorAll('#alist tbody tr')]
        .find(r => r.textContent.includes('Blogger E2E 第一篇'));
      return tr.querySelector('.dist-badge .dtag').textContent;
    });
    check('⑥ 列表徽标显示「已分发 · 博客名（账号）」', badge.includes('E2E Blogger 一号'), badge);
    await page.screenshot({ path: OUT + '/75-blogger-dist-badge.png', fullPage: true });

    // ② 再分发选「更新」→ 走 PATCH，远端 id 不变
    await page.evaluate(() => {
      const tr = [...document.querySelectorAll('#alist tbody tr')]
        .find(r => r.textContent.includes('Blogger E2E 第一篇'));
      tr.querySelector('button[data-act="dist"]').click();
    });
    await page.waitForFunction(() => {
      const r = document.querySelector('#dist-targets .dist-row input[type=checkbox]:checked');
      return !!r && !!document.querySelector('#dist-targets .dact select');
    }, null, { timeout: 15000 });
    check('⑥ 已分发过的目标自动勾选并默认「更新之前分发的文章」',
      await page.evaluate(() => {
        const s = document.querySelector('#dist-targets .dact select');
        return !!s && s.value === 'update';
      }));
    const updResp = page.waitForResponse(r => r.url().includes('/api/admin/dist/run'), { timeout: 60000 });
    await page.getByRole('button', { name: '开始分发', exact: true }).click();
    const updJson = await (await updResp).json();
    const updCall = mock.calls.find(c => c.kind === 'update');
    check('⑥ 选「更新」走 Blogger PATCH 且远端 id 不变',
      !!updCall && updCall.postId === NEW_POST_ID && (updJson.results || [])[0].remoteId === NEW_POST_ID,
      updCall ? updCall.postId : '(no patch call)');
    check('⑥ 原文分发不再带转载尾注', !!updCall && !updCall.body.content.includes('本文由'));
    await page.getByRole('button', { name: '关闭', exact: true }).click();

    // ---------- ⑦ 解除关联：分发记录一并清掉 ----------
    await page.click('.tab[data-p="blogger"]');
    await page.waitForFunction(() => document.querySelectorAll('#gblist tbody tr').length > 0, { timeout: 15000 });
    const delResp = page.waitForResponse(r => /\/api\/admin\/blogger\/accounts/.test(r.url()), { timeout: 15000 });
    await page.evaluate(() => delBloggerAccount(0));
    const delJson = await (await delResp).json();
    check('⑦ 解除整个 Google 账号：2 个博客一起解绑', delJson.removed === 2, JSON.stringify(delJson));
    await page.waitForFunction(() =>
      document.querySelector('#gblist tbody').textContent.includes('还没有关联'), null, { timeout: 15000 });
    const left = await (await posts('/api/admin/blogger/sites')).json();
    check('⑦ 关联列表已清空', left.length === 0, 'left=' + left.length);
    const records = await (await posts('/api/admin/dist/map?ids=' + articleId)).json();
    check('⑦ 分发记录随关联一起清掉（不留悬空徽标）',
      !records[String(articleId)] || records[String(articleId)].length === 0, JSON.stringify(records));

    // ---------- ⑧ 无 JS 错误 / 无意外 4xx-5xx ----------
    const realBad = httpBad.filter(u => !/\/import|401/.test(u));
    check('⑧ 无 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | '));
    check('⑧ 无意外 HTTP>=400', realBad.length === 0, realBad.slice(0, 3).join(' | '));
  } finally {
    try { await cleanup(); } catch (e) { /* 忽略 */ }
    mock.close();
    await browser.close();
  }

  const passed = checks.filter(c => c.ok).length;
  console.log('\n结果：' + passed + '/' + checks.length + ' 通过');
  if (passed !== checks.length) {
    console.log('失败项：');
    checks.filter(c => !c.ok).forEach(c => console.log('  - ' + c.name + (c.extra !== undefined ? '  [' + c.extra + ']' : '')));
    process.exit(1);
  }
})();
