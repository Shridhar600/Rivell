import { createBrowserRouter, Navigate } from 'react-router'
import { AppShell } from '../components/AppShell'
import { KnowledgePage } from '../routes/KnowledgePage'
import { ReviewsPage } from '../routes/ReviewsPage'

export const appRoutes = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/reviews" replace /> },
      { path: 'reviews', element: <ReviewsPage /> },
      { path: 'knowledge', element: <KnowledgePage /> },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
