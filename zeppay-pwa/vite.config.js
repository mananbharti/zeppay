import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { createHash } from 'node:crypto';
function offlineBundle() {
  return { name: 'offline-bundle', writeBundle(options) {
    const root = options.dir;
    const walk = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]);
    const files = walk(root).filter((p) => !p.endsWith('/sw.js'));
    const hash = createHash('sha256'); files.forEach((p) => hash.update(readFileSync(p)));
    const urls = ['./', ...files.map((p) => './' + relative(root, p).replaceAll('\\', '/'))];
    const sw = readFileSync('public/sw.js', 'utf8').replace('__BUILD_ID__', hash.digest('hex').slice(0, 16)).replace('/*PRECACHE*/[]', JSON.stringify(urls));
    writeFileSync(join(root, 'sw.js'), sw);
  }};
}
export default defineConfig({ base: './', plugins: [react(), offlineBundle()], test: { environment: 'node', include: ['src/**/*.test.js'] } });
