/**
 * The taxonomy as the tests' fake servers answer it. Real keys and labels, trimmed to the ones the
 * fixtures use, plus ONWARD_DISCLOSURE whose label is not its humanized key so a test can tell the
 * two apart.
 */
export const taxonomyFixture = {
  version: 1,
  parents: [
    { key: 'PARTIES_AND_DEFINITIONS', label: 'Parties and definitions', parentKey: null, definition: null },
    { key: 'TERM_AND_TERMINATION', label: 'Term and termination', parentKey: null, definition: null },
    { key: 'CONFIDENTIALITY', label: 'Confidentiality', parentKey: null, definition: null },
    { key: 'LIABILITY_AND_INDEMNITY', label: 'Liability and indemnity', parentKey: null, definition: null },
    { key: 'DISPUTES_AND_LAW', label: 'Disputes and law', parentKey: null, definition: null },
  ],
  leaves: [
    { key: 'TERM', label: 'Term', parentKey: 'TERM_AND_TERMINATION', definition: null },
    { key: 'RENEWAL', label: 'Renewal', parentKey: 'TERM_AND_TERMINATION', definition: null },
    {
      key: 'CONFIDENTIALITY_OBLIGATIONS',
      label: 'Confidentiality obligations',
      parentKey: 'CONFIDENTIALITY',
      definition: null,
    },
    {
      key: 'ONWARD_DISCLOSURE',
      label: 'Disclosure to representatives',
      parentKey: 'CONFIDENTIALITY',
      definition: null,
    },
    {
      key: 'LIMITATION_OF_LIABILITY',
      label: 'Limitation of liability',
      parentKey: 'LIABILITY_AND_INDEMNITY',
      definition: null,
    },
    { key: 'INDEMNIFICATION', label: 'Indemnification', parentKey: 'LIABILITY_AND_INDEMNITY', definition: null },
    { key: 'GOVERNING_LAW', label: 'Governing law', parentKey: 'DISPUTES_AND_LAW', definition: null },
  ],
}

export function taxonomyJsonResponse(fixture: unknown = taxonomyFixture, status = 200): Response {
  return new Response(JSON.stringify(fixture), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
