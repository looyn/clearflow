// ===== 文档下载：Markdown 原文；正文含图片时连同图片打包为 zip（md 内图片链接改为相对路径）=====

export function docFileName(title, ext) {
    const base = String(title || '文档')
        .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, '_')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 80);
    return `${base || '文档'}.${ext}`;
}

// 与后端 /api/files/raw/{key} 对应（兼容绝对地址）
const RAW_IMAGE = /^(?:https?:\/\/[^/]+)?\/api\/files\/raw\/(.+)$/;

function collectImageUrls(markdown) {
    const urls = new Set();
    const add = (u) => {
        if (u && !/^data:/i.test(u) && RAW_IMAGE.test(u)) urls.add(u);
    };
    for (const m of markdown.matchAll(/!\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+["'][^"']*["'])?\s*\)/g)) add(m[1]);
    for (const m of markdown.matchAll(/<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>/gi)) add(m[1]);
    return [...urls];
}

function imageEntryName(url, index) {
    let key = '';
    const m = url.match(RAW_IMAGE);
    if (m) {
        try {
            key = decodeURIComponent(m[1]);
        } catch {
            key = m[1];
        }
    }
    // 存储 key 形如 1729000000000-ab12cd34-原文件名
    const tail = key.replace(/^\d+-[0-9a-f]{8}-/i, '') || `image-${index + 1}`;
    const safe = tail.replace(/[\\/:*?"<>|]+/g, '_').trim() || `image-${index + 1}`;
    return `${String(index + 1).padStart(2, '0')}-${safe}`;
}

// ===== 极简 ZIP（仅 store 不压缩，避免引入第三方依赖）=====

const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c >>> 0;
    }
    return t;
})();

function crc32(bytes) {
    let crc = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ bytes[i]) & 0xff];
    return (crc ^ 0xffffffff) >>> 0;
}

function makeZip(entries) {
    const enc = new TextEncoder();
    const now = new Date();
    const time = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1);
    const date = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
    const parts = [];
    const central = [];
    let offset = 0;

    for (const entry of entries) {
        const name = enc.encode(entry.name);
        const data = entry.data;
        const crc = crc32(data);

        const local = new Uint8Array(30 + name.length);
        const lv = new DataView(local.buffer);
        lv.setUint32(0, 0x04034b50, true);
        lv.setUint16(4, 20, true);
        lv.setUint16(6, 0x0800, true); // UTF-8 文件名
        lv.setUint16(8, 0, true); // store
        lv.setUint16(10, time, true);
        lv.setUint16(12, date, true);
        lv.setUint32(14, crc, true);
        lv.setUint32(18, data.length, true);
        lv.setUint32(22, data.length, true);
        lv.setUint16(26, name.length, true);
        lv.setUint16(28, 0, true);
        local.set(name, 30);
        parts.push(local, data);

        const cd = new Uint8Array(46 + name.length);
        const cv = new DataView(cd.buffer);
        cv.setUint32(0, 0x02014b50, true);
        cv.setUint16(4, 20, true);
        cv.setUint16(6, 20, true);
        cv.setUint16(8, 0x0800, true);
        cv.setUint16(10, 0, true);
        cv.setUint16(12, time, true);
        cv.setUint16(14, date, true);
        cv.setUint32(16, crc, true);
        cv.setUint32(20, data.length, true);
        cv.setUint32(24, data.length, true);
        cv.setUint16(28, name.length, true);
        cv.setUint32(42, offset, true);
        cd.set(name, 46);
        central.push(cd);

        offset += local.length + data.length;
    }

    const centralSize = central.reduce((sum, b) => sum + b.length, 0);
    const end = new Uint8Array(22);
    const ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(8, entries.length, true);
    ev.setUint16(10, entries.length, true);
    ev.setUint32(12, centralSize, true);
    ev.setUint32(16, offset, true);

    return new Blob([...parts, ...central, end], { type: 'application/zip' });
}

export function downloadBlob(name, blob) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
}

/**
 * 下载 Markdown：无图片直接给 .md；有上传图片则打包为 .zip（md + images/）。
 * 外链图片不在打包范围，保持原地址。
 * @returns {Promise<{images: number, missing: number}>}
 */
export async function downloadDocMarkdown(doc) {
    const content = doc.content || '';
    const urls = collectImageUrls(content);
    const files = [];
    let missing = 0;
    let rewritten = content;

    for (const url of urls) {
        try {
            const res = await fetch(url, { credentials: 'same-origin' });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const blob = await res.blob();
            const name = imageEntryName(url, files.length);
            files.push({ name: `images/${name}`, data: new Uint8Array(await blob.arrayBuffer()) });
            rewritten = rewritten.split(url).join(`images/${name}`);
        } catch {
            missing++;
        }
    }

    const base = docFileName(doc.title, 'md').replace(/\.md$/, '');
    if (!files.length) {
        downloadBlob(`${base}.md`, new Blob([rewritten], { type: 'text/markdown;charset=utf-8' }));
        return { images: 0, missing };
    }
    const enc = new TextEncoder();
    const zip = makeZip([{ name: `${base}.md`, data: enc.encode(rewritten) }, ...files]);
    downloadBlob(`${base}.zip`, zip);
    return { images: files.length, missing };
}
