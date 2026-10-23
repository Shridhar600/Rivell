import { createBrowserRouter, Navigate } from 'react-router'
import { AppShell } from '../components/shell/AppShell'
import { HomePage } from '../routes/HomePage'
import { ReviewLifecyclePage } from '../routes/ReviewLifecyclePage'
import { CompilationPage } from '../routes/knowledge/CompilationPage'
import { KnowledgeLayout } from '../routes/knowledge/KnowledgeLayout'
import { AddKnowledgeRedirect, ArtifactRedirect } from '../routes/knowledge/redirects'
import { ContractPageRedirect } from '../routes/retiredRedirects'
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
      // The per-Review lifecycle surface.
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
      // Retired v1 pages: the contract page and its evidence inspector (a contract's analysis lives
      // in its review now), the old analysis page (Start review and the Reviews list replace it) and
      // the publication records (the Knowledge pages show what each version published). Old links
      // land on the page that replaced them.
      { path: 'reviews/:contractId', element: <ContractPageRedirect /> },
      { path: 'reviews/:contractId/evidence', element: <ContractPageRedirect /> },
      { path: 'reviews/:contractId/analysis', element: <Navigate replace to="/reviews" /> },
      { path: 'knowledge/publications', element: <Navigate replace to="/knowledge" /> },
      { path: 'knowledge/publications/:publicationId', element: <Navigate replace to="/knowledge" /> },
      // Full-width work surfaces. They keep the sidebar like every other page and bring their own panels.
      { path: 'reviews/:contractId/analysis/:reviewId', element: <ReviewWorkspacePage /> },
      { path: 'knowledge/compilations/:artifactId', element: <CompilationPage /> },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
