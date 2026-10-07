// 验证修改密码功能：
// ① 前台 article.html 登录后出现「修改密码」按钮，弹窗可改密（原密码校验 / 两次一致 / 成功后旧密码失效）
// ② 后台 admin.html「安全」页有修改密码表单且可用
// ③ 修改密码后旧 token 仍有效（JWT 无吊销，属预期），旧密码登录 401、新密码登录 200
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
const uname = 'pwduser' + stamp;

(async () => {
  // ---------- 注册前台测试用户 ----------
  const reg = await post('/api/auth/register', { username: uname, password: 'oldpass66' });
  check('注册测试用户', reg.status === 200, 'status=' + reg.status);

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 1200 }, deviceScaleFactor: 2 });
  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  // ---------- 前台：登录 → 修改密码弹窗 ----------
  // 找一篇已发布文章（没有就造一篇）
  const al = await post('/api/auth/login', { username: 'xiezitai', password: 'xiexiexie' });
  const admin = al.d.token;
  // 新注册用户是「待审核」，先用管理员放行才能登录
  await auditUser(BASE, admin, uname);
  let slug = '';
  const arts = await req('GET', '/api/articles?size=5');
  if (arts.d && arts.d.content && arts.d.content.length) slug = arts.d.content[0].slug;
  if (!slug) {
    const a = await post('/api/admin/articles', { title: '改密演示-' + stamp, content: '内容', status: 'PUBLISHED' }, admin);
    slug = a.d.slug;
  }
  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });

  // 前台登录弹窗登录
  await page.evaluate(() => openAuth('login'));
  await page.fill('#au-user', uname);
  await page.fill('#au-pass', 'oldpass66');
  await page.evaluate(() => doAuthLogin());
  await page.waitForFunction(() => typeof currentUser === 'string' && currentUser.length > 0, null, { timeout: 8000 });
  check('前台登录成功', true, uname);

  // 登录后出现「修改密码」按钮，点开弹窗
  const hasPwdBtn = await page.evaluate(() => !!Array.from(document.querySelectorAll('#userBox button')).find(b => /修改密码/.test(b.textContent)));
  check('登录后显示「修改密码」按钮', hasPwdBtn);
  await page.evaluate(() => openPwd());
  await page.waitForSelector('#pwdModal.open', { timeout: 5000 });

  // 两次新密码不一致 → 前端拦截，不发请求
  await page.fill('#pw-old', 'oldpass66');
  await page.fill('#pw-new', 'newpass77');
  await page.fill('#pw-new2', 'newpass88');
  await page.evaluate(() => doChangePwd());
  const mismatchMsg = await page.evaluate(() => document.getElementById('pwdMsg').textContent);
  check('两次输入不一致被拦截', /不一致/.test(mismatchMsg), mismatchMsg);

  // 原密码错误 → 服务端 400，弹窗内提示
  await page.fill('#pw-old', 'wrongpass1');
  await page.fill('#pw-new', 'newpass77');
  await page.fill('#pw-new2', 'newpass77');
  await page.evaluate(() => doChangePwd());
  await page.waitForFunction(() => /原密码错误/.test(document.getElementById('pwdMsg').textContent), null, { timeout: 8000 });
  check('原密码错误有提示', true);

  // 正确修改 → 成功提示 + 弹窗自动关闭
  await page.fill('#pw-old', 'oldpass66');
  await page.fill('#pw-new', 'newpass77');
  await page.fill('#pw-new2', 'newpass77');
  await page.evaluate(() => doChangePwd());
  await page.waitForFunction(() => /密码已修改/.test(document.getElementById('pwdMsg').textContent), null, { timeout: 8000 });
  check('修改成功有提示', true);
  await page.waitForFunction(() => !document.getElementById('pwdModal').classList.contains('open'), null, { timeout: 5000 });
  check('成功后弹窗自动关闭', true);
  await page.screenshot({ path: 'e2e/out/20-pwd-modal.png' });

  // ---------- 服务端验证：旧密码 401，新密码 200 ----------
  const oldLogin = await post('/api/auth/login', { username: uname, password: 'oldpass66' });
  check('旧密码登录 401', oldLogin.status === 401, 'status=' + oldLogin.status);
  const newLogin = await post('/api/auth/login', { username: uname, password: 'newpass77' });
  check('新密码登录 200', newLogin.status === 200, 'status=' + newLogin.status);

  // ---------- 后台 admin.html 的修改密码表单（独立 context，避免前台 token 干扰） ----------
  const ctx2 = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const pg2 = await ctx2.newPage();
  pg2.on('pageerror', e => errors.push('ADMIN PAGEERROR: ' + e.message));
  await pg2.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
  await pg2.fill('#luser', 'xiezitai');
  await pg2.fill('#lpass', 'xiexiexie');
  await pg2.evaluate(() => doLogin());
  await pg2.waitForFunction(() => document.getElementById('app').style.display === 'block', null, { timeout: 15000 });
  await pg2.evaluate(() => { document.querySelector('.tab[data-p=settings]').click(); });
  await pg2.waitForFunction(() => {
    const el = document.getElementById('pwdold');
    return !!(el && el.offsetParent !== null);
  }, null, { timeout: 5000 });
  const adminFormVisible = true;
  check('后台「安全」页有修改密码表单', adminFormVisible);
  // 两次不一致拦截（不动管理员真实密码）
  await pg2.fill('#pwdold', 'xiexiexie');
  await pg2.fill('#pwdnew', 'abc12345');
  await pg2.fill('#pwdnew2', 'abc99999');
  await pg2.evaluate(() => changePassword());
  const adminMsg = await pg2.evaluate(() => document.getElementById('pwdmsg').textContent);
  check('后台两次不一致被拦截', /不一致/.test(adminMsg), adminMsg);

  await browser.close();

  // ---------- 汇总 ----------
  // 故意触发的「原密码错误 400」会让浏览器打一条资源加载错误，属预期噪声；
  // 真实错误由 HTTP≥400 检查兜底
  const bad = errors.filter(e => !/favicon|Failed to load resource.*\b400\b/.test(e));
  check('0 JS 错误', bad.length === 0, bad.join(' | ').slice(0, 200));
  const badHttp = httpBad.filter(u => !/favicon|400\s.*password.*原密码|400\s.*api\/auth\/password/.test(u));
  check('HTTP≥400 仅预期的改密 400', badHttp.length === 0, badHttp.join(' | ').slice(0, 200));

  const fails = checks.filter(c => !c.ok).length;
  console.log('\nSUMMARY ' + (checks.length - fails) + '/' + checks.length + ' passed' + (fails ? '  <<<< FAIL' : '  ALL GREEN'));
  process.exit(fails ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
