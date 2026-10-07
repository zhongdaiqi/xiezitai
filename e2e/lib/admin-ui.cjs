/* 后台「文章」面板的视图切换助手。
 *
 * 背景（2026-10-07 改版）：文章列表与「发布/编辑文章」不再同屏 ——
 *   ① 登录后默认落在**列表视图**（#av-list 可见、#ev-edit 隐藏）
 *   ② 点「+ 新建文章」(#a-new) 或列表行内「编辑」才进**编辑视图**
 *   ③ 保存成功后提示并自动回到列表
 * 于是 #editor / #a-title / #a-keywords 这些表单元素**不再一登录就可见**，
 * 所有需要写文章的后台脚本都得先切到编辑视图 —— 统一用这里的两个助手，
 * 免得每个脚本各写一遍点击 + 等待（文案/结构再变时只改一处）。
 */

/** 进入「写新文章」编辑视图，并等 ByteMD 挂载完成。 */
async function enterNewArticle(page) {
  await page.waitForSelector('#av-list', { state: 'visible', timeout: 15000 });
  await page.click('#a-new');
  await page.waitForSelector('#ev-edit', { state: 'visible', timeout: 10000 });
  await page.waitForSelector('#editor .CodeMirror', { timeout: 15000 });
  await page.waitForTimeout(300);
}

/** 点「← 返回列表」回到列表视图。 */
async function backToList(page) {
  await page.click('#a-back');
  await page.waitForSelector('#av-list', { state: 'visible', timeout: 10000 });
  await page.waitForTimeout(200);
}

module.exports = { enterNewArticle, backToList };
