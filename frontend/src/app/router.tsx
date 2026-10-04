import { createBrowserRouter } from 'react-router'
import { AppShell, LegacySurface } from '../components/shell/AppShell'
import { ContractAnalysisPage } from '../routes/ContractAnalysisPage'
import { ContractPage } from '../routes/ContractPage'
import { EvidenceInspectorPage } from '../routes/EvidenceInspectorPage'
import { HomePage } from '../routes/HomePage'
import { KnowledgeArtifactPage } from '../routes/KnowledgeArtifactPage'
import { KnowledgeNewPage } from '../routes/KnowledgeNewPage'
import { KnowledgePage } from '../routes/KnowledgePage'
import { KnowledgePublicationPage } from '../routes/KnowledgePublicationPage'
import { KnowledgePublicationsPage } from '../routes/KnowledgePublicationsPage'
import { ReviewLifecyclePage } from '../routes/ReviewLifecyclePage'
import { ReviewsPage } from '../routes/ReviewsPage'
import { StartReviewPage } from '../routes/StartReviewPage'
import { ReviewWorkspacePage } from '../routes/ReviewWorkspacePage'

export const appRoutes = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'reviews', element: <ReviewsPage /> },
      { path: 'reviews/new', element: <StartReviewPage /> },
      // The per-Review lifecycle surface. It sits outside LegacySurface: it is a canonical M10B page.
      { path: 'reviews/:contractId/overview/:reviewId/:tab?', element: <ReviewLifecyclePage /> },
      {
        // Temporary seam: these surfaces keep their existing layout until they are restyled.
        element: <LegacySurface />,
        children: [
          { path: 'reviews/:contractId', element: <ContractPage /> },
          { path: 'reviews/:contractId/analysis', element: <ContractAnalysisPage /> },
          { path: 'reviews/:contractId/analysis/:reviewId', element: <ReviewWorkspacePage /> },
          { path: 'reviews/:contractId/evidence', element: <EvidenceInspectorPage /> },
          { path: 'knowledge', element: <KnowledgePage /> },
          { path: 'knowledge/new', element: <KnowledgeNewPage /> },
          { path: 'knowledge/artifacts/:artifactId', element: <KnowledgeArtifactPage /> },
          { path: 'knowledge/publications', element: <KnowledgePublicationsPage /> },
          { path: 'knowledge/publications/:publicationId', element: <KnowledgePublicationPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
