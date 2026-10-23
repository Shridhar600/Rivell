import { describe, expect, it } from 'vitest'
import { suggestContractTypes } from './knowledgeUpload'

describe('suggestContractTypes', () => {
  it.each([
    ['Mutual NDA.docx', ['NDA', 'MNDA', 'ONDA']],
    ['MNDA.docx', ['NDA', 'MNDA', 'ONDA']],
    ['ONDA.docx', ['NDA', 'MNDA', 'ONDA']],
    ['Non-Disclosure Agreement.docx', ['NDA', 'MNDA', 'ONDA']],
    ['Confidentiality Undertaking.docx', ['NDA', 'MNDA', 'ONDA']],
    ['Master Services Agreement.docx', ['MSA']],
    ['Data Processing Agreement.docx', ['DPA']],
    ['SaaS Subscription Order.docx', ['SAAS']],
    ['Statement of Work.docx', ['SOW']],
    ['Software Licence.docx', ['SWL']],
    ['Software License Terms.docx', ['SWL']],
  ])('suggests from %s', (filename, expected) => {
    expect(suggestContractTypes(filename)).toEqual(expected)
  })

  it('suggests nothing when the name names no contract type', () => {
    expect(suggestContractTypes('Supply Agreement.docx')).toEqual([])
    expect(suggestContractTypes('Standard Terms.docx')).toEqual([])
  })

  it('is case-insensitive', () => {
    expect(suggestContractTypes('COMMERCIAL MSA.DOCX')).toEqual(['MSA'])
  })
})
