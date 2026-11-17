import { cp, mkdir, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import * as esbuild from 'esbuild';

const root = process.cwd();
const dist = path.join(root, 'dist');
await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });

// The plugin ships as a single classic script rather than a tree of ES modules.
// The Even app loads installed plugins over file://, where module scripts are
// blocked by CORS, so a bundle is the only shape that works in both dev mode
// (http) and installed mode (file).
await esbuild.build({
  entryPoints: [path.join(root, 'src', 'main.js')],
  bundle: true,
  format: 'iife',
  target: ['es2020'],
  platform: 'browser',
  outfile: path.join(dist, 'plugin.js'),
  legalComments: 'none',
  logLevel: 'warning',
});

// Keep the plain modules around for the dev server, where they are handy for
// reading a stack trace without a source map.
const sources = (await readdir(path.join(root, 'src'))).filter((name) => name.endsWith('.js'));
for (const name of sources) {
  await cp(path.join(root, 'src', name), path.join(dist, name));
}
await cp(path.join(root, 'index.html'), path.join(dist, 'index.html'));
// The Even dev server hands out app.json from the project root it is serving, so the
// manifest has to sit next to index.html or the dev-load silently 404s.
await cp(path.join(root, 'app.json'), path.join(dist, 'app.json'));

const app = JSON.parse(await cpText(path.join(root, 'app.json')));
console.log(`built ${app.package_id} v${app.version}: bundle + ${sources.length} modules`);

async function cpText(file) {
    const { readFile } = await import('node:fs/promises');
    return await readFile(file, 'utf8');
}
