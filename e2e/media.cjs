// 文章编辑器「插入媒体」端到端验证：上传图片/视频 → 插入正文 → 预览 → 发布 → 前台播放
const { chromium } = require('playwright');

const BASE = process.env.E2E_BASE || 'http://localhost:8080';
const PNG = 'e2e/fixtures/e2e-shot.png';
const MP4 = 'e2e/fixtures/sample.mp4';

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  const errors = [], httpBad = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE.ERROR: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400) httpBad.push(r.status() + ' ' + r.url()); });

  const log = (...a) => console.log(...a);

  // ---------- 登录 ----------
  await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle' });
  await page.fill('#luser', 'xiezitai');
  await page.fill('#lpass', 'xiexiexie');
  await page.click('#login button');
  await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });
  log('WHO=' + (await page.textContent('#who')));

  // ---------- 插入媒体：打开弹窗 ----------
  await page.click('text=插入媒体');
  await page.waitForSelector('#media-modal.open', { timeout: 5000 });
  await page.waitForTimeout(600);
  log('MODAL_OPEN=true  COUNT=' + (await page.textContent('#m-media-count')));
  await page.screenshot({ path: 'e2e/out/8-media-modal.png' });

  // ---------- 上传图片 + 视频（走 hidden input，等价于点选/拖拽） ----------
  await page.setInputFiles('#m-media-input', [PNG, MP4]);
  await page.waitForFunction(() => !document.getElementById('media-modal').classList.contains('open'),
    { timeout: 60000 });
  await page.waitForTimeout(800);

  const md = await page.evaluate(() => getEditorValue());
  log('MD_HAS_IMAGE_MD=' + /!\[[^\]]*\]\(\/media\/[0-9a-f]+\.png\)/.test(md));
  log('MD_HAS_VIDEO_TAG=' + /<video src="\/media\/[0-9a-f]+\.mp4" controls/.test(md));
  const imgStored = (md.match(/!\[[^\]]*\]\((\/media\/[^)]+)\)/) || [])[1] || '';
  const vidStored = (md.match(/<video src="([^"]+)"/) || [])[1] || '';
  log('IMG_URL=' + imgStored);
  log('VID_URL=' + vidStored);

  // ---------- 编辑器预览是否真的渲染出媒体 ----------
  await page.waitForFunction(() => {
    const img = document.querySelector('.bytemd-preview .markdown-body img');
    const vid = document.querySelector('.bytemd-preview .markdown-body video');
    return !!img && img.naturalWidth > 0 && !!vid && vid.videoWidth > 0;
  }, { timeout: 30000 }).catch(() => log('PREVIEW_WAIT_TIMEOUT'));
  const preview = await page.evaluate(() => {
    const img = document.querySelector('.bytemd-preview .markdown-body img');
    const vid = document.querySelector('.bytemd-preview .markdown-body video');
    return {
      imgOk: !!img && img.naturalWidth > 0, imgW: img ? img.naturalWidth : 0,
      vidExists: !!vid, vidW: vid ? vid.videoWidth : 0, vidH: vid ? vid.videoHeight : 0,
      vidControls: vid ? vid.hasAttribute('controls') : false
    };
  });
  log('PREVIEW=' + JSON.stringify(preview));
  await page.screenshot({ path: 'e2e/out/9-editor-media.png' });

  // ---------- 发布 ----------
  const title = 'E2E 媒体插入 ' + Date.now();
  await page.fill('#a-title', title);
  await page.selectOption('#a-status', 'PUBLISHED');
  await page.click('text=保存');
  await page.waitForTimeout(1200);
  const slug = await page.inputValue('#a-slug');
  log('SLUG=' + slug);
  log('SAVE_MSG=' + (await page.textContent('#aimsg')));

  // ---------- 前台文章页 ----------
  await page.goto(BASE + '/article/' + slug, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => {
    const v = document.querySelector('.markdown-body video');
    return !!v && v.videoWidth > 0;
  }, { timeout: 30000 }).catch(() => log('PUBLIC_VIDEO_WAIT_TIMEOUT'));
  const pub = await page.evaluate(() => {
    const img = document.querySelector('.markdown-body img');
    const v = document.querySelector('.markdown-body video');
    return {
      imgOk: !!img && img.naturalWidth > 0, imgW: img ? img.naturalWidth : 0,
      vidW: v ? v.videoWidth : 0, vidH: v ? v.videoHeight : 0,
      vidControls: v ? v.hasAttribute('controls') : false,
      vidClientW: v ? Math.round(v.getBoundingClientRect().width) : 0,
      imgClientW: img ? Math.round(img.getBoundingClientRect().width) : 0,
      wrapW: Math.round(document.querySelector('.wrap').getBoundingClientRect().width)
    };
  });
  log('PUBLIC=' + JSON.stringify(pub));
  await page.screenshot({ path: 'e2e/out/10-public-media.png', fullPage: true });

  // ---------- Range 直查（真实容器） ----------
  for (const url of [imgStored, vidStored]) {
    if (!url) continue;
    const r = await fetch(BASE + url, { headers: { Range: 'bytes=0-99' } });
    log('RANGE ' + url + ' -> ' + r.status + ' | Content-Range=' + r.headers.get('content-range')
      + ' | Content-Type=' + r.headers.get('content-type') + ' | Accept-Ranges=' + r.headers.get('accept-ranges')
      + ' | bytes=' + (await r.arrayBuffer()).byteLength);
  }

  await page.waitForTimeout(300);
  log('PAGE_ERRORS=' + errors.length);
  errors.slice(0, 10).forEach(e => log('  ! ' + e));
  log('HTTP_BAD=' + httpBad.length);
  httpBad.slice(0, 10).forEach(e => log('  ! ' + e));

  await browser.close();
  console.log('E2E_DONE');
})();
