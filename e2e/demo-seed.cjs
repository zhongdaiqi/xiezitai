// 验证「首次启动写入示例内容」：空库启动后，前台/后台都能直接看到内容。
// 断言：首页 4 篇文章 + 导航入口、文章页排版与两级评论、关于页、后台待审评论，
//      以及全程无 JS 错误 / 无 4xx-5xx。顺便截图，用于人工核对视觉效果。
const { chromium } = require('playwright');
const path = require('path');
const BASE = process.env.E2E_BASE || 'http://localhost:8080';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}
const shot = n => 'e2e/out/' + n;

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1000, height: 900 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) errors.push('HTTP ' + r.status() + ' ' + r.url()); });

  /* ---------------- 首页 ---------------- */
  const resp = await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  check('首页 200', resp.status() === 200, 'status=' + resp.status());
  const homeText = await page.innerText('body');
  for (const t of ['欢迎来到写字台', 'Markdown 写作速查', '自托管博客', '部署与维护清单']) {
    check('首页出现《' + t + '》', homeText.includes(t));
  }
  const articleCount = (await page.$$('article')).length;
  check('首页文章条数 = 4', articleCount === 4, 'count=' + articleCount);
  check('首页摘要里有任务清单复选框', (await page.$$('article input[type=checkbox]')).length > 0);

  // 根级 slug 是兜底路由（/{slug}），绝不能被它吃掉根级静态文件 —— 否则整个后台打不开
  const adminSt = await page.request.get(BASE + '/admin.html');
  check('根级静态文件 /admin.html 未被 slug 路由吞掉', adminSt.status() === 200, 'status=' + adminSt.status());
  const iconSt = await page.request.get(BASE + '/favicon.svg');
  check('根级静态文件 /favicon.svg 未被 slug 路由吞掉', iconSt.status() === 200, 'status=' + iconSt.status());

  const navHrefs = await page.$$eval('nav a', as => as.map(a => a.textContent.trim() + '|' + a.getAttribute('href')));
  check('导航含「关于」→ /about', navHrefs.includes('关于|/about'), navHrefs.join(' '));
  check('导航含「友链」→ /links', navHrefs.includes('友链|/links'));
  await page.screenshot({ path: shot('demo-home.png'), fullPage: true });

  /* ---------------- 文章页 ----------------
     故意先走历史地址 /article/welcome：顺带验证 301 到根级规范地址（真浏览器才测得出重定向链路） */
  await page.goto(BASE + '/article/welcome', { waitUntil: 'domcontentloaded' });
  check('历史 /article/welcome 已 301 到 /welcome', page.url() === BASE + '/welcome', page.url());
  const artText = await page.innerText('body');
  check('文章页含二级标题「这是什么」', artText.includes('这是什么'));
  check('文章页表格已渲染', (await page.$$('.markdown-body table')).length > 0);
  const artBoxes = (await page.$$('.markdown-body input[type=checkbox]')).length;
  check('文章页任务清单 ≥ 6 个复选框', artBoxes >= 6, 'count=' + artBoxes);
  check('代码块 / 引用块存在', (await page.$$('.markdown-body blockquote')).length > 0);
  check('评论区显示一级评论作者（夜航船）', artText.includes('夜航船'));
  check('回复显示「回复 @」', artText.includes('回复 @'));
  check('待审核评论不出现在前台', !artText.includes('请问支持画流程图吗'));
  const cmtCount = (await page.$$('.comment')).length;
  check('评论条目数 = 4（2 一级 + 2 回复）', cmtCount === 4, 'count=' + cmtCount);
  const topNav = await page.$$eval('.top a', as => as.map(a => a.textContent.trim()));
  check('文章页顶部有页面入口', topNav.includes('关于') && topNav.includes('友链'), topNav.join(' , '));
  await page.screenshot({ path: shot('demo-article.png'), fullPage: true });

  /* ---------------- 自定义页面 ---------------- */
  await page.goto(BASE + '/about', { waitUntil: 'domcontentloaded' });
  const aboutText = await page.innerText('body');
  check('关于页渲染正常', aboutText.includes('关于这个站点'));
  const aboutNav = await page.$$eval('.top a', as => as.map(a => a.textContent.trim()));
  check('关于页导航含友链', aboutNav.includes('友链'), aboutNav.join(' , '));
  await page.screenshot({ path: shot('demo-page.png'), fullPage: true });

  /* ---------------- 后台 ---------------- */
  const login = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'xiezitai', password: 'xiexiexie' })
  }).then(r => r.json());
  const ah = { Authorization: 'Bearer ' + login.token };

  const allComments = await fetch(BASE + '/api/admin/comments', { headers: ah }).then(r => r.json());
  const pending = (Array.isArray(allComments) ? allComments : []).filter(c => c.status === 'PENDING');
  check('后台评论总数 = 5', allComments.length === 5, 'len=' + allComments.length);
  check('后台待审核 = 1', pending.length === 1, 'len=' + pending.length);

  const adminArticles = await fetch(BASE + '/api/admin/articles', { headers: ah }).then(r => r.json());
  const artList = Array.isArray(adminArticles) ? adminArticles : (adminArticles.content || []);
  check('后台文章列表 = 4', artList.length === 4, 'len=' + artList.length);

  const pages = await fetch(BASE + '/api/admin/pages', { headers: ah }).then(r => r.json()).catch(() => null);
  if (Array.isArray(pages)) check('后台页面列表 = 2', pages.length === 2, 'len=' + pages.length);

  check('全程无 JS 错误 / 无 4xx-5xx', errors.length === 0, errors.slice(0, 6).join(' ; '));

  await browser.close();
  const failed = checks.filter(c => !c.ok);
  console.log('\n==== ' + (checks.length - failed.length) + '/' + checks.length + ' passed ====');
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error('FATAL ' + e.message); process.exit(2); });
