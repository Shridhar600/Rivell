const SOURCE_FILE = /\.(docx|xlsx)$/i

/** Why a file cannot be used as Knowledge, or null. Only DOCX and XLSX are read deeply. PDF is not supported. */
export function sourceFileProblem(file: Pick<File, 'name'>): string | null {
  return SOURCE_FILE.test(file.name) ? null : 'Choose a Word or Excel file. PDF is not supported.'
}
