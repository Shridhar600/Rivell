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
import { ReviewsPage } from '../routes/ReviewsPage'
import { ReviewWorkspacePage } from '../routes/ReviewWorkspacePage'

export const appRoutes = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      {
        // Temporary F1 seam: these surfaces keep their existing layout until F2/F3 restyle them.
        element: <LegacySurface />,
        children: [
          { path: 'reviews', element: <ReviewsPage /> },
          { path: 'reviews/:contractId', element: <ContractPage /> },
          { path: 'reviews/:contractId/analysis', element: <ContractAnalysisPage /> },
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
  // The workspace is full-bleed and carries its own top bar, as in the canonical design, so it
  // renders outside the sidebar shell.
  { path: '/reviews/:contractId/analysis/:reviewId', element: <ReviewWorkspacePage /> },
]

export const router = createBrowserRouter(appRoutes)
