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
    ['Supply Agreement.docx', ['SUPPLY']],
    ['Purchase Agreement.docx', ['SUPPLY']],
    ['Professional Services Agreement.docx', ['SERVICES']],
    ['Consulting Agreement.docx', ['SERVICES']],
    ['Reseller Agreement.docx', ['RESELLER']],
    ['Distribution Agreement.docx', ['RESELLER']],
    ['Order Form.docx', ['ORDER_FORM']],
    ['Purchase Order.docx', ['ORDER_FORM']],
    ['Amendment.docx', ['AMENDMENT']],
    ['Variation Agreement.docx', ['AMENDMENT']],
    ['Data Processing Addendum.docx', ['DPA']],
    ['Amendment to MSA.docx', ['AMENDMENT']],
    ['Software Licence Amendment.docx', ['AMENDMENT']],
    ['Master Agreement.docx', ['MSA']],
    ['Master-Services Agreement.docx', ['MSA']],
    ['Data-Processing Addendum.docx', ['DPA']],
    ['SaaS-Order-Form.docx', ['ORDER_FORM']],
  ])('suggests from %s', (filename, expected) => {
    expect(suggestContractTypes(filename)).toEqual(expected)
  })

  it('suggests nothing when the name names no contract type', () => {
    expect(suggestContractTypes('Something Else.docx')).toEqual([])
    expect(suggestContractTypes('Standard Terms.docx')).toEqual([])
  })

  it('is case-insensitive', () => {
    expect(suggestContractTypes('COMMERCIAL MSA.DOCX')).toEqual(['MSA'])
  })
})
