/*
 * 假大模型上游 —— 离线 E2E 专用（不依赖 ModelScope 等真实第三方服务）。
 *
 * 为什么要它：真实大模型有网络延迟、限流与配额，且输出不可预期，E2E 会变成「时好时坏」。
 * 这里用本机 http 服务冒充 OpenAI 兼容上游，输出**固定且故意脏**的内容，
 * 这样「清洗逻辑 + 前端回填」才有确定性的断言基准。
 *
 * 用法：
 *   1) node e2e/lib/fake-ai.cjs                      # 默认监听 127.0.0.1:8123
 *   2) 启动被测实例时把大模型指过来：
 *      XIEZITAI_AI_BASE_URL=http://127.0.0.1:8123/v1
 *      XIEZITAI_AI_API_KEY=fake-key
 *      XIEZITAI_AI_MODEL=fake-chat
 *      XIEZITAI_AI_IMAGE_MODEL=fake-image
 *      ⚠️ sys_configs 里的 ai.* 只在「不存在」时由 DataInitializer 写入，
 *         所以必须用全新的空库（见 e2e/README.md），否则环境变量不生效。
 */
const http = require('http');

const PORT = Number(process.env.FAKE_AI_PORT || 8123);

// 1×1 PNG（与 XiezitaiApplicationTests.PNG_1X1 同一份），够媒体库的魔数嗅探用
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=',
  'base64');

/** 按 prompt 关键词返回对应的（故意带格式问题的）脏输出 */
function chatReply(prompt) {
  if (prompt.indexOf('SEO 关键词') >= 0) {
    return '关键词：1. 写字台、2. 自托管博客\n3. 内容管理';   // 带标签 + 带序号的列表
  }
  if (prompt.indexOf('Meta Description') >= 0) {
    return '「写字台是一套开箱即用的自托管博客系统，支持文章、页面、评论、媒体管理与 SEO 优化。」';
  }
  if (prompt.indexOf('润色') >= 0) {
    return '（假上游）这是润色后的正文。';
  }
  if (prompt.indexOf('摘要') >= 0) {
    return '这是一段由假上游生成的摘要。';
  }
  return '"优化后的标题：写字台 · 自托管博客系统"';            // 带包裹引号 + 「优化后的标题：」标签
}

function sendJson(res, obj) {
  const buf = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': buf.length });
  res.end(buf);
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks).toString('utf8');

    if (req.method === 'POST' && req.url.endsWith('/chat/completions')) {
      let prompt = '';
      try {
        prompt = (JSON.parse(body).messages || []).map(m => m.content).join('\n');
      } catch (_) { /* 非法 JSON 就当空 prompt，下面按标题兜底 */ }
      const content = chatReply(prompt);
      console.log('CHAT  <- ' + prompt.replace(/\s+/g, ' ').slice(0, 70)
        + '\n      -> ' + content.replace(/\s+/g, ' ').slice(0, 50));
      return sendJson(res, { choices: [{ message: { role: 'assistant', content } }] });
    }

    if (req.method === 'POST' && req.url.endsWith('/images/generations')) {
      console.log('IMAGE <- ' + body.replace(/\s+/g, ' ').slice(0, 80));
      return sendJson(res, { data: [{ url: 'http://127.0.0.1:' + PORT + '/cdn/cover.png' }] });
    }

    if (req.method === 'GET' && req.url === '/cdn/cover.png') {
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': PNG_1X1.length });
      return res.end(PNG_1X1);
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end('{"error":"not found"}');
  });
});

server.listen(PORT, '127.0.0.1', () => console.log('FAKE_AI_READY http://127.0.0.1:' + PORT + '/v1'));
