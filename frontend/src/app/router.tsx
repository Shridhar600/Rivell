import { createBrowserRouter, Navigate } from 'react-router'
import { AppShell, LegacySurface } from '../components/shell/AppShell'
import { ContractPage } from '../routes/ContractPage'
import { HomePage } from '../routes/HomePage'
import { ReviewLifecyclePage } from '../routes/ReviewLifecyclePage'
import { CompilationPage } from '../routes/knowledge/CompilationPage'
import { KnowledgeLayout } from '../routes/knowledge/KnowledgeLayout'
import { AddKnowledgeRedirect, ArtifactRedirect } from '../routes/knowledge/redirects'
import { EvidenceInspectorRedirect } from '../routes/retiredRedirects'
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
      {
        // One layout for every Knowledge page, so Add knowledge opens on any of them without being rebuilt.
        element: <KnowledgeLayout />,
        children: [
          { path: 'knowledge', element: <KnowledgeOverviewPage /> },
          { path: 'knowledge/playbooks', element: <PlaybooksPage /> },
          { path: 'knowledge/playbooks/:artifactId', element: <PlaybookDetailPage /> },
          { path: 'knowledge/clauses', element: <ClausesPage /> },
          { path: 'knowledge/clauses/:clauseKey', element: <ClauseDetailPage /> },
          { path: 'knowledge/sources', element: <SourcesPage /> },
          { path: 'knowledge/sources/:artifactId', element: <SourceDetailPage /> },
        ],
      },
      { path: 'knowledge/new', element: <AddKnowledgeRedirect /> },
      { path: 'knowledge/artifacts/:artifactId', element: <ArtifactRedirect /> },
      // Retired v1 pages: the old analysis page (Start review and the Reviews list replace it), the
      // evidence inspector (a developer view) and the publication records (the Knowledge pages show
      // what each version published). Old links land on the page that replaced them.
      { path: 'reviews/:contractId/analysis', element: <Navigate replace to="/reviews" /> },
      { path: 'reviews/:contractId/evidence', element: <EvidenceInspectorRedirect /> },
      { path: 'knowledge/publications', element: <Navigate replace to="/knowledge" /> },
      { path: 'knowledge/publications/:publicationId', element: <Navigate replace to="/knowledge" /> },
      {
        // Temporary seam: the contract page keeps its v1 layout until its semantic view moves into
        // the review workspace and overview (DW-076 phase 2).
        element: <LegacySurface />,
        children: [{ path: 'reviews/:contractId', element: <ContractPage /> }],
      },
    ],
  },
  // The workspace is full-bleed and carries its own top bar, as in the canonical design, so it
  // renders outside the sidebar shell.
  { path: '/reviews/:contractId/analysis/:reviewId', element: <ReviewWorkspacePage /> },
  // The compilation workspace is full-bleed and carries its own top bar, as in the canonical design,
  // so it renders outside the sidebar shell.
  { path: '/knowledge/compilations/:artifactId', element: <CompilationPage /> },
]

export const router = createBrowserRouter(appRoutes)
