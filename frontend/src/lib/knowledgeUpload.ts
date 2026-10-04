const SOURCE_FILE = /\.(docx|xlsx)$/i

/** Why a file cannot be used as Knowledge, or null. Only DOCX and XLSX are read deeply. PDF is not supported. */
export function sourceFileProblem(file: Pick<File, 'name'>): string | null {
  return SOURCE_FILE.test(file.name) ? null : 'Only .docx and .xlsx files are supported. PDF is not supported.'
}
