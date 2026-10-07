// 验证「注册需审核」：待审核用户登录被拦 → 后台「用户」页放行 → 才能登录；驳回带原因且令牌立即失效
// ① 前台注册弹窗提交后不再自动登录，而是提示等待审核（并切到登录页签、填好用户名）
// ② 前台用待审核账号登录：提示等待审核，且确实没有登录态
// ③ 后台用户页：待审核行高亮、排在最前，点「通过」后状态变为已通过
// ④ 通过后前台登录成功
// ⑤ 后台 UI 驳回另一个待审核账号并填原因 → 前台登录提示里能看到原因
// ⑥ 驳回已通过的账号：此前签发的 token 立即失效（不必等它过期）
// ⑦ 0 JS 错误
const { chromium } = require('playwright');
const { auditUser } = require('./lib/audit.cjs');
const BASE = process.env.E2E_BASE || 'http://localhost:8080';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

async function req(method, path, body, token) {
  const h = {};
  if (body) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
  let d = null; try { d = await r.json(); } catch (e) {}
  return { status: r.status, d };
}
const post = (p, b, t) => req('POST', p, b, t);
const get = (p, t) => req('GET', p, null, t);

const stamp = Date.now() % 100000;
const uiRegUser = 'uia' + stamp;     // 走「前台注册弹窗」那条路
const pendingUser = 'wait' + stamp;  // 走「待审核 → 后台通过」
const rejectUser = 'rej' + stamp;    // 走「待审核 → 后台驳回 + 原因」
const revokeUser = 'rev' + stamp;    // 走「已通过 → 驳回后令牌失效」
const PWD = 'audit123456';

function rowExpr(username) {
  return `Array.from(document.querySelectorAll('#ulist tbody tr'))
      .find(r => r.cells[0] && r.cells[0].textContent.trim() === ${JSON.stringify(username)})`;
}

async function waitRow(page, username, timeout = 8000) {
  await page.waitForFunction(
    (u) => Array.from(document.querySelectorAll('#ulist tbody tr'))
      .some(r => r.cells[0] && r.cells[0].textContent.trim() === u),
    username, { timeout });
}

async function waitStatus(page, username, re, timeout = 8000) {
  await page.waitForFunction(
    ({ u, src }) => {
      const row = Array.from(document.querySelectorAll('#ulist tbody tr'))
        .find(r => r.cells[0] && r.cells[0].textContent.trim() === u);
      return !!row && new RegExp(src).test(row.cells[3].textContent);
    },
    { u: username, src: re.source }, { timeout });
}

async function rowInfo(page, username) {
  return page.evaluate((u) => {
    const rows = Array.from(document.querySelectorAll('#ulist tbody tr'));
    const row = rows.find(r => r.cells[0] && r.cells[0].textContent.trim() === u);
    if (!row) return null;
    return {
      status: row.cells[3].textContent.replace(/\s+/g, ' ').trim(),
      bg: getComputedStyle(row).backgroundColor,
      buttons: Array.from(row.querySelectorAll('button')).map(b => b.textContent.trim()),
      hasNoteBox: !!row.querySelector('input[id^="u-note-"]'),
      buttonsAt: Array.from(row.querySelectorAll('button')).map(b => b.textContent.trim()).filter(t => t !== '保存'),
      isFirst: row === rows[0]
    };
  }, username);
}

async function clickRowButton(page, username, label) {
  await page.evaluate(({ u, l }) => {
    const row = Array.from(document.querySelectorAll('#ulist tbody tr'))
      .find(r => r.cells[0] && r.cells[0].textContent.trim() === u);
    if (!row) throw new Error('后台用户列表里没有 ' + u);
    const btn = Array.from(row.querySelectorAll('button')).find(b => b.textContent.trim() === l);
    if (!btn) throw new Error(u + ' 这行没有「' + l + '」按钮');
    btn.click();
  }, { u: username, l: label });
}

