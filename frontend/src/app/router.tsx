import { createBrowserRouter, Navigate } from 'react-router'
import { AppShell } from '../components/AppShell'
import { ContractAnalysisPage } from '../routes/ContractAnalysisPage'
import { ContractPage } from '../routes/ContractPage'
import { EvidenceInspectorPage } from '../routes/EvidenceInspectorPage'
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
      { index: true, element: <Navigate to="/reviews" replace /> },
      { path: 'reviews', element: <ReviewsPage /> },
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
]

export const router = createBrowserRouter(appRoutes)
