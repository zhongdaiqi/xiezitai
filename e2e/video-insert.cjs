// 复现并验证：编辑器里插入视频不能变成 ![xxx.mp4](/media/xxx.mp4)
// 三条路径都要验证：① 工具栏「图片」按钮选 mp4（用户报的那条）② 拖 mp4 进编辑器 ③ 图片+视频混选
const { chromium } = require('playwright');
const { enterNewArticle } = require('./lib/admin-ui.cjs');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const PNG = 'e2e/fixtures/e2e-shot.png';
const MP4 = 'e2e/fixtures/sample.mp4';

const checks = [];
function check(name, ok, extra) {
  checks.push({ name, ok: !!ok, extra });
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name + (extra !== undefined ? '   [' + extra + ']' : ''));
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });
  const log = (...a) => console.log(...a);

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

  const mdValue = () => page.evaluate(() => getEditorValue());

  await login();
  log('WHO=' + (await page.textContent('#who')));

  // 工具栏按钮一览（ByteMD 未传 locale，标签是英文，用 tippy 气泡读标题）
  const toolCount = await page.locator('.bytemd-toolbar-icon').count();
  let imgBtnIndex = -1;
  const toolLabels = [];
  for (let i = 0; i < toolCount; i++) {
    try {
      await page.locator('.bytemd-toolbar-icon').nth(i).hover({ timeout: 3000 });
      await page.waitForTimeout(320);
      const tip = await page.evaluate(() => {
        const t = document.querySelector('.tippy-content');
        return t ? (t.textContent || '').trim() : '';
      });
      toolLabels.push(i + ':' + tip);
      if (/^image$/i.test(tip)) imgBtnIndex = i;
    } catch (e) { toolLabels.push(i + ':?'); }
  }
  log('TOOLS=' + toolLabels.join(' | '));
  if (imgBtnIndex < 0) imgBtnIndex = 5;   // ByteMD 默认工具栏里 Image 的固定位置
  check('找到工具栏「图片」按钮', imgBtnIndex >= 0, 'index=' + imgBtnIndex);

  // ---------- 场景 ①：工具栏「图片」按钮选 mp4（用户报的路径） ----------
  const [chooser1] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 10000 }),
    page.locator('.bytemd-toolbar-icon').nth(imgBtnIndex).click()
  ]);
  await chooser1.setFiles([MP4]);
  await page.waitForFunction(
    () => /<video src="\/media\/[^"]+\.mp4"/.test(getEditorValue()),
    { timeout: 60000 }
  ).catch(() => log('WAIT_VIDEO_TIMEOUT'));
  await page.waitForTimeout(600);

  let md = await mdValue();
  log('MD_1=' + JSON.stringify(md));
  check('① 工具栏选 mp4 → 插入 <video> 标签', /<video src="\/media\/[^"]+\.mp4" controls/.test(md));
  check('① 不再出现 ![](xxx.mp4) 图片语法', !/!\[[^\]]*\]\(\/media\/[^)]*\.mp4\)/.test(md), 'bug-guard');
  const vid1 = (md.match(/<video src="([^"]+)"/) || [])[1] || '';
  log('VID_URL=' + vid1);

  // 预览是否真的渲染出 video（净化白名单生效）
  await page.waitForFunction(() => {
    const v = document.querySelector('.bytemd-preview .markdown-body video');
    return !!v && v.videoWidth > 0;
  }, { timeout: 30000 }).catch(() => log('PREVIEW_VIDEO_TIMEOUT'));
  const pv = await page.evaluate(() => {
    const v = document.querySelector('.bytemd-preview .markdown-body video');
    return { exists: !!v, w: v ? v.videoWidth : 0, h: v ? v.videoHeight : 0, ctrl: v ? v.hasAttribute('controls') : false };
  });
  check('① 编辑器预览渲染 video（净化放行生效）', pv.exists && pv.w > 0, pv.w + 'x' + pv.h + ' controls=' + pv.ctrl);
  await page.screenshot({ path: 'e2e/out/11-video-toolbar-insert.png' });

  // ---------- 场景 ②：拖 mp4 进编辑器 ----------
  const beforeCount = (await mdValue()).match(/<video /g)?.length || 0;
  await page.evaluate(async url => {
    const blob = await (await fetch(url)).blob();
    const file = new File([blob], 'dropped-video.mp4', { type: 'video/mp4' });
    const dt = new DataTransfer();
    dt.items.add(file);
    const host = document.getElementById('editor');
    host.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
  }, vid1);
  await page.waitForFunction(
    n => (getEditorValue().match(/<video /g) || []).length > n,
    beforeCount, { timeout: 60000 }
  ).catch(() => log('WAIT_DROP_TIMEOUT'));
  await page.waitForTimeout(500);
  md = await mdValue();
  log('MD_2=' + JSON.stringify(md));
  const afterCount = md.match(/<video /g)?.length || 0;
  check('② 拖 mp4 进编辑器 → 新增一个 <video>（无重复上传）', afterCount === beforeCount + 1, beforeCount + ' -> ' + afterCount);
  check('② 拖拽路径也没出现 ![](xxx.mp4)', !/!\[[^\]]*\]\(\/media\/[^)]*\.mp4\)/.test(md));

  // ---------- 场景 ③：一次混选 图片 + 视频 ----------
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
  await enterNewArticle(page);   // 刷新后回到列表视图，重新进编辑视图
  const [chooser2] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 10000 }),
    page.locator('.bytemd-toolbar-icon').nth(imgBtnIndex).click()
  ]);
  await chooser2.setFiles([PNG, MP4]);
  await page.waitForFunction(() => {
    const v = getEditorValue();
    return /<video src="\/media\/[^"]+\.mp4"/.test(v) && /!\[[^\]]*\]\(\/media\/[^)]+\.png\)/.test(v);
  }, { timeout: 90000 }).catch(() => log('WAIT_MIXED_TIMEOUT'));
  await page.waitForTimeout(600);
  md = await mdValue();
  log('MD_3=' + JSON.stringify(md));
  check('③ 混选：图片插成 Markdown 图片语法', /!\[[^\]]*\]\(\/media\/[^)]+\.png\)/.test(md));
  check('③ 混选：视频插成 <video> 标签', /<video src="\/media\/[^"]+\.mp4" controls/.test(md));
  check('③ 混选：没有任何 ![](xxx.mp4)', !/!\[[^\]]*\]\(\/media\/[^)]*\.mp4\)/.test(md));

  // 发布 → 前台文章页能播
  await page.fill('#a-title', 'E2E 视频插入校验 ' + Date.now());
  await page.selectOption('#a-status', 'PUBLISHED');   // 草稿会被前台 302 重定向回首页
  await page.click('button[onclick="saveArticle()"]');
  await page.waitForFunction(() => !!document.getElementById('a-slug').value, { timeout: 30000 }).catch(() => {});
  const slug = await page.inputValue('#a-slug');
  log('SLUG=' + slug);
  check('发布成功并回填 slug', !!slug);

  if (slug) {
    await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => {
      const v = document.querySelector('.markdown-body video');
      return !!v && v.videoWidth > 0;
    }, { timeout: 30000 }).catch(() => log('PUBLIC_VIDEO_WAIT_TIMEOUT'));
    const pub = await page.evaluate(() => {
      const body = document.querySelector('.markdown-body');
      const v = document.querySelector('.markdown-body video');
      const i = document.querySelector('.markdown-body img');
      return {
        bodyExists: !!body, vid: !!v, vidW: v ? v.videoWidth : 0, vidCtrl: v ? v.hasAttribute('controls') : false,
        img: !!i, imgW: i ? i.naturalWidth : 0,
        imgMdResidue: body ? /!\[[^\]]*\]\(\/media\/[^)]*\.mp4\)/.test(body.innerHTML) : null
      };
    });
    log('PUBLIC=' + JSON.stringify(pub));
    check('前台文章页存在正文容器', pub.bodyExists);
    check('前台文章页 video 可播放（videoWidth>0）', pub.vid && pub.vidW > 0, 'w=' + pub.vidW + ' controls=' + pub.vidCtrl);
    check('前台文章页 图片正常', pub.img && pub.imgW > 0, 'w=' + pub.imgW);
    check('前台正文无 ![](xxx.mp4) 残留', pub.imgMdResidue === false);
    await page.screenshot({ path: 'e2e/out/12-public-video-ok.png', fullPage: true });
  }

  check('0 个页面 JS 错误', errors.length === 0, errors.slice(0, 4).join(' ; '));
  check('0 个 HTTP >= 400', httpBad.length === 0, httpBad.slice(0, 4).join(' ; '));

  await browser.close();

  const failed = checks.filter(c => !c.ok);
  console.log('\n===== 结果：' + (checks.length - failed.length) + '/' + checks.length + ' 通过 =====');
  if (failed.length) { failed.forEach(f => console.log('  FAILED: ' + f.name)); process.exit(1); }
})().catch(e => { console.error('SCRIPT_ERROR', e); process.exit(2); });
