/*!
 * 写字台 · 代码块增强：语法高亮 + 一键复制
 *
 * 渲染方（前台 commonmark / 后台 ByteMD 预览）输出的都是：
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
 *  6. 【动态场景】后台编辑器的实时预览是「每次输入整段重渲染」的，所以本脚本必须可重入：
 *     enhancePre 用内容指纹去重（同一块内容没变就直接跳过），内容变了则先还原成裸 <pre>
 *     再重做（否则会把新的 span 套进上一次的 span 里）。boot() 时挂一个 MutationObserver
 *     监听 #editor，预览一变就防抖重扫一遍；我们自己的插入操作会再触发一次回调，
 *     但那一轮全部命中「内容没变」而空转，不会形成死循环。
 *     外部若在别处动态渲染了 markdown，可手动调 window.__xzCodeBlockRefresh()。
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

  /** 单块超过这个字符数就不高亮：后台输入时每敲一下都要重扫，别让超大文件拖慢打字 */
  var MAX_HIGHLIGHT_LENGTH = 120000;

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

  /** 内容指纹：动态重渲染时用来判断这块还是不是上次那块内容 */
  function fingerprint(text) {
    var h = 5381;
    for (var i = 0; i < text.length; i++) h = ((h << 5) + h + text.charCodeAt(i)) | 0;
    return text.length + ':' + h;
  }

  function codeOf(pre) {
    var first = pre.firstElementChild;
    return first && first.tagName === 'CODE' ? first : pre;
  }

  function wrapperOf(pre) {
    var p = pre.parentNode;
    return (p && p.classList && p.classList.contains('code-block')) ? p : null;
  }

  /** 还原成裸 <pre>：删掉工具条、把高亮 span 退回纯文本（文本内容不变，只丢标记） */
  function restore(pre) {
    var wrap = wrapperOf(pre);
    if (wrap) {
      for (var i = wrap.children.length - 1; i >= 0; i--) {
        var child = wrap.children[i];
        if (child !== pre && child.classList && child.classList.contains('code-tools')) {
          wrap.removeChild(child);
        }
      }
      if (wrap.parentNode) {
        wrap.parentNode.insertBefore(pre, wrap);
        wrap.parentNode.removeChild(wrap);
      }
    }
    if (pre.dataset.cbHi === '1') {
      var code = codeOf(pre);
      code.textContent = code.textContent || '';   // 赋值 textContent 即丢弃内部 span
      code.classList.remove('hljs');
      delete pre.dataset.cbHi;
    }
  }

  function enhancePre(pre) {
    var code = codeOf(pre);
    var text = code.textContent || '';
    var sig = fingerprint(text);

    // 已处理过且内容没变 → 跳过（动态重渲染会反复调用同一批节点）
    if (pre.dataset.cbDone === '1' && pre.dataset.cbSig === sig) return;
    // 已处理过但内容变了（元素被复用）→ 先还原再重做，避免 span 套 span
    if (pre.dataset.cbDone === '1') restore(pre);

    pre.dataset.cbDone = '1';
    pre.dataset.cbSig = sig;

    var raw = readLang(code);
    var lang = resolveLang(raw);

    // 1) 高亮（只有认得出语法名、且块不算太大时才做）
    if (lang && window.hljs && text.length <= MAX_HIGHLIGHT_LENGTH) {
      try {
        var res = window.hljs.highlight(text, { language: lang, ignoreIllegals: true });
        code.innerHTML = res.value;
        code.classList.add('hljs');
        pre.dataset.cbHi = '1';
      } catch (e) {
        // 高亮失败不影响阅读与复制，静默降级
      }
    }

    // 2) 工具栏：包一层 div，把工具栏放在不滚动的那一层
    var host = pre.parentNode;
    var wrap = wrapperOf(pre);
    if (!wrap) {
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

  /* ---------------- 动态内容（后台编辑器实时预览） ---------------- */

  var timer = null;

  function schedule(delay) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () { timer = null; run(); }, delay || 160);
  }

  function watchDynamic() {
    if (!window.MutationObserver) return;
    var root = document.getElementById('editor');   // 后台文章编辑器的挂载点
    if (!root || root.__xzCbWatched) return;
    root.__xzCbWatched = true;
    new MutationObserver(function () { schedule(160); })
      .observe(root, { childList: true, subtree: true });
  }

  /** 手动刷新：外部若往别处动态插入了 markdown 渲染结果，可调它补一次增强 */
  window.__xzCodeBlockRefresh = run;

  function boot() {
    run();
    watchDynamic();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