(async () => {
  // ---------- 准备：一篇已发布文章（前台登录/注册弹窗挂在文章页上） ----------
  const al = await post('/api/auth/login', { username: 'xiezitai', password: 'xiexiexie' });
  const admin = al.d.token;
  const arts = await get('/api/articles?size=1');
  let slug = '';
  if (arts.d && arts.d.content && arts.d.content.length) slug = arts.d.content[0].slug;
  if (!slug) {
    const a = await post('/api/admin/articles', { title: '审核演示-' + stamp, content: '内容', status: 'PUBLISHED' }, admin);
    slug = a.d.slug;
  }
  check('拿到演示文章 slug', !!slug, slug);

  // ---------- ① 接口层：注册后为待审核 ----------
  const reg1 = await post('/api/auth/register', { username: pendingUser, password: PWD });
  check('注册成功且返回状态 PENDING', reg1.status === 200 && reg1.d && reg1.d.status === 'PENDING', JSON.stringify(reg1.d));
  const login1 = await post('/api/auth/login', { username: pendingUser, password: PWD });
  check('待审核账号即使密码正确也登录失败(403 PENDING_REVIEW)',
    login1.status === 403 && login1.d && login1.d.error === 'PENDING_REVIEW', 'status=' + login1.status + ' body=' + JSON.stringify(login1.d));

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 1000 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });

  // ---------- ② 前台注册弹窗：提交后不自动登录，提示等待审核 ----------
  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  await page.evaluate(() => openAuth('register'));
  await page.fill('#ar-user', uiRegUser);
  await page.fill('#ar-pass', PWD);
  await page.evaluate(() => doAuthRegister());
  await page.waitForFunction(() => /审核/.test(document.getElementById('authMsg').textContent), null, { timeout: 8000 });
  const regMsg = await page.evaluate(() => document.getElementById('authMsg').textContent);
  check('注册弹窗提示等待审核', /审核/.test(regMsg), regMsg);
  check('注册后不再是自动登录状态',
    !(await page.evaluate(() => typeof currentUser === 'string' && currentUser.length > 0)));
  check('注册后自动切到登录页签',
    await page.evaluate(() => document.getElementById('paneLogin').style.display !== 'none'));
  const prefilled = await page.evaluate(() => document.getElementById('au-user').value);
  check('注册后登录框已填好用户名', prefilled === uiRegUser, prefilled);

  // ---------- ③ 前台登录待审核账号：被拦且提示明确 ----------
  await page.evaluate(() => openAuth('login'));
  await page.fill('#au-user', pendingUser);
  await page.fill('#au-pass', PWD);
  await page.evaluate(() => doAuthLogin());
  await page.waitForFunction(() => /审核/.test(document.getElementById('authMsg').textContent), null, { timeout: 8000 });
  const loginMsg = await page.evaluate(() => document.getElementById('authMsg').textContent);
  check('待审核登录被拦并提示等待审核', /等待管理员审核/.test(loginMsg), loginMsg);
  check('被拦后没有登录态',
    await page.evaluate(() => !(typeof currentUser === 'string' && currentUser.length > 0)));

  // ---------- ④ 后台用户页：待审核高亮 + 通过 ----------
  const apage = await ctx.newPage();
  const aerrors = [];
  apage.on('pageerror', e => aerrors.push('PAGEERROR: ' + e.message));
  apage.on('console', m => { if (m.type() === 'error') aerrors.push('CONSOLE.ERROR: ' + m.text()); });
  await apage.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
  await apage.fill('#luser', 'xiezitai');
  await apage.fill('#lpass', 'xiexiexie');
  await apage.evaluate(() => doLogin());
  await apage.waitForFunction(() => document.getElementById('app').style.display === 'block', null, { timeout: 15000 });
  await apage.evaluate(() => {
    switchTab(Array.from(document.querySelectorAll('.tab')).find(b => b.dataset.p === 'users'));
    return loadUsers();
  });
  await waitRow(apage, pendingUser);

  const info = await rowInfo(apage, pendingUser);
  check('后台能看到该用户的行', !!info);
  check('待审核行显示「待审核」', info && /待审核/.test(info.status), info && info.status);
  check('待审核行有高亮底色', info && info.bg === 'rgb(255, 251, 235)', info && info.bg);
  check('待审核行有「通过」「驳回」按钮',
    info && info.buttonsAt.includes('通过') && info.buttonsAt.includes('驳回'), info && info.buttonsAt.join('/'));
  // 待审核的要排在最前（列表里可能还有别处遗留的待审核账号，所以断言「第一行的状态」而不是「这一行第一」）
  const firstStatus = await apage.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#ulist tbody tr'));
    return rows.length ? rows[0].cells[3].textContent.replace(/\s+/g, ' ').trim() : '';
  });
  check('待审核用户排在最前面', /待审核/.test(firstStatus), firstStatus);
  const tip = await apage.evaluate(() => document.getElementById('pendingTip').textContent);
  check('顶部提示显示待审核数量', /等待审核/.test(tip), tip.slice(0, 40));

  await clickRowButton(apage, pendingUser, '通过');
  await waitStatus(apage, pendingUser, /已通过/);
  check('点「通过」后变为已通过', true);
  const afterRow = await rowInfo(apage, pendingUser);
  check('已通过的行不再显示备注框', afterRow && !afterRow.hasNoteBox);

  // ---------- ⑤ 通过后前台登录成功 ----------
  const relogin = await post('/api/auth/login', { username: pendingUser, password: PWD });
  check('审核通过后接口登录成功', relogin.status === 200 && !!relogin.d.token, 'status=' + relogin.status);
  await page.evaluate(() => openAuth('login'));
  await page.fill('#au-user', pendingUser);
  await page.fill('#au-pass', PWD);
  await page.evaluate(() => doAuthLogin());
  await page.waitForFunction(() => typeof currentUser === 'string' && currentUser.length > 0, null, { timeout: 8000 });
  const nowUser = await page.evaluate(() => currentUser);
  check('审核通过后前台登录成功', nowUser === pendingUser, nowUser);

  // ---------- ⑥ 后台 UI 驳回一个待审核账号并填原因 ----------
  await post('/api/auth/register', { username: rejectUser, password: PWD });
  await apage.evaluate(() => loadUsers());
  await waitRow(apage, rejectUser);
  const rinfo = await rowInfo(apage, rejectUser);
  check('待审核的行有「驳回原因」输入框', rinfo && rinfo.hasNoteBox);
  await apage.evaluate((u) => {
    const row = Array.from(document.querySelectorAll('#ulist tbody tr'))
      .find(r => r.cells[0] && r.cells[0].textContent.trim() === u);
    row.querySelector('input[id^="u-note-"]').value = '内容不合规';
  }, rejectUser);
  await clickRowButton(apage, rejectUser, '驳回');
  await waitStatus(apage, rejectUser, /已驳回/);
  check('点「驳回」后变为已驳回', true);
  const rejectRow = await rowInfo(apage, rejectUser);
  check('驳回后仍可改判：出现「通过」按钮', rejectRow && rejectRow.buttonsAt.includes('通过'), rejectRow && rejectRow.buttonsAt.join('/'));

  const rl = await post('/api/auth/login', { username: rejectUser, password: PWD });
  check('驳回账号登录 403 REJECTED 且带原因',
    rl.status === 403 && rl.d && rl.d.error === 'REJECTED' && /内容不合规/.test(rl.d.message || ''),
    'status=' + rl.status + ' body=' + JSON.stringify(rl.d));

  await page.evaluate(() => { logout(); openAuth('login'); });
  await page.fill('#au-user', rejectUser);
  await page.fill('#au-pass', PWD);
  await page.evaluate(() => doAuthLogin());
  await page.waitForFunction(() => /审核|驳回/.test(document.getElementById('authMsg').textContent), null, { timeout: 8000 });
  const rejectMsg = await page.evaluate(() => document.getElementById('authMsg').textContent);
  check('前台登录提示里带驳回原因', /内容不合规/.test(rejectMsg), rejectMsg);

  // ---------- ⑦ 已通过的账号被驳回：此前签发的 token 立即失效 ----------
  await post('/api/auth/register', { username: revokeUser, password: PWD });
  await auditUser(BASE, admin, revokeUser);
  const preToken = (await post('/api/auth/login', { username: revokeUser, password: PWD })).d.token;
  check('驳回前该账号 token 可用', (await get('/api/auth/me', preToken)).status === 200);
  await auditUser(BASE, admin, revokeUser, 'REJECTED', '不再需要');
  check('驳回后此前签发的 token 立即失效', (await get('/api/auth/me', preToken)).status === 401,
    'status=' + (await get('/api/auth/me', preToken)).status);

  try { await apage.screenshot({ path: 'e2e/out/30-user-audit.png', fullPage: true }); } catch (e) { /* C 盘吃紧时截图会失败，非断言项 */ }

  await browser.close();

  const bad = errors.concat(aerrors)
    .filter(e => !/favicon|Failed to load resource.*\b(401|403|423)\b/.test(e));
  check('0 JS 错误', bad.length === 0, bad.join(' | ').slice(0, 200));

  const fails = checks.filter(c => !c.ok).length;
  console.log('\nSUMMARY ' + (checks.length - fails) + '/' + checks.length + ' passed' + (fails ? '  <<<< FAIL' : '  ALL GREEN'));
  process.exit(fails ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
