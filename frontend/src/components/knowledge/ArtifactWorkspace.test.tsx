import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function ok(body: unknown): Response {
  return jsonResponse({
    data: body,
    status: 200,
    timestamp: '2026-09-23T05:00:00Z',
  })
}

const artifactDetail = {
  id: 'a1',
  artifactType: 'PLAYBOOK',
  filename: 'msa-playbook.docx',
  contentType: 'application/octet-stream',
  sizeBytes: 2048,
  sha256: 'c'.repeat(64),
  storageKey: 'org/knowledge/a1.docx',
  declaredContractTypes: ['MSA', 'NDA'],
  contractPackKey: 'MSA',
  partyPerspective: null,
  paperSource: 'Third-party',
  jurisdiction: 'US',
  language: 'en',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  manifestNote: null,
  ingestStatus: 'EXTRACTED',
  parserVersion: 'rivell-docx-parser-2',
  parseWarnings: [],
  artifactAssessment: null,
  createdAt: '2026-09-23T05:17:29.604991Z',
}

const docxStructure = [
  {
    id: 'body',
    artifactId: 'a1',
    parentId: null,
    nodeType: 'BODY',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
  },
  {
    id: 'h1',
    artifactId: 'a1',
    parentId: 'body',
    nodeType: 'HEADING',
    ordinal: 1,
    text: 'Limitation of Liability',
    headingLevel: 1,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 1,
    paragraphIndex: 1,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
  },
  {
    id: 'p1',
    artifactId: 'a1',
    parentId: 'body',
    nodeType: 'PARAGRAPH',
    ordinal: 2,
    text: 'Liability caps below 1x annual fees are preferred.',
    headingLevel: null,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 2,
    paragraphIndex: 2,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
  },
]

const xlsxStructure = [
  {
    id: 'wb',
    artifactId: 'x1',
    parentId: null,
    nodeType: 'WORKBOOK',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'xl/workbook.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
  },
  {
    id: 'sheet1',
    artifactId: 'x1',
    parentId: 'wb',
    nodeType: 'SHEET',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'xl/worksheets/sheet1.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: 'Positions',
    mergedRegion: null,
    rawStyleId: null,
  },
  {
    id: 'row0',
    artifactId: 'x1',
    parentId: 'sheet1',
    nodeType: 'SPREADSHEET_ROW',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'xl/worksheets/sheet1.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: 0,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: 'Positions',
    mergedRegion: null,
    rawStyleId: null,
  },
  {
    id: 'cell00',
    artifactId: 'x1',
    parentId: 'row0',
    nodeType: 'SPREADSHEET_CELL',
    ordinal: 0,
    text: 'Clause',
    headingLevel: null,
    numberingLabel: null,
    partPath: 'xl/worksheets/sheet1.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: 0,
    columnIndex: 0,
    runStart: null,
    runEnd: null,
    sheetName: 'Positions',
    mergedRegion: { firstRow: 0, lastRow: 0, firstColumn: 0, lastColumn: 2 },
    rawStyleId: null,
  },
  {
    id: 'cell01',
    artifactId: 'x1',
    parentId: 'row0',
    nodeType: 'SPREADSHEET_CELL',
    ordinal: 1,
    text: 'Preferred',
    headingLevel: null,
    numberingLabel: null,
    partPath: 'xl/worksheets/sheet1.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: 0,
    columnIndex: 1,
    runStart: null,
    runEnd: null,
    sheetName: 'Positions',
    mergedRegion: null,
    rawStyleId: null,
  },
]

const units = [
  {
    id: 'u1',
    artifactId: 'a1',
    ordinal: 1,
    kind: 'HEADING_SECTION',
    title: 'Limitation of Liability',
    contentText: 'Liability caps below 1x annual fees are preferred.',
    sourceNodeIds: ['h1', 'p1'],
  },
]

