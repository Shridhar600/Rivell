import { describe, expect, it } from 'vitest'
import type { components, paths } from './schema'

type SystemStatusResponse = components['schemas']['SystemStatusResponse']

describe('generated OpenAPI client workflow', () => {
  it('exposes typed system status path from generated schema', () => {
    type StatusGet = paths['/api/system/status']['get']
    type SuccessBody =
      StatusGet['responses'][200]['content']['application/json']

    const ok: SuccessBody = {
      service: 'redstar-backend',
      status: 'ready',
    }

    const typed: SystemStatusResponse = ok
    expect(typed.status).toBe('ready')
    expect('/api/system/status' satisfies keyof paths).toBe(
      '/api/system/status',
    )
  })

  it('exposes M1 contract paths from generated schema', () => {
    expect('/api/contracts' satisfies keyof paths).toBe('/api/contracts')
    expect('/api/contracts/{contractId}' satisfies keyof paths).toBe(
      '/api/contracts/{contractId}',
    )
    expect(
      '/api/contracts/{contractId}/versions/{versionNumber}/structure' satisfies keyof paths,
    ).toBe(
      '/api/contracts/{contractId}/versions/{versionNumber}/structure',
    )
    expect(
      '/api/contracts/{contractId}/versions/{versionNumber}/original' satisfies keyof paths,
    ).toBe(
      '/api/contracts/{contractId}/versions/{versionNumber}/original',
    )

    type Node = components['schemas']['DocumentNode']
    const node: Node = {
      id: 'n1',
      parentId: null,
      nodeType: 'PARAGRAPH',
      ordinal: 0,
      text: 'hello',
      headingLevel: null,
      rawStyleId: null,
      sourceSpan: { partPath: 'word/document.xml', bodyIndex: 0 },
    }
    expect(node.nodeType).toBe('PARAGRAPH')
  })
})
