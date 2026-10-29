// 富文本展示前的轻量清洗：移除可执行标签、内联事件与 javascript: / data:text/html 链接。
// 只用于「展示」，编辑态仍保留原始 HTML，避免破坏 wangEditor 的节点结构。
const DROP_TAGS = 'script,style,iframe,object,embed,link,meta,form,input,button,textarea,select';
const URL_ATTRS = new Set(['href', 'src', 'xlink:href']);

export function sanitizeHtml(html) {
    if (!html) return '';
    const tpl = document.createElement('template');
    tpl.innerHTML = String(html);
    tpl.content.querySelectorAll(DROP_TAGS).forEach((el) => el.remove());
    tpl.content.querySelectorAll('*').forEach((el) => {
        for (const attr of [...el.attributes]) {
            const name = attr.name.toLowerCase();
            const value = attr.value.replace(/\s+/g, '').toLowerCase();
            if (name.startsWith('on')) el.removeAttribute(attr.name);
            else if (URL_ATTRS.has(name) && /^(javascript:|data:text\/html)/.test(value)) el.removeAttribute(attr.name);
        }
    });
    return tpl.innerHTML;
}
