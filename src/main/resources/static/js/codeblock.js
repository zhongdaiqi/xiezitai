/*!
 * 写字台 · 文章代码块增强：语法高亮 + 一键复制
 *
 * 服务端（commonmark）渲染 fenced code 时输出的是：
 *     <pre><code class="language-java">…</code></pre>
 * 本脚本把它变成：
 *     <div class="code-block">
 *       <div class="code-tools"><span class="code-lang">java</span><button class="code-copy">复制</button></div>
 *       <pre><code class="language-java hljs">…带 token span 的高亮结果…</code></pre>
 *     </div>
 *
 * 设计取舍（改之前先看）：
 *  1. 高亮库是本地内置的 /vendor/highlight/highlight.min.js（项目禁用任何 CDN，离线可跑）。
 *     库没加载成功时脚本仍然工作 —— 只是不高亮，复制按钮照常注入。
 *  2. **只对作者显式标注了语言的代码块做高亮**。实测 highlight.js 的自动探测（highlightAuto）
 *     没有可靠阈值：中文散文会被判成 sql、几行日志会被判成 yaml、`System.out.println`
 *     会被判成 csharp。宁可不着色，也不要给出错误的颜色。未标注语言的块保持原样 + 复制按钮。
 *  3. 工具栏（语言标签 + 复制按钮）挂在**包裹层**上而不是 <pre> 里：<pre> 自身 overflow:auto，
 *     横滚时按钮会跟着滚出可视区；挂在外层就始终贴在右上角。
 *  4. 复制内容一律取 code.textContent（高亮只是包 span，文本不变），所以复制到的一定是
 *     作者写的原文，不含任何标签。
 *  5. 复制优先用 Clipboard API（https / localhost 才可用），失败或非安全上下文时回退
 *     execCommand('copy')。
 *
 * 无 JS 时：页面就是服务端渲染的 <pre><code>，纯文本可读、可手动选中复制（SEO 与可访问性不受影响）。
 */
(function () {
  'use strict';

  // 重复引入保护
  if (window.__xzCodeBlockReady) return;
  window.__xzCodeBlockReady = true;

  /** 不参与高亮的语言标记：mermaid 是将来要渲染成图的，纯文本类着色没意义反而干扰阅读 */
  var SKIP = {
    mermaid: 1, text: 1, txt: 1, plaintext: 1, plain: 1, none: 1,
    output: 1, log: 1, logs: 1, console: 1, terminal: 1, raw: 1
  };

  /**
   * 少量别名补齐：highlight.js 自己已认识 js/ts/py/sh/yml/c#/golang/kt 等常见别名，
   * 这里只补它不认识的写法。
   */
  var ALIAS = {
    shell: 'bash',
    sh: 'bash',
    '.net': 'csharp',
    dotnet: 'csharp',
    jsonc: 'json',
    htm: 'xml',
    make: 'makefile',
    conf: 'nginx',
    'docker-compose': 'yaml',
    k8s: 'yaml'
  };

  /** 取代码块的语言标记（作者写的信息串原文），如 language-java / lang-Java / language-c# */
  function readLang(code) {
    var m = /(?:^|\s)(?:language|lang)-([^\s]+)/i.exec(code.className || '');
    if (!m) return '';
    try { return decodeURIComponent(m[1]).toLowerCase(); } catch (e) { return m[1].toLowerCase(); }
  }

  /** 把作者的写法解析成 highlight.js 认识的语法名，认不出返回空串 */
  function resolveLang(raw) {
    if (!raw || SKIP[raw]) return '';
    var hljs = window.hljs;
    if (!hljs) return '';
    var key = ALIAS[raw] || raw;
    if (hljs.getLanguage(key)) return key;
    // 去掉常见修饰：language-java → java；c#7 → c#；java@17 → java
    var trimmed = key.replace(/[\d.@]+$/, '').replace(/[-_]?(snippet|example|demo)$/, '');
    if (trimmed !== key) {
      var k2 = ALIAS[trimmed] || trimmed;
      if (hljs.getLanguage(k2)) return k2;
    }
    return '';
  }

  /** 复制到剪贴板：Clipboard API 优先，回退 execCommand */
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () { return true; },
        function () { return legacyCopy(text); });
    }
    return Promise.resolve(legacyCopy(text));
  }

  function legacyCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', 'readonly');
    ta.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;padding:0;border:0;opacity:0;';
    document.body.appendChild(ta);
    var ok = false;
    try {
      ta.select();
      ta.setSelectionRange(0, ta.value.length);
      ok = document.execCommand('copy');
    } catch (e) {
      ok = false;
    }
    document.body.removeChild(ta);
    return ok;
  }

  function buildTools(lang, codeEl) {
    var tools = document.createElement('div');
    tools.className = 'code-tools';

    if (lang) {
      var tag = document.createElement('span');
      tag.className = 'code-lang';
      tag.textContent = lang;
      tools.appendChild(tag);
    }

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'code-copy';
    btn.textContent = '复制';
    btn.title = '复制代码';
    btn.setAttribute('aria-label', '复制代码');

    var timer = null;
    btn.addEventListener('click', function () {
      copyText(codeEl.textContent || '').then(function (ok) {
        btn.textContent = ok ? '已复制' : '复制失败';
        btn.classList.toggle('ok', !!ok);
        btn.classList.toggle('err', !ok);
        if (timer) clearTimeout(timer);
        timer = setTimeout(function () {
          btn.textContent = '复制';
          btn.classList.remove('ok', 'err');
        }, 1600);
      });
    });

    tools.appendChild(btn);
    return tools;
  }

  function enhancePre(pre) {
    if (pre.dataset.cbDone) return;
    var code = pre.firstElementChild && pre.firstElementChild.tagName === 'CODE'
      ? pre.firstElementChild : pre;
    pre.dataset.cbDone = '1';

    var raw = readLang(code);
    var lang = resolveLang(raw);

    // 1) 高亮（只有认得出语法名时才做）
    if (lang && window.hljs) {
      try {
        var res = window.hljs.highlight(code.textContent || '', { language: lang, ignoreIllegals: true });
        code.innerHTML = res.value;
        code.classList.add('hljs');
      } catch (e) {
        // 高亮失败不影响阅读与复制，静默降级
      }
    }

    // 2) 工具栏：包一层 div，把工具栏放在不滚动的那一层
    var host = pre.parentNode;
    var wrap;
    if (host && host.classList && host.classList.contains('code-block')) {
      wrap = host;
    } else {
      wrap = document.createElement('div');
      wrap.className = 'code-block';
      host.insertBefore(wrap, pre);
      wrap.appendChild(pre);
    }
    wrap.appendChild(buildTools(lang ? raw : '', code));
  }

  function run() {
    var blocks = document.querySelectorAll('.markdown-body pre');
    for (var i = 0; i < blocks.length; i++) enhancePre(blocks[i]);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
})();
