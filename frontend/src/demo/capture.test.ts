/// <reference types="node" />
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it } from 'vitest'
import { advanceDemoStage, resetDemo } from './state'
import {
  DemoRuntime,
  type DemoCapture,
  type DemoManifest,
} from './transport'

const CAPTURE_DIR = resolve(process.cwd(), '../demo/capture') + '/'
const MANIFEST_PATH = `${CAPTURE_DIR}manifest.json`
const available = existsSync(MANIFEST_PATH)

const manifest: DemoManifest | null = available
  ? JSON.parse(readFileSync(MANIFEST_PATH, 'utf8')) as DemoManifest
  : null
const captures: Record<string, DemoCapture> = manifest
  ? Object.fromEntries(manifest.states.map((state) => [
    state,
    JSON.parse(readFileSync(`${CAPTURE_DIR}${state}.json`, 'utf8')) as DemoCapture,
  ]))
  : {}

const SECRETS = [
  /\bauthorization\b/i,
  /\bbearer\b\s/i,
  /(?<![a-z0-9])sk-[a-z0-9][a-z0-9_-]{8,}/i,
  /(?<![a-z0-9])tsk-[a-z0-9][a-z0-9_-]{8,}/i,
  /\bapi[_-]?keys?\b/i,
]

describe.skipIf(!available)('captured demo data', () => {
  beforeEach(() => resetDemo())

  it('has the seven states in capture order and the named ids', () => {
    expect(manifest?.version).toBe(1)
    expect(manifest?.states).toEqual([
      'base',
      'tour-k2-compiled',
      'tour-k2-approved',
      'tour-k2-published',
      'tour-c5-decided',
      'tour-c5-finalized',
      'tour-c5-done',
    ])
    expect(manifest?.capturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    expect(manifest?.ids?.c5ReviewId).toBeTruthy()
    expect(manifest?.ids?.c5ContractId).toBeTruthy()
    expect(manifest?.ids?.k2ArtifactId).toBeTruthy()
    expect(manifest?.ids?.k2PublicationId).toBeTruthy()
    expect(Object.keys(manifest?.ids?.reviews ?? {})).toHaveLength(5)
  })

  it('keeps the redline files and the prepared playbook at their recorded sizes and hashes', () => {
    for (const [reviewId, file] of Object.entries(manifest?.files ?? {})) {
      const path = `${CAPTURE_DIR}files/${reviewId}.docx`
      expect(existsSync(path)).toBe(true)
      expect(statSync(path).size).toBe(file.sizeBytes)
      const digest = createHash('sha256').update(readFileSync(path)).digest('hex')
      expect(digest).toBe(file.sha256)
      expect(file.contentType).toContain('wordprocessingml')
    }
    expect(Object.keys(manifest?.files ?? {})).toHaveLength(2)
    const prepared = manifest?.preparedKnowledge
    expect(prepared?.filename).toBe('NDA Playbook.xlsx')
    const preparedPath = `${CAPTURE_DIR}${prepared?.path}`
    expect(statSync(preparedPath).size).toBe(prepared?.size ?? prepared?.sizeBytes)
    expect(createHash('sha256').update(readFileSync(preparedPath)).digest('hex'))
      .toBe(prepared?.sha256)
  })

  it('stores every state and no credential marker', () => {
    for (const state of manifest?.states ?? []) {
      const path = `${CAPTURE_DIR}${state}.json`
      const text = readFileSync(path, 'utf8')
      const parsed = JSON.parse(text) as DemoCapture
      expect(parsed.state).toBe(state)
      expect(Object.keys(parsed.responses).length).toBeGreaterThan(0)
      for (const pattern of SECRETS) {
        expect(pattern.test(text), `${state} contains ${pattern}`).toBe(false)
      }
    }
  })

  it('serves the base lists and the tour review from the real capture', async () => {
    const demo = new DemoRuntime(manifest!, captures)
    const base = await demo.fetch(new Request('http://localhost/api/reviews'))
    expect(base.status).toBe(200)
    const baseItems = (await base.json() as { data: { items: { id: string }[] } }).data.items
    expect(baseItems.length).toBe(4)
    expect(baseItems.some((item) => item.id === manifest?.ids?.c5ReviewId)).toBe(false)

    advanceDemoStage('tour-k2-published')
    const published = await demo.fetch(new Request('http://localhost/api/reviews'))
    const publishedItems = (await published.json() as {
      data: { items: { id: string; humanReview: { actionableFindings: number } }[] }
    }).data.items
    const c5 = publishedItems.find((item) => item.id === manifest?.ids?.c5ReviewId)
    expect(c5?.humanReview.actionableFindings).toBe(3)

    const candidates = await demo.fetch(new Request(
      `http://localhost/api/knowledge/artifacts/${manifest?.ids?.k2ArtifactId}/candidates`))
    expect(candidates.status).toBe(200)
    const list = (await candidates.json() as { data: { reviewStatus: string }[] }).data
    expect(list.length).toBe(14)
    expect(list.every((candidate) => candidate.reviewStatus === 'PUBLISHED')).toBe(true)
  })
})
