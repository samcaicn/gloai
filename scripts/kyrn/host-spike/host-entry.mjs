/**
 * Entry of the host process. Registers the same TypeScript loader the CLI launcher uses (tsx with pi's tsconfig
 * paths), then loads the host. A packaged app would bundle pi instead; see README.
 */
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = process.env.KYRN_ROOT;
if (!root) throw new Error('KYRN_ROOT is required');
const require = createRequire(join(root, 'package.json'));
const { register } = await import(pathToFileURL(require.resolve('tsx/esm/api')).href);
register({ tsconfig: join(root, 'tsconfig.json') });
await import('./host.mts');
