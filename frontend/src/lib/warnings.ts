export const DOCUMENT_WARNING_LABELS: Record<string, string> = {
  SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED: 'Some content in this file could not be shown in Rivell.',
  HEADERS_OR_FOOTERS_NOT_REPRESENTED: 'Headers or footers hold text that Rivell did not read.',
  FOOTNOTES_NOT_REPRESENTED: 'Footnotes hold text that Rivell did not read.',
  UNSUPPORTED_NESTED_TABLE: 'A table inside another table could not be read in full.',
  HUMAN_DECLARED_TYPE_DIFFERS_FROM_MACHINE_ASSESSMENT: 'The file type differs from what you chose.',
  DAMAGED_PACKAGE_REPAIRED:
    'The file was damaged. Rivell repaired it the way Word does, by skipping links to parts that are missing.',
  SEMANTIC_COMPILATION_UNAVAILABLE: 'Some checks could not run. Only basic reading was used.',
  TRACKED_CHANGES_PRESENT: 'The file already has tracked changes. Rivell read the text as if they were accepted.',
  STYLES_UNRESOLVED: 'Rivell could not read the file’s styles, so headings and numbering may be read less well.',
  COVERAGE_CHECK_FAILED: 'Rivell could not confirm it read all the text in this file.',
}

/** A code Rivell has no words for yet is never shown raw. */
const UNKNOWN_WARNING = 'Rivell noted a problem with part of this file.'

export function warningLabel(warning: string): string {
  return DOCUMENT_WARNING_LABELS[warning] ?? UNKNOWN_WARNING
}
