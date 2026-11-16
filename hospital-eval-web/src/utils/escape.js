/**
 * HTML 转义与关键词高亮（TASK-12 步骤 3）。
 * highlight 必须**先转义再包裹 <mark>**，保证病历等外部文本渲染安全（04 §13 禁止 v-html 未转义）。
 */
const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

export function escapeHtml(text) {
  if (text === null || text === undefined) {
    return ''
  }
  return String(text).replace(/[&<>"']/g, (ch) => ESCAPE_MAP[ch])
}

function escapeRegExp(text) {
  return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 先整体转义，再把关键词（不区分大小写）用 <mark> 包裹。
 * @returns {string} 安全的 HTML 字符串（调用方用 v-html 渲染）
 */
export function highlight(text, keywords) {
  const escaped = escapeHtml(text)
  if (!keywords || keywords.length === 0) {
    return escaped
  }
  const patterns = keywords
    .filter((k) => k !== null && k !== undefined && String(k).length > 0)
    .map((k) => escapeRegExp(escapeHtml(String(k))))
  if (patterns.length === 0) {
    return escaped
  }
  const re = new RegExp(`(${patterns.join('|')})`, 'gi')
  return escaped.replace(re, '<mark>$1</mark>')
}
