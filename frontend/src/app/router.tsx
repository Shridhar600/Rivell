import { createBrowserRouter } from 'react-router'
import { AppShell, LegacySurface } from '../components/shell/AppShell'
import { ContractAnalysisPage } from '../routes/ContractAnalysisPage'
import { ContractPage } from '../routes/ContractPage'
import { EvidenceInspectorPage } from '../routes/EvidenceInspectorPage'
import { HomePage } from '../routes/HomePage'
import { KnowledgeArtifactPage } from '../routes/KnowledgeArtifactPage'
import { KnowledgeNewPage } from '../routes/KnowledgeNewPage'
import { KnowledgePublicationPage } from '../routes/KnowledgePublicationPage'
import { KnowledgePublicationsPage } from '../routes/KnowledgePublicationsPage'
import { ReviewLifecyclePage } from '../routes/ReviewLifecyclePage'
import { ClauseDetailPage } from '../routes/knowledge/ClauseDetailPage'
import { ClausesPage } from '../routes/knowledge/ClausesPage'
import { KnowledgeOverviewPage } from '../routes/knowledge/KnowledgeOverviewPage'
import { PlaybookDetailPage } from '../routes/knowledge/PlaybookDetailPage'
import { PlaybooksPage } from '../routes/knowledge/PlaybooksPage'
import { SourceDetailPage } from '../routes/knowledge/SourceDetailPage'
import { SourcesPage } from '../routes/knowledge/SourcesPage'
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
      { path: 'knowledge', element: <KnowledgeOverviewPage /> },
      { path: 'knowledge/playbooks', element: <PlaybooksPage /> },
      { path: 'knowledge/playbooks/:artifactId', element: <PlaybookDetailPage /> },
      { path: 'knowledge/clauses', element: <ClausesPage /> },
      { path: 'knowledge/clauses/:clauseKey', element: <ClauseDetailPage /> },
      { path: 'knowledge/sources', element: <SourcesPage /> },
      { path: 'knowledge/sources/:artifactId', element: <SourceDetailPage /> },
      {
        // Temporary seam: these surfaces keep their existing layout until they are restyled. Add knowledge,
        // the compilation workspace and the publication records stay here: they are not read surfaces.
        element: <LegacySurface />,
        children: [
          { path: 'reviews/:contractId', element: <ContractPage /> },
          { path: 'reviews/:contractId/analysis', element: <ContractAnalysisPage /> },
          { path: 'reviews/:contractId/evidence', element: <EvidenceInspectorPage /> },
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
