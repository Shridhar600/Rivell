import { CautionIcon } from '../../ui/Icon'

/** Shown when an older immutable publication is being read, with the way back to the current one. */
export function VersionBanner({ viewing, current, onBack }: { viewing: number; current: number; onBack: () => void }) {
  return (
    <div className="rv-k-banner" role="note">
      <CautionIcon className="rv-icon" />
      <span>
        You are viewing knowledge version {viewing}. Version {current} is current.
      </span>
      <button type="button" className="rv-btn rv-btn--compact" onClick={onBack}>
        Back to current
      </button>
    </div>
  )
}
