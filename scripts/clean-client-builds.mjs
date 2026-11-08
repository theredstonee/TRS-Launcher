#!/usr/bin/env node

import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const options = process.argv.slice(2)

if (options.includes('--help') || options.includes('-h')) {
  console.log('Usage: node scripts/clean-client-builds.mjs [--dry-run]')
  process.exit(0)
}

if (options.length > 1 || (options.length === 1 && !['--dry-run', '--zeigen'].includes(options[0]))) {
  console.error('Usage: node scripts/clean-client-builds.mjs [--dry-run]')
  process.exit(2)
}

const cleanupArgs = ['--client-builds', ...(options.length ? ['--zeigen'] : [])]
let bash = 'bash'
if (process.platform === 'win32') {
  const candidates = [
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Programs', 'Git', 'bin', 'bash.exe'),
    join(process.env.ProgramFiles ?? 'C:\\Program Files', 'Git', 'bin', 'bash.exe'),
  ].filter(Boolean)
  bash = candidates.find((candidate) => existsSync(candidate))
  if (!bash) {
    console.error('Git Bash not found in the standard installation folders.')
    process.exit(1)
  }
}

const result = spawnSync(bash, [join(root, 'aufraeumen'), ...cleanupArgs], { cwd: root, stdio: 'inherit' })

if (result.error) {
  console.error(`Could not start cleanup: ${result.error.message}`)
  process.exit(1)
}

process.exit(result.status ?? 1)