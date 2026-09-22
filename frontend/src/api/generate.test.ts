import { describe, expect, it } from 'vitest'
import type { components, paths } from './schema'

type SystemStatus = components['schemas']['SystemStatus']

describe('generated OpenAPI client workflow', () => {
  it('exposes typed system status path from generated schema', () => {
    type StatusGet = paths['/api/system/status']['get']
    type SuccessBody =
      StatusGet['responses'][200]['content']['application/json']

    const ok: SuccessBody = {
      status: 'UP',
      timestamp: '2026-09-22T00:00:00.000Z',
    }

    const typed: SystemStatus = ok
    expect(typed.status).toBe('UP')
    expect('/api/system/status' satisfies keyof paths).toBe(
      '/api/system/status',
    )
  })
})
