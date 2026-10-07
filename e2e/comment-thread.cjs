// 验证评论层级：① 服务端渲染出 一级 + 缩进回复（「回复 @某人」）② 登录后点「回复」内联表单提交
// ③ 回复的回复被收敛到同一根评论下 ④ 缩进阶梯真实存在 ⑤ 0 JS 错误
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
const put = (p, b, t) => req('PUT', p, b, t);
const get = (p, t) => req('GET', p, null, t);

const suffix = Date.now() % 100000;

(async () => {
  const al = await post('/api/auth/login', { username: 'xiezitai', password: 'xiexiexie' });
  const admin = al.d.token;

  const art = await post('/api/admin/articles', {
    title: '评论层级演示', content: '本文用于验证评论的两级层级。', status: 'PUBLISHED'
  }, admin);
  const slug = art.d.slug;
  console.log('SLUG=' + slug);

  async function mkUser(prefix) {
    const u = prefix + suffix;
    await post('/api/auth/register', { username: u, password: 'lv123456' });
    // 新注册用户是「待审核」，登录前先用管理员放行
    await auditUser(BASE, admin, u);
    const l = await post('/api/auth/login', { username: u, password: 'lv123456' });
    return { name: u, token: l.d.token };
  }
  const A = await mkUser('ah');
  const B = await mkUser('bh');
  const C = await mkUser('ch');
  console.log('USERS=' + [A.name, B.name, C.name].join(','));

  // 每发一条就重新拉一次后台列表：列表是快照，不能复用（否则新评论 id 查不到）
  const idOf = async txt => {
    const all = await get('/api/admin/comments', admin);
    const hit = (all.d || []).find(c => c.article && c.article.slug === slug && c.content === txt);
    return hit && hit.id;
  };

  const r1 = await post('/api/articles/' + slug + '/comments', { content: '一级评论：这套层级看着清爽。' }, A.token);
  if (r1.status !== 200) { console.error('一级评论失败', r1); process.exit(1); }
  const rootId = await idOf('一级评论：这套层级看着清爽。');

  // B 回复一级评论
  const r2 = await post('/api/articles/' + slug + '/comments',
    { content: '回复一级：同意，跟小红书一样。', parentId: String(rootId) }, B.token);
  if (r2.status !== 200) { console.error('回复失败', r2); process.exit(1); }
  const rep1Id = await idOf('回复一级：同意，跟小红书一样。');
  console.log('IDS root=' + rootId + ' reply=' + rep1Id);
  check('id 均取到', !!rootId && !!rep1Id, rootId + '/' + rep1Id);

  // C 回复 B 的回复 —— 应被收敛到 rootId 下
  const r3 = await post('/api/articles/' + slug + '/comments',
    { content: '回复二楼：+1', parentId: String(rep1Id) }, C.token);
  check('回复的回复提交成功', r3.status === 200, 'status=' + r3.status);
  const rep2Id = await idOf('回复二楼：+1');

  // 三条一起审核通过
  for (const id of [rootId, rep1Id, rep2Id]) await put('/api/admin/comments/' + id + '/status', { status: 'APPROVED' }, admin);

  const tree = await get('/api/articles/' + slug + '/comments');
  check('公开接口返回树形（1 个根）', Array.isArray(tree.d) && tree.d.length === 1, 'roots=' + (tree.d || []).length);
  check('根下挂 2 条回复', tree.d[0] && tree.d[0].replies.length === 2,
    tree.d[0] ? tree.d[0].replies.length : 'n/a');
  check('回复的回复 parentId 被收敛到根', tree.d[0] && tree.d[0].replies[1].parentId === rootId,
    tree.d[0] ? tree.d[0].replies[1].parentId : 'n/a');
  check('回复的回复仍标注「回复 @B」', tree.d[0] && tree.d[0].replies[1].replyToName === B.name,
    tree.d[0] ? tree.d[0].replies[1].replyToName : 'n/a');

  // ---------- 浏览器 ----------
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 1100 }, deviceScaleFactor: 2 });
  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);

  const layout = await page.evaluate(() => {
    const root = document.querySelector('.comments > .comment');
    if (!root) return null;
    const rootText = root.querySelector(':scope > .body > .text');
    const replies = Array.from(root.querySelectorAll(':scope > .body > .replies > .comment'));
    const r0 = replies[0];
    const r0Text = r0 ? r0.querySelector(':scope > .body > .text') : null;
    const at = r0 ? r0.querySelector('.at') : null;
    return {
      replyCount: replies.length,
      rootTextLeft: Math.round(rootText.getBoundingClientRect().left),
      replyTextLeft: r0Text ? Math.round(r0Text.getBoundingClientRect().left) : 0,
      replyAvatarW: r0 ? Math.round(r0.querySelector('.avatar').getBoundingClientRect().width) : 0,
      atText: at ? at.textContent.trim() : '',
      replyBtnVisible: !!document.querySelector('.reply-btn') &&
        getComputedStyle(document.querySelector('.reply-btn')).display !== 'none'
    };
  });
  check('前台渲染出 2 条回复', layout && layout.replyCount === 2, layout ? layout.replyCount : 'n/a');
  check('回复内容相对一级内容有缩进阶梯',
    layout && layout.replyTextLeft > layout.rootTextLeft,
    layout ? (layout.rootTextLeft + ' -> ' + layout.replyTextLeft) : 'n/a');
  check('回复用更小的头像（26px）', layout && layout.replyAvatarW === 26, layout ? layout.replyAvatarW : 'n/a');
  check('回复行显示「回复 @xxx」', layout && /^回复 @/.test(layout.atText || ''), layout ? layout.atText : 'n/a');
  check('未登录时「回复」按钮隐藏', layout && layout.replyBtnVisible === false);

  // 登录 A 后「回复」按钮出现，点开内联表单并提交
  await page.click('.userbox button');
  await page.waitForSelector('#authModal.open', { timeout: 5000 });
  await page.fill('#au-user', A.name);
  await page.fill('#au-pass', 'lv123456');
  await page.click('button[onclick="doAuthLogin()"]');
  await page.waitForFunction(() => /👤/.test((document.getElementById('userBox') || {}).textContent || ''),
    { timeout: 15000 });
  await page.waitForTimeout(400);

  const btnVisible = await page.evaluate(() =>
    getComputedStyle(document.querySelector('.reply-btn')).display !== 'none');
  check('登录后「回复」按钮出现', btnVisible);

  await page.click('.reply-btn');
  await page.waitForSelector('.reply-form textarea', { timeout: 5000 });
  const ph = await page.getAttribute('.reply-form textarea', 'placeholder');
  check('回复表单 placeholder 带被回复人', /回复 @/.test(ph || ''), ph);

  await page.fill('.reply-form textarea', '我也来回复一条，看看提交流程。');
  await page.click('.reply-form button[type=submit]');
  await page.waitForFunction(() => {
    const m = document.querySelector('.reply-form .msg');
    return m && m.textContent.trim().length > 0;
  }, { timeout: 10000 }).catch(() => {});
  const rmsg = (await page.textContent('.reply-form .msg').catch(() => '')).trim();
  check('回复提交后提示待审核', /待审|已提交/.test(rmsg), rmsg);

  await page.screenshot({ path: 'e2e/out/17-comment-thread.png', fullPage: true });

  const realErrors = errors.filter(e => !/favicon/.test(e) && !/401/.test(e));
  const realHttpBad = httpBad.filter(x => !/401/.test(x) && !/favicon/.test(x));
  check('无 JS 错误', realErrors.length === 0, realErrors.join(' ;; '));
  check('无意外 HTTP>=400', realHttpBad.length === 0, realHttpBad.join(' ;; '));

  await browser.close();
  const failed = checks.filter(c => !c.ok);
  console.log('\n===== SUMMARY: ' + (checks.length - failed.length) + '/' + checks.length + ' passed =====');
  process.exit(failed.length ? 2 : 0);
})().catch(e => { console.error('E2E CRASH', e); process.exit(3); });
