// 封面三种来源端到端验证：① 本地上传 ② 从媒体库选择 ③ 清除 ④ 封面放大预览（灯箱）
// 并验证发布后前台文章页显示封面、og:image 输出绝对地址（封面＝分享图）
const { chromium } = require('playwright');
const { enterNewArticle } = require('./lib/admin-ui.cjs');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const PNG = 'e2e/fixtures/e2e-shot.png';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

const errors = [], httpBad = [];

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => {
    // /api/admin/ai/cover 是第 ③b 步刻意探测的：AI 未配置时后端应答 502 属预期，不算站点错误
    if (r.status() >= 400 && !/\/api\/admin\/ai\/cover/.test(r.url())) httpBad.push(r.status() + ' ' + r.url());
  });
  const log = (...a) => console.log(...a);

  // 用 getByRole + exact 精确定位按钮，避免 text= 的子串匹配歧义
  const btnUpload = () => page.getByRole('button', { name: '本地上传', exact: true });
  const btnPick = () => page.getByRole('button', { name: '从媒体库选择', exact: true });
  const btnClear = () => page.getByRole('button', { name: '清除', exact: true });
  const btnSave = () => page.getByRole('button', { name: '保存', exact: true });

  async function login() {
    await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1200);                    // 等 boot() 自动登录（localStorage 有 token 时）
    const appVisible = await page.isVisible('#app').catch(() => false);
    if (!appVisible) {
      await page.fill('#luser', 'xiezitai');
      await page.fill('#lpass', 'xiexiexie');
      await page.click('#login button');
    }
    await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
    // 文章面板默认落在列表视图 → 先进「+ 新建文章」的编辑视图，编辑器与表单项才存在
    await enterNewArticle(page);
  }

  const coverVal = () => page.inputValue('#a-cover');
  const modalOpen = () => page.evaluate(() => document.getElementById('media-modal').classList.contains('open'));
  async function coverPreview() {
    return page.evaluate(() => {
      const wrap = document.getElementById('cover-preview');
      const img = document.getElementById('cover-preview-img');
      return {
        visible: !!wrap && getComputedStyle(wrap).display !== 'none',
        src: img ? (img.getAttribute('src') || '') : '',
        w: img ? img.naturalWidth : 0
      };
    });
  }
  /** 打开封面弹窗并等网格渲染完（媒体库是异步拉取的，必须等条目出现再断言/点选） */
  async function openCoverPicker() {
    await btnPick().click();
    await page.waitForSelector('#media-modal.open', { timeout: 8000 });
    await page.waitForFunction(() => {
      const dots = document.querySelectorAll('#m-media-grid .mitem');
      return dots.length > 0 || /没有匹配/.test(document.getElementById('m-media-grid').textContent || '');
    }, { timeout: 15000 });
  }
  /** 点媒体库第一张图 → 返回其 url */
  async function pickFirstFromLibrary() {
    await openCoverPicker();
    const first = page.locator('#m-media-grid .mitem').first();
    const url = await first.evaluate(el => { const i = el.querySelector('img'); return i ? i.getAttribute('src') : ''; });
    await first.click();
    await page.waitForFunction(() => !document.getElementById('media-modal').classList.contains('open'), { timeout: 8000 });
    await page.waitForTimeout(200);
    return url;
  }

  await login();
  log('WHO=' + (await page.textContent('#who')));

  // 初始应为空封面
  check('初始封面为空', (await coverVal()) === '', await coverVal());

  // ---------- ① 本地上传 ----------
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 8000 }).catch(() => null),
    btnUpload().click()
  ]);
  check('① 「本地上传」按钮打开文件选择器', !!chooser);
  if (chooser) await chooser.setFiles([PNG]);
  else await page.setInputFiles('#a-cover-input', PNG);   // 兜底：直接喂隐藏 input

  await page.waitForFunction(
    () => /^\/media\/[0-9a-f]+\.(png|jpe?g|gif|webp|avif)$/i.test(document.getElementById('a-cover').value),
    { timeout: 60000 }
  ).catch(() => log('WAIT_UPLOAD_TIMEOUT'));
  await page.waitForFunction(() => {
    const img = document.getElementById('cover-preview-img');
    return !!img && img.naturalWidth > 0;
  }, { timeout: 15000 }).catch(() => log('WAIT_PREVIEW_TIMEOUT'));

  const upCover = await coverVal();
  const pv1 = await coverPreview();
  log('UPLOAD_COVER=' + upCover);
  check('① 上传后封面写入 /media/... 地址', /^\/media\/[0-9a-f]+\.(png|jpe?g|gif|webp|avif)$/i.test(upCover), upCover);
  check('① 封面预览显示且图片加载成功', pv1.visible && pv1.w > 0, 'display=' + pv1.visible + ' naturalWidth=' + pv1.w);
  await page.screenshot({ path: 'e2e/out/13-cover-upload.png' });

  // ---------- ①b 封面放大预览（灯箱） ----------
  const lightbox = () => page.evaluate(() => {
    const box = document.getElementById('cover-lightbox');
    const big = document.getElementById('cover-lightbox-img');
    const cap = document.getElementById('cover-lightbox-cap');
    return {
      open: !!box && box.classList.contains('open'),
      display: box ? getComputedStyle(box).display : '',
      src: big ? (big.getAttribute('src') || '') : '',
      w: big ? big.naturalWidth : 0,
      cap: cap ? (cap.textContent || '') : ''
    };
  });
  const waitLightboxClosed = () => page.waitForFunction(
    () => !document.getElementById('cover-lightbox').classList.contains('open'), { timeout: 5000 });

  await page.click('#cover-preview-img');                      // 点缩略图应弹出大图
  await page.waitForSelector('#cover-lightbox.open', { timeout: 5000 });
  await page.waitForFunction(() => document.getElementById('cover-lightbox-img').naturalWidth > 0,
    { timeout: 10000 }).catch(() => log('WAIT_LIGHTBOX_IMG_TIMEOUT'));
  const lb1 = await lightbox();
  log('LIGHTBOX=' + JSON.stringify(lb1));
  check('①b 点缩略图打开大图预览', lb1.open && lb1.display === 'flex', 'display=' + lb1.display);
  check('①b 大图 src 与封面一致', lb1.src === upCover, lb1.src);
  check('①b 大图加载成功', lb1.w > 0, 'naturalWidth=' + lb1.w);
  check('①b 大图说明含原始尺寸', /×/.test(lb1.cap), lb1.cap);
  await page.screenshot({ path: 'e2e/out/16-cover-lightbox.png' });
  await page.keyboard.press('Escape');
  await waitLightboxClosed();
  check('①b ESC 关闭大图预览', !(await lightbox()).open);

  await page.getByRole('button', { name: '放大预览', exact: true }).click();
  await page.waitForSelector('#cover-lightbox.open', { timeout: 5000 });
  check('①b 「放大预览」按钮同样可打开', (await lightbox()).open);
  await page.mouse.click(20, 20);                              // 点遮罩空白处关闭
  await waitLightboxClosed();
  check('①b 点遮罩空白处关闭大图预览', !(await lightbox()).open);

  // ---------- ② 从媒体库选择 ----------
  await openCoverPicker();
  const modalTitle = (await page.textContent('#media-modal .modal-head h3') || '').trim();
  check('② 弹窗标题切换为「选择封面图」', modalTitle === '选择封面图', modalTitle);
  const tags = await page.evaluate(() => [...document.querySelectorAll('#media-modal .tag')]
    .map(t => ({ kind: t.dataset.kind, visible: t.offsetParent !== null, active: t.classList.contains('active') })));
  log('TAGS=' + JSON.stringify(tags));
  check('② 封面模式只显示「图片」筛选且默认选中', tags.length === 3
    && tags.find(t => t.kind === 'image').visible && tags.find(t => t.kind === 'image').active
    && !tags.find(t => t.kind === 'video').visible, JSON.stringify(tags));
  const thumbKinds = await page.evaluate(() => [...document.querySelectorAll('#m-media-grid .mitem')]
    .map(el => el.querySelector('img') ? 'img' : (el.querySelector('video') ? 'video' : 'other')));
  log('THUMBS=' + JSON.stringify(thumbKinds));
  check('② 媒体库只列出图片条目（无视频）', thumbKinds.length > 0 && thumbKinds.every(k => k === 'img'),
    'count=' + thumbKinds.length + ' kinds=' + JSON.stringify(thumbKinds));
  await page.screenshot({ path: 'e2e/out/14-cover-modal.png' });
  const firstThumb = await page.locator('#m-media-grid .mitem img').first().getAttribute('src');
  await page.locator('#m-media-grid .mitem').first().click();
  await page.waitForFunction(() => !document.getElementById('media-modal').classList.contains('open'), { timeout: 8000 });
  await page.waitForTimeout(200);
  const picked = await coverVal();
  log('PICKED=' + picked + ' (thumb=' + firstThumb + ')');
  check('② 点选媒体库图片 → 设为封面', picked === firstThumb && /^\/media\//.test(picked), picked);
  check('② 选择后弹窗自动关闭', !(await modalOpen()));

  // ---------- ③ 清除 ----------
  await btnClear().click();
  await page.waitForTimeout(200);
  const pv3 = await coverPreview();
  check('③ 「清除」清空封面输入框', (await coverVal()) === '', await coverVal());
  check('③ 「清除」隐藏封面预览', !pv3.visible);
  check('③ 无封面时调用放大不弹大图', await page.evaluate(() => {
    zoomCover();
    return !document.getElementById('cover-lightbox').classList.contains('open');
  }));

  // ---------- ③b AI 封面：只允许 /media/ 站内地址，绝不允许第三方链接 ----------
  // 后端会把 AI 返回的图下载下来存进媒体库（上游给的临时链接过期即裂图）。
  // 未配置大模型时应明确报错，而不是回一个可写进封面的外部 URL。
  // 注意：必须在 admin.html 页面里调（jpost 定义在后台脚本里），所以放在跳转前。
  const aiRes = await page.evaluate(async () => {
    try { return await jpost('/api/admin/ai/cover', { prompt: 'E2E AI 封面校验' }); }
    catch (e) { return { error: 'THROWN:' + (e && e.message) }; }
  });
  log('AI_COVER=' + JSON.stringify(aiRes));
  check('③b AI 封面要么给站内 /media/ 地址，要么明确报错（绝不返回第三方链接）',
    aiRes && aiRes.coverUrl ? /^\/media\//.test(aiRes.coverUrl) : !!(aiRes && aiRes.error),
    JSON.stringify(aiRes));
  check('③b AI 封面失败时不产生 HTTP 5xx 页面 JS 错误崩溃', errors.length === 0, errors.slice(0, 3).join(' ; '));

  // ---------- ④ 重新选择封面 → 发布 → 前台校验 ----------
  const cover4 = await pickFirstFromLibrary();
  const picked2 = await coverVal();
  check('④ 重新选封面成功', picked2 === cover4 && /^\/media\//.test(picked2), picked2);

  const title = 'E2E 封面来源校验 ' + Date.now();
  await page.fill('#a-title', title);
  await page.fill('#a-summary', '封面三来源端到端验证');
  await page.selectOption('#a-status', 'PUBLISHED');
  await btnSave().click();
  await page.waitForFunction(() => !!document.getElementById('a-slug').value, { timeout: 30000 }).catch(() => {});
  const slug = await page.inputValue('#a-slug');
  log('SLUG=' + slug);
  check('④ 文章保存成功并回填 slug', !!slug);

  if (slug) {
    await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => {
      const img = document.querySelector('img.cover');
      return !!img && img.naturalWidth > 0;
    }, { timeout: 30000 }).catch(() => log('WAIT_PUBLIC_COVER_TIMEOUT'));
    const pub = await page.evaluate(() => {
      const img = document.querySelector('img.cover');
      const og = document.querySelector('meta[property="og:image"]');
      const ogt = document.querySelector('meta[property="og:title"]');
      return {
        hasCover: !!img, coverSrc: img ? img.getAttribute('src') : '', coverW: img ? img.naturalWidth : 0,
        ogImage: og ? og.getAttribute('content') : null,
        ogTitle: ogt ? ogt.getAttribute('content') : null
      };
    });
    log('PUBLIC=' + JSON.stringify(pub));
    check('④ 前台文章页显示封面图且可加载', pub.hasCover && pub.coverW > 0, 'src=' + pub.coverSrc + ' w=' + pub.coverW);
    check('④ 封面 src 与所选一致', pub.coverSrc === picked2, pub.coverSrc);
    check('④ og:image 输出绝对地址（站点根 + 封面路径）',
      !!pub.ogImage && /^https?:\/\//.test(pub.ogImage) && pub.ogImage.endsWith(picked2), pub.ogImage);
    check('④ og:title 未受影响', pub.ogTitle === title, pub.ogTitle);
    await page.screenshot({ path: 'e2e/out/15-cover-public.png', fullPage: true });
  }

  check('0 个页面 JS 错误', errors.length === 0, errors.slice(0, 4).join(' ; '));
  check('0 个 HTTP >= 400', httpBad.length === 0, httpBad.slice(0, 4).join(' ; '));

  await browser.close();

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
  if (failed.length) { failed.forEach(f => console.log('  FAILED: ' + f.name)); process.exit(1); }
})().catch(async e => {
  console.error('SCRIPT_ERROR', e && e.message ? e.message : e);
  try {
    // 崩了就把已收集的错误倒出来，便于定位（页面 JS 异常 / HTTP 4xx 往往是真因）
    const errs = (typeof errors !== 'undefined') ? errors : [];
    const bad = (typeof httpBad !== 'undefined') ? httpBad : [];
    errs.slice(0, 8).forEach(x => console.error('  PAGEERR: ' + x));
    bad.slice(0, 8).forEach(x => console.error('  HTTPBAD: ' + x));
  } catch (_) {}
  process.exit(2);
});
