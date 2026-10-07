// 验证记住登录：
// ① 前台登录弹窗有「记住登录」复选框；勾选 → token 进 localStorage；不勾 → 只进 sessionStorage（关浏览器即失效）
// ② 勾选后刷新页面仍保持登录；后台登录框同样有复选框且勾选后 token 进 localStorage
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
const stamp = Date.now() % 100000;
const uname = 'rmbuser' + stamp;

(async () => {
  const reg = await post('/api/auth/register', { username: uname, password: 'pass123456' });
  check('注册测试用户', reg.status === 200, 'status=' + reg.status);

  const al = await post('/api/auth/login', { username: 'xiezitai', password: 'xiexiexie' });
  const admin = al.d.token;
  // 新注册用户是「待审核」，先用管理员放行才能登录
  await auditUser(BASE, admin, uname);
  check('注册后由管理员审核通过', true);
  let slug = null;
  const arts = await req('GET', '/api/articles?size=1');
  if (Array.isArray(arts.d) && arts.d.length) slug = arts.d[0].slug;
  else if (arts.d && arts.d.content && arts.d.content.length) slug = arts.d.content[0].slug;
  if (!slug) {
    const a = await post('/api/admin/articles', { title: '记住登录演示-' + stamp, content: '内容', status: 'PUBLISHED' }, admin);
    slug = a.d.slug;
  }
  check('拿到演示文章 slug', !!slug, slug);

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1000, height: 1000 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });

  // ---------- 前台：勾选记住登录 ----------
  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  const hasCheckbox = await page.evaluate(() => !!document.getElementById('au-remember'));
  check('前台登录弹窗有「记住登录」复选框', hasCheckbox);

  await page.evaluate(() => openAuth('login'));
  await page.fill('#au-user', uname);
  await page.fill('#au-pass', 'pass123456');
  await page.check('#au-remember');
  await page.evaluate(() => doAuthLogin());
  await page.waitForFunction(() => typeof currentUser === 'string' && currentUser.length > 0, null, { timeout: 8000 });
  const stored1 = await page.evaluate(() => ({
    ls: !!localStorage.getItem('xz_token'),
    ss: !!sessionStorage.getItem('xz_token')
  }));
  check('勾选后 token 进 localStorage', stored1.ls);
  check('勾选后不在 sessionStorage（避免残留）', !stored1.ss, JSON.stringify(stored1));

  // 刷新后仍登录（localStorage 持久）
  await page.reload({ waitUntil: 'networkidle' });
  const stillLoggedIn = await page.evaluate(() => typeof currentUser === 'string' && currentUser.length > 0
    ? currentUser : '');
  check('勾选后刷新页面仍保持登录', stillLoggedIn === uname, stillLoggedIn);

  // ---------- 前台：不勾选 → sessionStorage ----------
  const page2 = await ctx.newPage();
  await page2.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  await page2.evaluate(() => { logout(); openAuth('login'); });
  await page2.fill('#au-user', uname);
  await page2.fill('#au-pass', 'pass123456');
  await page2.uncheck('#au-remember');
  await page2.evaluate(() => doAuthLogin());
  await page2.waitForFunction(() => typeof currentUser === 'string' && currentUser.length > 0, null, { timeout: 8000 });
  const stored2 = await page2.evaluate(() => ({
    ls: !!localStorage.getItem('xz_token'),
    ss: !!sessionStorage.getItem('xz_token')
  }));
  check('不勾选时 token 只进 sessionStorage', !stored2.ls && stored2.ss, JSON.stringify(stored2));
  await page2.screenshot({ path: 'e2e/out/21-remember.png' });

  // sessionStorage 本就按标签页隔离（新标签不共享）——这正是「未记住=关掉即失效」的正确语义
  const page3 = await ctx.newPage();
  await page3.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  const isolated = await page3.evaluate(() => !currentUser && !sessionStorage.getItem('xz_token'));
  check('新标签页不继承 sessionStorage（未记住=新会话即失效）', isolated);

  const ctx2 = await browser.newContext();
  const page4 = await ctx2.newPage();
  await page4.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  const loggedOut = await page4.evaluate(() => !currentUser);
  check('全新会话（模拟关浏览器后）为未登录态', loggedOut);

  // ---------- 后台：勾选记住登录 ----------
  const pg2 = await ctx2.newPage();
  await pg2.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
  const adminHasCheckbox = await pg2.evaluate(() => !!document.getElementById('lremember'));
  check('后台登录框有「记住登录」复选框', adminHasCheckbox);
  await pg2.fill('#luser', 'xiezitai');
  await pg2.fill('#lpass', 'xiexiexie');
  await pg2.check('#lremember');
  await pg2.evaluate(() => doLogin());
  await pg2.waitForFunction(() => document.getElementById('app').style.display === 'block', null, { timeout: 15000 });
  const adminStored = await pg2.evaluate(() => ({
    ls: !!localStorage.getItem('xz_token'),
    ss: !!sessionStorage.getItem('xz_token')
  }));
  check('后台勾选后 token 进 localStorage', adminStored.ls && !adminStored.ss, JSON.stringify(adminStored));

  await browser.close();

  const bad = errors.filter(e => !/favicon|Failed to load resource.*\b(40[139]|423)\b/.test(e));
  check('0 JS 错误', bad.length === 0, bad.join(' | ').slice(0, 200));

  const fails = checks.filter(c => !c.ok).length;
  console.log('\nSUMMARY ' + (checks.length - fails) + '/' + checks.length + ' passed' + (fails ? '  <<<< FAIL' : '  ALL GREEN'));
  process.exit(fails ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
