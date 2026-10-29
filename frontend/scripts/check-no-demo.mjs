#!/usr/bin/env node
/**
 * Fails when a normal production build contains demo transport code or demo data.
 * The demo marker is a string that only `src/demo/install.ts` adds to the demo bundle.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const DIST = 'dist'
const MARKERS = [
  'rivell-demo-transport',
  'demo-data',
  'rivell-demo-v1',
  'rivell-demo-blocked',
  'rivell-demo-route',
  'rivell-demo-finalized',
  'NDA Playbook',
  'Demo mode.',
  'Not available in the demo.',
]

function walk(directory) {
  const files = []
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) files.push(...walk(path))
    else files.push(path)
  }
  return files
}

let files
try {
  files = walk(DIST)
} catch {
  console.error(`check:no-demo: no ${DIST} directory; run the build first`)
  process.exit(2)
}

const problems = []
for (const file of files) {
  const name = relative(DIST, file)
  if (MARKERS.some((marker) => name.includes(marker))) {
    problems.push(`${name}: file name contains a demo marker`)
    continue
  }
  const contents = readFileSync(file, 'utf8')
  for (const marker of MARKERS) {
    if (contents.includes(marker)) problems.push(`${name}: contains ${JSON.stringify(marker)}`)
  }
}

if (problems.length > 0) {
  console.error('check:no-demo: the normal build contains demo code or data:')
  for (const problem of problems) console.error(`  ${problem}`)
  process.exit(1)
}

console.log(`check:no-demo: ${files.length} files, no demo marker or demo-data path`)
