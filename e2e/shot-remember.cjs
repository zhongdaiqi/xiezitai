const { chromium } = require('playwright');
const BASE = process.env.E2E_BASE || 'http://localhost:8080';
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 900, height: 900 }, deviceScaleFactor: 2 });
  const pg = await ctx.newPage();
  // 从首页点进第一篇文章（登录弹窗在 article.html 上）
  await pg.goto(BASE + '/', { waitUntil: 'networkidle' });
  await pg.click('.wrap > article a, article a');
  await pg.waitForLoadState('networkidle');
  await pg.evaluate(() => openAuth('login'));
  await pg.waitForSelector('#authModal.open', { timeout: 5000 });
  await pg.check('#au-remember');
  await pg.screenshot({ path: 'e2e/out/21-remember-modal.png' });
  // 后台登录框
  const pg2 = await ctx.newPage();
  await pg2.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
  await pg2.check('#lremember');
  await pg2.screenshot({ path: 'e2e/out/21-admin-login.png' });
  await browser.close();
  console.log('shots done');
})().catch(e => { console.error('FATAL', e); process.exit(1); });