function stubArtifact(handlers: {
  artifact?: () => Response
  structure?: () => Response
  units?: () => Response
} = {}) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input)
    if (url.includes('/structure')) {
      return Promise.resolve(handlers.structure?.() ?? ok(docxStructure))
    }
    if (url.includes('/units')) {
      return Promise.resolve(handlers.units?.() ?? ok(units))
    }
    if (url.includes('/candidates')) {
      return Promise.resolve(ok([]))
    }
    return Promise.resolve(handlers.artifact?.() ?? ok(artifactDetail))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderArtifact(path = '/knowledge/artifacts/a1') {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return {
    router,
    ...render(
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>,
    ),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ArtifactWorkspace', () => {
  it('renders the source identity, manifest, structure and units', async () => {
    stubArtifact()

    renderArtifact()

    const heading = await screen.findByRole('heading', {
      level: 1,
      name: 'msa-playbook.docx',
    })
    expect(heading).toHaveFocus()

    expect(screen.getByText(/Contract playbook/)).toBeInTheDocument()
    expect(screen.getByText('Extracted')).toBeInTheDocument()
    expect(screen.getByText(`sha256 ${'c'.repeat(64)}`)).toBeInTheDocument()

    const view = screen.getByRole('link', { name: /view original/i })
    expect(view.getAttribute('href')).toMatch(
      /\/api\/knowledge\/artifacts\/a1\/original$/,
    )
    expect(view).toHaveAttribute('target', '_blank')

    const download = screen.getByRole('link', { name: /download original/i })
    expect(download).toHaveAttribute('download', 'msa-playbook.docx')

    expect(screen.getByText('MSA, NDA')).toBeInTheDocument()
    expect(screen.getByText('Third-party')).toBeInTheDocument()
    expect(screen.getByText('US')).toBeInTheDocument()
    expect(screen.getByText('2026-01-01')).toBeInTheDocument()
    expect(screen.getAllByText(/not declared/i).length).toBeGreaterThan(0)

    const structureSummary = screen.getByText('Source structure')
    expect(structureSummary).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { level: 3, name: 'Limitation of Liability' }),
    ).toBeInTheDocument()
    expect(
      screen.getAllByText('Liability caps below 1x annual fees are preferred.')
        .length,
    ).toBeGreaterThan(0)

    expect(screen.getByText('Extraction units')).toBeInTheDocument()
    const unit = await screen.findByText('Heading section · 2 source nodes')
    expect(unit).toBeInTheDocument()
  })


  it('shows a possible artifact-type mismatch without offering a type change', async () => {
    stubArtifact({
      artifact: () =>
        ok({
          ...artifactDetail,
          artifactAssessment: {
            assessedType: 'CLAUSE_LIBRARY',
            probability: 0.82,
            state: 'POSSIBLE_MISMATCH',
            specId: 'artifact-type-assessment',
            specVersion: 1,
            warnings: ['HUMAN_DECLARED_TYPE_DIFFERS_FROM_MACHINE_ASSESSMENT'],
          },
        }),
    })

    renderArtifact()

    expect(
      await screen.findByText(/may not match the declared artifact type/i),
    ).toBeInTheDocument()
    expect(screen.getAllByText(/Contract playbook/).length).toBeGreaterThan(0)
    // The Knowledge sidebar also has a Clause library link, so look at the page content only.
    expect(screen.getAllByText('Clause library').some((element) => !element.closest('nav'))).toBe(true)
    expect(screen.getByText(/remains authoritative/i)).toBeInTheDocument()
    expect(
      screen.getByText(/declared artifact type differs from Rivell's assessment/i),
    ).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).toBeNull()
  })

  it('shows an ambiguous assessment', async () => {
    stubArtifact({
      artifact: () =>
        ok({
          ...artifactDetail,
          artifactAssessment: {
            assessedType: 'PLAYBOOK',
            probability: 0.55,
            state: 'AMBIGUOUS',
            specId: 'artifact-type-assessment',
            specVersion: 1,
            warnings: [],
          },
        }),
    })

    renderArtifact()

    expect(
      await screen.findByText(/artifact type assessment is ambiguous/i),
    ).toBeInTheDocument()
  })

  it('shows agreement when the assessment matches', async () => {
    stubArtifact({
      artifact: () =>
        ok({
          ...artifactDetail,
          artifactAssessment: {
            assessedType: 'PLAYBOOK',
            probability: 1,
            state: 'MATCH',
            specId: 'artifact-type-assessment',
            specVersion: 1,
            warnings: [],
          },
        }),
    })

    renderArtifact()

    expect(
      await screen.findByText(/classification agrees with the declared artifact type/i),
    ).toBeInTheDocument()
  })

  it('renders spreadsheet structure as sheet tables with merged regions', async () => {
    stubArtifact({
      artifact: () =>
        ok({
          ...artifactDetail,
          id: 'x1',
          filename: 'knowledge-matrix.xlsx',
          artifactType: 'CLAUSE_LIBRARY',
        }),
      structure: () => ok(xlsxStructure),
      units: () => ok([]),
    })

    renderArtifact('/knowledge/artifacts/x1')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'knowledge-matrix.xlsx' }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { level: 3, name: 'Positions' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Clause')).toBeInTheDocument()
    expect(screen.getByText('Preferred')).toBeInTheDocument()
    expect(screen.getByText(/row 0, columns 0–2/)).toBeInTheDocument()
  })

  it('shows extraction warnings for an extracted source', async () => {
    stubArtifact({
      artifact: () =>
        ok({
          ...artifactDetail,
          parseWarnings: ['HEADERS_OR_FOOTERS_NOT_REPRESENTED'],
        }),
    })

    renderArtifact()

    const notice = await screen.findByText(/extracted with warnings/i)
    expect(notice).toBeInTheDocument()
    expect(
      screen.getByText(/headers or footers contain content/i),
    ).toBeInTheDocument()
  })

  it('shows a failed ingestion without review sections and keeps the original', async () => {
    stubArtifact({
      artifact: () =>
        ok({
          ...artifactDetail,
          ingestStatus: 'FAILED',
          parseWarnings: ['The document could not be parsed (IOException)'],
        }),
    })

    renderArtifact()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/knowledge ingestion failed/i)
    expect(alert).toHaveTextContent(/could not be parsed/i)
    expect(screen.queryByText('Source structure')).not.toBeInTheDocument()
    expect(screen.queryByText('Extraction units')).not.toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /download original/i }),
    ).toBeInTheDocument()
  })

  it('shows processing state while extraction is in progress', async () => {
    stubArtifact({
      artifact: () => ok({ ...artifactDetail, ingestStatus: 'EXTRACTING' }),
    })

    renderArtifact()

    expect(
      await screen.findByText(/extracting: rivell is storing and extracting/i),
    ).toBeInTheDocument()
    expect(screen.queryByText('Source structure')).not.toBeInTheDocument()
  })

  it('retries when the source request fails', async () => {
    let failures = 0
    stubArtifact({
      artifact: () => {
        failures += 1
        if (failures <= 2) {
          return jsonResponse(
            {
              code: '500',
              title: 'Server error',
              detail: 'Knowledge source is unavailable',
            },
            500,
          )
        }
        return ok(artifactDetail)
      },
    })

    renderArtifact()

    const alert = await screen.findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/knowledge request could not be completed/i)

    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'msa-playbook.docx' }),
    ).toBeInTheDocument()
  })

  it('degrades to the error state on a malformed artifact payload', async () => {
    stubArtifact({ artifact: () => ok({ id: 'a1', filename: 'broken.docx' }) })

    renderArtifact()

    const alert = await screen.findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/malformed knowledge source response/i)
  })
})
