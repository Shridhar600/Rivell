export const VERIFICATION_LABELS: Record<string, string> = {
  VERIFIED: 'Supported by source',
  UNCERTAIN: 'Verification uncertain',
  DISPUTED: 'Source support disputed',
  NOT_VERIFIED: 'Not independently verified',
}

export function humanizeCode(code: string): string {
  const lower = code
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replaceAll('_', ' ')
    .toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}
