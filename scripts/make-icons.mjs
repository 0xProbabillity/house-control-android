#!/usr/bin/env node
// Turns the Lucide icons the app uses into Android vector drawables (app/src/main/res/drawable/lucide_*.xml),
// so the native screens and the widgets draw the same icons as the web panel.
// Usage: node scripts/make-icons.mjs   (after `npm ci` in web/)
import { readFileSync, writeFileSync, readdirSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'web/node_modules/lucide/dist/esm/icons');
const out = join(root, 'app/src/main/res/drawable');

const ICONS = [
  'power', 'sun', 'sofa', 'film', 'moon', 'door-open', 'lamp-ceiling', 'lamp', 'lightbulb', 'lightbulb-off', 'lamp-floor',
  'lamp-desk', 'sparkles', 'snowflake', 'flame', 'droplets', 'fan', 'refresh-cw', 'leaf', 'air-vent', 'house', 'house-plus',
  'settings-2', 'sliders-horizontal', 'tv', 'bed-double', 'chevron-right', 'chevron-left', 'arrow-left', 'x', 'check',
  'circle-check', 'circle', 'circle-alert', 'link-2', 'unlink', 'laptop', 'smartphone', 'plug', 'house-wifi', 'router',
  'pencil-ruler', 'plus', 'minus', 'download', 'upload', 'scan-qr-code', 'qr-code', 'globe', 'shield-check', 'at-sign',
  'code-xml', 'package', 'arrow-up-right', 'pointer', 'image-down', 'share-2', 'radar', 'thermometer', 'square-dashed',
  'building-2', 'layout-grid', 'clipboard-paste', 'refresh-ccw', 'info', 'circle-question-mark', 'wifi', 'hand-heart', 'trash-2',
  'zap', 'armchair',
];

const num = (v) => Number(v);

// Every Lucide element as path data in a 24×24 box.
function toPath([tag, a]) {
  switch (tag) {
    case 'path': return a.d;
    case 'line': return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`;
    case 'polyline': return 'M' + a.points.trim().split(/\s+/).join('L');
    case 'polygon': return 'M' + a.points.trim().split(/\s+/).join('L') + 'Z';
    case 'circle': {
      const [cx, cy, r] = [num(a.cx), num(a.cy), num(a.r)];
      return `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
    }
    case 'ellipse': {
      const [cx, cy, rx, ry] = [num(a.cx), num(a.cy), num(a.rx), num(a.ry)];
      return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0`;
    }
    case 'rect': {
      const [x, y, w, h] = [num(a.x || 0), num(a.y || 0), num(a.width), num(a.height)];
      const r = Math.min(num(a.rx || a.ry || 0), w / 2, h / 2);
      if (!r) return `M${x} ${y}h${w}v${h}h${-w}Z`;
      return `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${h - 2 * r}a${r} ${r} 0 0 1 ${-r} ${r}`
        + `h${-(w - 2 * r)}a${r} ${r} 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a${r} ${r} 0 0 1 ${r} ${-r}Z`;
    }
    default: throw new Error(`Unknown element <${tag}>`);
  }
}

for (const file of readdirSync(out)) if (file.startsWith('lucide_')) unlinkSync(join(out, file));

for (const name of ICONS) {
  const text = readFileSync(join(source, `${name}.js`), 'utf8');
  const body = text.slice(text.indexOf('= [') + 2, text.lastIndexOf('];') + 1);
  const nodes = Function(`return ${body}`)();
  const paths = nodes.map((node) => `    <path android:pathData="${toPath(node)}"
        android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />`);
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Lucide "${name}" (ISC license), made by scripts/make-icons.mjs. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
${paths.join('\n')}
</vector>
`;
  writeFileSync(join(out, `lucide_${name.replaceAll('-', '_')}.xml`), xml);
}
console.log(`${ICONS.length} icons written to ${out}`);
