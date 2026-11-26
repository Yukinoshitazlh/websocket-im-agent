import { Marked } from 'marked';
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import sql from 'highlight.js/lib/languages/sql';
import json from 'highlight.js/lib/languages/json';
import bash from 'highlight.js/lib/languages/bash';
import yaml from 'highlight.js/lib/languages/yaml';

hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('js', javascript);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('ts', typescript);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('json', json);
hljs.registerLanguage('bash', bash);
hljs.registerLanguage('sh', bash);
hljs.registerLanguage('yaml', yaml);
hljs.registerLanguage('yml', yaml);

function escapeHtml(raw: string): string {
  return raw.replace(/[&<>"']/g, (ch) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch] || ch)
  );
}

/**
 * 严格 URL 协议白名单校验（防御 javascript: / vbscript: / data:text/html 等 XSS 注入）
 */
export function sanitizeUrl(rawHref?: string | null): string | null {
  if (!rawHref) return null;
  // 移除不可见控制字符与首尾空白，防止绕过前缀匹配
  const cleaned = rawHref.replace(/[\u0000-\u001F\u007F\s]+/g, '').trim();
  if (!cleaned) return null;
  if (cleaned.startsWith('/') && !cleaned.startsWith('//')) {
    return rawHref.trim();
  }
  try {
    const parsed = new URL(cleaned, 'https://pulseim.invalid');
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:' || parsed.protocol === 'mailto:') {
      return rawHref.trim();
    }
  } catch {
    return null;
  }
  return null;
}

const marked = new Marked({
  breaks: true,
  gfm: true,
  renderer: {
    html({ text }) {
      // 防 XSS：转义原始 HTML 标签
      return escapeHtml(text);
    },
    link({ href, title, tokens }) {
      const safeHref = sanitizeUrl(href);
      const label = this.parser.parseInline(tokens);
      if (!safeHref) {
        return `<span>${label}</span>`;
      }
      const titleAttr = title ? ` title="${escapeHtml(title)}"` : '';
      return `<a href="${escapeHtml(safeHref)}"${titleAttr} target="_blank" rel="noopener noreferrer">${label}</a>`;
    },
    image({ href, title, text }) {
      const safeHref = sanitizeUrl(href);
      if (!safeHref) {
        return escapeHtml(text || '');
      }
      const altAttr = escapeHtml(text || '');
      const titleAttr = title ? ` title="${escapeHtml(title)}"` : '';
      return `<img src="${escapeHtml(safeHref)}" alt="${altAttr}"${titleAttr} loading="lazy" />`;
    },
    code({ text, lang }) {
      const safeLang = (lang || '').trim().toLowerCase();
      let highlighted: string;
      if (safeLang && hljs.getLanguage(safeLang)) {
        highlighted = hljs.highlight(text, { language: safeLang }).value;
      } else {
        highlighted = escapeHtml(text);
      }
      const label = safeLang || 'code';
      return `<div class="code-block"><div class="code-head"><span>${escapeHtml(label)}</span><button type="button" class="code-copy-btn" data-copy-code="1">复制</button></div><pre><code class="hljs">${highlighted}</code></pre></div>`;
    }
  }
});

export function renderMarkdownSafe(text: string): string {
  if (!text) return '';
  return marked.parse(text) as string;
}
