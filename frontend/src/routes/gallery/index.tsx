import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { GalleryPage } from '@/components/gallery/GalleryPage'

/** The Gallery's tab, kept in the URL so a link opens it (`?group=basics`, `?group=mistakes`). */
export interface GallerySearch {
  group?: string
}

export function validateSearch(search: Record<string, unknown>): GallerySearch {
  const group = search.group
  return { group: typeof group === 'string' && group.trim().length > 0 ? group : undefined }
}

export const Route = createFileRoute('/gallery/')({
  validateSearch,
  component: GalleryRoute,
})

function GalleryRoute() {
  const { group } = Route.useSearch()
  const navigate = useNavigate()
  return (
    <Layout>
      <GalleryPage group={group} onGroupChange={(next) => navigate({ to: '/gallery', search: { group: next } })} />
    </Layout>
  )
}
