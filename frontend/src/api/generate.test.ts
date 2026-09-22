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
})
