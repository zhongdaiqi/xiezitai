/* TOTP 扫码绑定 UI 端到端验证 */
const { chromium } = require('playwright');
const crypto = require('crypto');

function base32Decode(s) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, val = 0; const out = [];
  for (const c of s.toUpperCase().replace(/=+$/, '')) {
    const i = A.indexOf(c); if (i < 0) continue;
    val = (val << 5) | i; bits += 5;
    if (bits >= 8) { out.push((val >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}
function totpCode(secret) {
  const counter = Math.floor(Date.now() / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeUInt32BE(Math.floor(counter / 0x100000000), 0);
  buf.writeUInt32BE(counter >>> 0, 4);
  const h = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const o = h[h.length - 1] & 0x0f;
  const bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
  return String(bin % 1000000).padStart(6, '0');
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  const errs = [];
  page.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text()); });

  await page.goto((process.env.E2E_BASE || 'http://localhost:8080') + '/admin.html', { waitUntil: 'networkidle' });
  await page.fill('#luser', 'xiezitai');
  await page.fill('#lpass', 'xiexiexie');
  await page.click('#login button');
  await page.waitForSelector('#app', { state: 'visible', timeout: 15000 });

  await page.click('.tab[data-p="settings"]');
  await page.waitForTimeout(300);

  console.log('STEP click 开启 TOTP');
  await page.click('#p-settings button:has-text("开启 TOTP")');
  await page.waitForSelector('#totpbox', { state: 'visible', timeout: 10000 });

  const info = await page.evaluate(() => {
    const img = document.getElementById('totpqr');
    return {
      srcHead: (img.getAttribute('src') || '').slice(0, 30),
      naturalW: img.naturalWidth, naturalH: img.naturalHeight,
      secret: document.getElementById('totpsecret').textContent
    };
  });
  console.log('QR_SRC=' + info.srcHead);
  console.log('QR_RENDERED=' + info.naturalW + 'x' + info.naturalH);
  console.log('SECRET_LEN=' + info.secret.length);
  await page.screenshot({ path: 'e2e/out/6-totp-qr.png', fullPage: true });

  console.log('STEP 扫描等价操作：用密钥算出动态码并确认');
  const code = totpCode(info.secret);
  console.log('CODE=' + code);
  await page.fill('#totpcode', code);
  await page.click('#p-settings button:has-text("确认开启")');
  await page.waitForTimeout(800);
  const msg = await page.textContent('#totpmsg');
  const boxHidden = await page.evaluate(() => getComputedStyle(document.getElementById('totpbox')).display === 'none');
  console.log('ENABLE_MSG=' + msg.trim());
  console.log('BOX_HIDDEN_AFTER_ENABLE=' + boxHidden);

  // 清理：关闭 TOTP，避免影响后续验证
  await page.click('#p-settings button:has-text("关闭 TOTP")');
  await page.waitForTimeout(800);
  console.log('DISABLE_MSG=' + (await page.textContent('#totpmsg')).trim());

  console.log('PAGE_ERRORS=' + errs.length);
  errs.forEach(e => console.log(e));
  await browser.close();
})();
