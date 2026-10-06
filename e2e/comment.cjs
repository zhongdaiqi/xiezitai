// 验证：评论仅限登录用户（禁止匿名）
//  ① 匿名访问文章页 -> 无评论输入框，只有登录/注册入口
//  ② 匿名直接调评论接口 -> 401
//  ③ 弹窗注册并登录 -> 出现评论框，显示「以 xxx 的身份评论」
//  ④ 提交评论 -> 提示待审；公开评论列表仍为空（未审核）
//  ⑤ 全程 0 JS 错误、0 HTTP>=400（除预期的 401）
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const ADMIN = { u: 'xiezitai', p: 'xiexiexie' };

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

async function jpost(path, body, token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + path, { method: 'POST', headers: h, body: JSON.stringify(body) });
  let d = {}; try { d = await r.json(); } catch (e) {}
  return { status: r.status, d };
}

(async () => {
  // ---- 准备：管理员登录 -> 建一篇已发布文章 ----
  const adminLogin = await jpost('/api/auth/login', { username: ADMIN.u, password: ADMIN.p });
  if (adminLogin.status !== 200) { console.error('管理员登录失败', adminLogin); process.exit(1); }
  const adminToken = adminLogin.d.token;

  const art = await jpost('/api/admin/articles', {
    title: '评论登录校验 ' + Date.now(), content: '## 正文\n用于验证登录后才可评论。', status: 'PUBLISHED'
  }, adminToken);
  if (art.status !== 200) { console.error('建文章失败', art); process.exit(1); }
  const slug = art.d.slug;
  console.log('SLUG=' + slug);

  // ---- 准备：待注册的普通用户（注册动作放到浏览器弹窗里做，顺便验证注册流程） ----
  const uname = 'cmt' + (Date.now() % 1000000);
  const upass = 'cmt123456';
  console.log('USER=' + uname);

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  // ---------- ① 匿名访问 ----------
  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);

  const anonFormVisible = await page.isVisible('#commentForm').catch(() => false);
  const anonHintVisible = await page.isVisible('#loginHint').catch(() => false);
  check('匿名时评论输入框隐藏', !anonFormVisible);
  check('匿名时显示登录/注册入口', anonHintVisible);

  // ---------- ② 匿名直接调接口 -> 401 ----------
  const anonPost = await page.evaluate(async (s) => {
    const r = await fetch('/api/articles/' + s + '/comments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: '匿名灌水', authorName: '管理员本尊' })
    });
    return r.status;
  }, slug);
  check('匿名调评论接口被拒（401）', anonPost === 401, 'status=' + anonPost);

  // ---------- ③ 弹窗注册并登录 ----------
  await page.click('#loginHint button');            // 第一个是「登录」
  await page.waitForSelector('#authModal.open', { timeout: 5000 });
  await page.click('#tabReg');
  await page.fill('#ar-user', uname);
  await page.fill('#ar-pass', upass);
  await page.fill('#ar-email', uname + '@test.local');
  await page.click('button[onclick="doAuthRegister()"]');
  // 注册后自动登录；若失败把弹窗里的错误信息带出来，便于定位
  const loggedIn = await page.waitForFunction(() => {
    const b = document.getElementById('userBox');
    return b && /👤/.test(b.textContent || '');
  }, { timeout: 15000 }).then(() => true).catch(() => false);
  if (!loggedIn) {
    const m = await page.textContent('#authMsg').catch(() => '');
    console.log('AUTH_MSG=' + (m || '').trim());
  }
  await page.waitForTimeout(500);

  const formVisibleAfter = await page.isVisible('#commentForm').catch(() => false);
  const hintGone = !(await page.isVisible('#loginHint').catch(() => false));
  const asWho = await page.textContent('#cmtAs').catch(() => '');
  check('登录后出现评论框', formVisibleAfter);
  check('登录后登录提示消失', hintGone);
  check('显示「以 xxx 的身份评论」', asWho.trim() === uname, asWho.trim());

  // ---------- ④ 提交评论（正文里带伪造 authorName 也没用） ----------
  await page.fill('#ccontent', '这篇文章写得不错，登录测试通过。');
  await page.click('#commentForm button[type=submit]');
  await page.waitForFunction(() => {
    const m = document.getElementById('cmsg');
    return m && m.textContent.trim().length > 0;
  }, { timeout: 10000 }).catch(() => {});
  const cmsg = (await page.textContent('#cmsg')).trim();
  check('提交后提示待审核', /待审|已提交/.test(cmsg), cmsg);

  // 公开列表仍未显示（未审核）
  const pubCount = await page.evaluate(async (s) => {
    const r = await fetch('/api/articles/' + s + '/comments');
    const d = await r.json();
    return Array.isArray(d) ? d.length : -1;
  }, slug);
  check('未审核评论不出现在公开列表', pubCount === 0, 'count=' + pubCount);

  // 管理员侧确认作者名来自登录账号，而非请求体伪造值
  const adminCheck = await page.evaluate(async ({ tk, s }) => {
    const r = await fetch('/api/admin/comments', { headers: { Authorization: 'Bearer ' + tk } });
    const d = await r.json();
    const hit = (d || []).find(c => c.article && c.article.slug === s);
    return hit ? { author: hit.authorName, content: hit.content, status: hit.status } : null;
  }, { tk: adminToken, s: slug });
  check('待审评论作者名为登录账号', !!adminCheck && adminCheck.author === uname,
    adminCheck ? adminCheck.author : 'none');
  check('待审评论状态为 PENDING', !!adminCheck && adminCheck.status === 'PENDING',
    adminCheck ? adminCheck.status : 'none');

  await page.screenshot({ path: 'e2e/out/13-comment-loggedin.png', fullPage: true });
  await page.evaluate(() => { localStorage.removeItem('xz_token'); });
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.screenshot({ path: 'e2e/out/14-comment-anonymous.png', fullPage: true });

  // 匿名提交评论的 401 是预期行为（浏览器会对失败请求打 console.error），不算缺陷
  const realErrors = errors.filter(e => !/favicon/.test(e) && !/401/.test(e));
  check('无 JS 错误', realErrors.length === 0, realErrors.join(' ;; '));
  const realHttpBad = httpBad.filter(x => !/401/.test(x) && !/favicon/.test(x));
  check('无意外 HTTP>=400', realHttpBad.length === 0, realHttpBad.join(' ;; '));

  await browser.close();

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== SUMMARY: ' + (checks.length - failed.length) + '/' + checks.length + ' passed =====');
  process.exit(failed.length ? 2 : 0);
})().catch(e => { console.error('E2E CRASH', e); process.exit(3); });
