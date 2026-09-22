import { NewReviewForm } from '../components/NewReviewForm'
import { SystemStatusPanel } from '../components/SystemStatusPanel'

export function ReviewsPage() {
  return (
    <section aria-labelledby="reviews-heading">
      <h1 id="reviews-heading">Reviews</h1>
      <NewReviewForm />
      <SystemStatusPanel />
    </section>
  )
}
