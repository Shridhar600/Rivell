export const DOCUMENT_WARNING_LABELS: Record<string, string> = {
  SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED:
    'Some document content could not be represented in the structural view.',
  HEADERS_OR_FOOTERS_NOT_REPRESENTED:
    'Headers or footers contain content that is not represented.',
  FOOTNOTES_NOT_REPRESENTED:
    'Footnotes contain content that is not represented.',
  UNSUPPORTED_NESTED_TABLE:
    'A nested table could not be fully represented.',
  HUMAN_DECLARED_TYPE_DIFFERS_FROM_MACHINE_ASSESSMENT:
    'The file type differs from what you chose.',
  DAMAGED_PACKAGE_REPAIRED:
    'The file was damaged. Rivell repaired it the way Word does, by skipping links to parts that are missing.',
  SEMANTIC_COMPILATION_UNAVAILABLE:
    'Some checks could not run. Only basic reading was used.',
}

export function warningLabel(warning: string): string {
  return DOCUMENT_WARNING_LABELS[warning] ?? warning
}
