import { build } from 'esbuild';
import { cp, mkdir, rm } from 'node:fs/promises';

await rm('dist', { recursive: true, force: true });
await mkdir('dist', { recursive: true });
await Promise.all([
  cp('index.html', 'dist/index.html'),
  cp('favicon.svg', 'dist/favicon.svg'),
  cp('src/styles.css', 'dist/styles.css'),
  build({
    entryPoints: ['src/app.js'], bundle: true, outfile: 'dist/app.js',
    format: 'esm', target: ['chrome120'], sourcemap: false, minify: true,
    legalComments: 'none'
  })
]);
