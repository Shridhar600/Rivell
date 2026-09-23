import { createBrowserRouter, Navigate } from 'react-router'
import { AppShell } from '../components/AppShell'
import { ContractPage } from '../routes/ContractPage'
import { KnowledgeArtifactPage } from '../routes/KnowledgeArtifactPage'
import { KnowledgeNewPage } from '../routes/KnowledgeNewPage'
import { KnowledgePage } from '../routes/KnowledgePage'
import { ReviewsPage } from '../routes/ReviewsPage'

export const appRoutes = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/reviews" replace /> },
      { path: 'reviews', element: <ReviewsPage /> },
      { path: 'reviews/:contractId', element: <ContractPage /> },
      { path: 'knowledge', element: <KnowledgePage /> },
      { path: 'knowledge/new', element: <KnowledgeNewPage /> },
      { path: 'knowledge/artifacts/:artifactId', element: <KnowledgeArtifactPage /> },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
