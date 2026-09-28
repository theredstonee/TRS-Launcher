// News-Beiträge des Teams (§30) – Typen und kleine Helfer für Website und Team-Bereich. Die automatischen
// Update-Beiträge aus CHANGELOG.md stehen weiter in composables/useSite.ts (BlogPostSummary).

export type BlogLang = 'en' | 'de' | 'es'
export const BLOG_LANGS: BlogLang[] = ['en', 'de', 'es']
export type BlogState = 'draft' | 'scheduled' | 'published'

export interface NewsAuthor {
  uuid: string
  name: string
  skin: string | null
}

export interface NewsCover {
  url: string
  thumbUrl: string
  width: number
  height: number
}

export interface NewsSummary {
  kind: 'news'
  slug: string
  publishedAt: string
  updatedAt: string
  langs: BlogLang[]
  title: Partial<Record<BlogLang, string>>
  summary: Partial<Record<BlogLang, string>>
  cover: NewsCover | null
  author: NewsAuthor | null
}

export interface NewsPost extends NewsSummary {
  markdown: Partial<Record<BlogLang, string>>
}

export interface BlogText {
  title: string
  summary: string
  body: string
}

export interface BlogMediaView {
  id: string
  url: string
  thumbUrl: string
  width: number
  height: number
  bytes: number
  createdAt: string
}

export interface StaffRefView {
  uuid: string
  name: string | null
}

export interface AdminBlogPost {
  id: string
  slug: string
  state: BlogState
  publishAt: string | null
  texts: Partial<Record<BlogLang, BlogText>>
  cover: BlogMediaView | null
  author: NewsAuthor | null
  rev: number
  createdAt: string
  createdBy: StaffRefView
  updatedAt: string
  updatedBy: StaffRefView
  media: BlogMediaView[]
  path: string
}

export interface AdminBlogListItem {
  id: string
  slug: string
  state: BlogState
  publishAt: string | null
  titles: Partial<Record<BlogLang, string>>
  langs: BlogLang[]
  cover: BlogMediaView | null
  author: NewsAuthor | null
  updatedAt: string
  updatedBy: StaffRefView
  path: string
}

/** Sprache, in der ein News-Beitrag gezeigt wird: die Seitensprache, wenn übersetzt, sonst Englisch. */
export function newsLang(post: Pick<NewsSummary, 'langs'>, lang: string): BlogLang {
  return post.langs.includes(lang as BlogLang) ? (lang as BlogLang) : 'en'
}

/** Text eines Feldes in der Seitensprache mit Rückfall auf Englisch. */
export function newsText(post: NewsSummary | NewsPost, field: 'title' | 'summary' | 'markdown', lang: string): string {
  const texts = (post as unknown as Record<string, Partial<Record<BlogLang, string>>>)[field] ?? {}
  return texts[newsLang(post, lang)] ?? texts.en ?? ''
}

/** Slug eines News-Beitrags (nie wie eine Version `0.6.5`). */
export const NEWS_SLUG = /^(?=[a-z0-9-]*[a-z])[a-z0-9]+(?:-[a-z0-9]+)*$/
