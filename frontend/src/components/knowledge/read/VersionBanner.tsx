import { CautionIcon } from '../../ui/Icon'

/** Shown when an older immutable publication is being read, with the way back to the current one. */
export function VersionBanner({ viewing, current, what, onBack }: { viewing: number; current: number; what: string; onBack: () => void }) {
  return (
    <div className="rv-k-banner" role="note">
      <CautionIcon className="rv-icon" />
      <span>
        Viewing publication {viewing}, superseded by publication {current}. {what} as it was published in publication {viewing}.
      </span>
      <button type="button" className="rv-btn rv-btn--compact" onClick={onBack}>
        Back to current
      </button>
    </div>
  )
}
