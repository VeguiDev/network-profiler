#!/usr/bin/env node
/**
 * Run npm inside one app directory with a predictable environment.
 *
 * npm 11.19 exports user-level `allow-scripts` to child processes during
 * `npm run`, and a child project-scoped install then rejects it as a CLI
 * policy. Removing that one inherited config entry lets the child read the
 * same user/project .npmrc normally. This keeps the root build scripts
 * working on developer machines without making CI depend on personal npm
 * configuration.
 */
import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const [directory, ...args] = process.argv.slice(2)
if (!directory || args.length === 0) {
  console.error('usage: node tools/npm-app.mjs <app-directory> <npm arguments…>')
  process.exit(2)
}

const env = { ...process.env }
delete env.npm_config_allow_scripts

const result = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', args, {
  cwd: resolve(root, directory),
  env,
  stdio: 'inherit',
  shell: process.platform === 'win32',
})
if (result.error) {
  console.error(result.error.message)
  process.exit(1)
}
process.exit(result.status ?? 1)
