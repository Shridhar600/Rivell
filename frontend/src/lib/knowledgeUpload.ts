const SOURCE_FILE = /\.(docx|xlsx)$/i

/** Why a file cannot be used as Knowledge, or null. Only DOCX and XLSX are read deeply. PDF is not supported. */
export function sourceFileProblem(file: Pick<File, 'name'>): string | null {
  return SOURCE_FILE.test(file.name) ? null : 'Choose a Word or Excel file. PDF is not supported.'
}

const TYPE_IN_NAME: { pattern: RegExp; types: string[] }[] = [
  // The matching rule treats every NDA kind as one family, so a suggested NDA covers all three.
  { pattern: /(^|[^a-z])(nda|mnda|onda)([^a-z]|$)|non[- ]?disclosure|confidentiality/, types: ['NDA', 'MNDA', 'ONDA'] },
  { pattern: /(^|[^a-z])msa([^a-z]|$)|master services/, types: ['MSA'] },
  { pattern: /(^|[^a-z])dpa([^a-z]|$)|data processing/, types: ['DPA'] },
  { pattern: /(^|[^a-z])saas([^a-z]|$)|subscription/, types: ['SAAS'] },
  { pattern: /(^|[^a-z])sow([^a-z]|$)|statement of work/, types: ['SOW'] },
  { pattern: /licen[cs]e/, types: ['SWL'] },
]

/**
 * A deterministic suggestion from the file name, used only to pre-tick the contract types at
 * upload. The reviewer can change it, and a name that names no type suggests nothing.
 */
export function suggestContractTypes(fileName: string): string[] {
  const name = fileName.toLowerCase()
  const match = TYPE_IN_NAME.find((entry) => entry.pattern.test(name))
  return match ? [...match.types] : []
}
