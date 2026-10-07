// 评论排版（参考小红书）截图验证：造 3 条已审核评论 + 1 条长评论，截图评论区
const { chromium } = require('playwright');
const { auditUser } = require('./lib/audit.cjs');
const BASE = process.env.E2E_BASE || 'http://localhost:8080';

async function post(path, body, token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(BASE + path, { method: 'POST', headers: h, body: JSON.stringify(body) });
  let d = {}; try { d = await r.json(); } catch (e) {}
  return { status: r.status, d };
}
async function get(path, token) {
  const h = token ? { Authorization: 'Bearer ' + token } : {};
  const r = await fetch(BASE + path, { headers: h });
  return { status: r.status, d: await r.json().catch(() => null) };
}
async function put(path, body, token) {
  const r = await fetch(BASE + path, {
    method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify(body)
  });
  return { status: r.status, d: await r.json().catch(() => null) };
}

(async () => {
  const al = await post('/api/auth/login', { username: 'xiezitai', password: 'xiexiexie' });
  const adminToken = al.d.token;

  const art = await post('/api/admin/articles', {
    title: '评论区排版演示', content: '本文用于查看评论区的排版效果（参考小红书）。', status: 'PUBLISHED'
  }, adminToken);
  const slug = art.d.slug;
  console.log('SLUG=' + slug);

  const seeds = [
    ['linxiaoyu', '排版看着舒服多了，头像和内容错开有层次感。'],
    ['zhang_wei', '这个缩进跟小红书挺像的，读起来不累。'],
    ['chenmo2026', '昵称浅灰、正文深色，主次分明，赞一个。'],
    ['wangfang', '这是一条比较长的评论，用来验证多行换行时的缩进是否一致——如果一行很长很长很长很长很长很长很长很长很长很长很长很长很长，右侧应该自动折行并与正文左边缘对齐，而不是跑到头像下面去。']
  ];
  for (const [u, content] of seeds) {
    const uname = u + (Date.now() % 1000) + Math.floor(Math.random() * 90 + 10);
    await post('/api/auth/register', { username: uname, password: 'test123456' });
    // 新注册用户是「待审核」，登录前先用管理员放行
    await auditUser(BASE, adminToken, uname);
    const l = await post('/api/auth/login', { username: uname, password: 'test123456' });
    if (l.status !== 200) { console.log('LOGIN_FAIL', uname, l); continue; }
    const s = await post('/api/articles/' + slug + '/comments', { content }, l.d.token);
    if (s.status !== 200) { console.log('COMMENT_FAIL', uname, s); continue; }
    console.log('COMMENT_BY=' + uname);
  }

  // 管理员通过全部待审评论
  const all = await get('/api/admin/comments', adminToken);
  for (const c of (all.d || [])) {
    if (c.article && c.article.slug === slug) {
      await put('/api/admin/comments/' + c.id + '/status', { status: 'APPROVED' }, adminToken);
    }
  }
  const pub = await get('/api/articles/' + slug + '/comments');
  console.log('APPROVED_COUNT=' + (Array.isArray(pub.d) ? pub.d.length : -1));

  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 1200 }, deviceScaleFactor: 2 });
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });

  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);

  const stat = await page.evaluate(() => {
    const items = Array.from(document.querySelectorAll('.comments .comment'));
    return items.map(el => {
      const av = el.querySelector('.avatar');
      const body = el.querySelector('.body');
      const text = el.querySelector('.text');
      const cb = el.getBoundingClientRect();
      const tb = text.getBoundingClientRect();
      return {
        initial: av ? av.textContent.trim() : '',
        avatarW: av ? Math.round(av.getBoundingClientRect().width) : 0,
        indent: Math.round(tb.left - cb.left),          // 正文相对评论块左边缘的缩进
        bodyTopAligned: body ? Math.abs(body.getBoundingClientRect().top - cb.top) < 2 : false
      };
    });
  });
  console.log('ITEMS=' + JSON.stringify(stat));

  const el = await page.$('#comments');
  const box = await el.boundingBox();
  await page.screenshot({
    path: 'e2e/out/15-comments-xhs.png',
    clip: { x: Math.max(0, box.x - 20), y: Math.max(0, box.y - 16), width: box.width + 40, height: Math.min(900, box.height + 40) }
  });
  await page.screenshot({ path: 'e2e/out/16-comments-full.png', fullPage: true });

  console.log('ERRORS=' + JSON.stringify(errors));
  await browser.close();
})().catch(e => { console.error('CRASH', e); process.exit(1); });
