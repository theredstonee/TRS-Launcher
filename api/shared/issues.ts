/**
 * Issues & Roadmap (API.md §28) – Werte, Grenzen und Antwort-Formen, gemeinsam für Server und Website
 * (reine Daten, keine Importe).
 */

export const ISSUE_TYPES = ['bug', 'feature'] as const
export type IssueType = (typeof ISSUE_TYPES)[number]

export const ISSUE_AREAS = ['launcher', 'client', 'website'] as const
export type IssueArea = (typeof ISSUE_AREAS)[number]

export const ISSUE_STATUSES = ['open', 'planned', 'in_progress', 'in_review', 'done', 'rejected', 'duplicate'] as const
export type IssueStatus = (typeof ISSUE_STATUSES)[number]

/** Geschlossen = erledigt, abgelehnt oder Duplikat. */
export const CLOSED_STATUSES: readonly IssueStatus[] = ['done', 'rejected', 'duplicate']
export const isClosed = (s: IssueStatus): boolean => CLOSED_STATUSES.includes(s)

export const ISSUE_PRIORITIES = ['low', 'medium', 'high', 'critical'] as const
export type IssuePriority = (typeof ISSUE_PRIORITIES)[number]

export const ISSUE_SORTS = ['top', 'new', 'activity'] as const
export type IssueSort = (typeof ISSUE_SORTS)[number]

export const ISSUE_TAG = /^[a-z0-9][a-z0-9-]{0,23}$/
export const ISSUE_VERSION = /^\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][0-9A-Za-z.-]{1,20})?$/
export const UPLOAD_ID = /^[A-Za-z0-9_-]{22}$/

export const ISSUE_LIMITS = {
  titleMin: 5,
  titleMax: 120,
  descriptionMax: 8000,
  /** Die Website verlangt mindestens so viele Zeichen (der Client darf weniger schicken). */
  descriptionMinWeb: 10,
  commentMax: 5000,
  noteMax: 2000,
  tagsMax: 6,
  uploadsPerIssue: 6,
  uploadsPerComment: 4,
  uploadMaxBytes: 8 * 1024 * 1024,
  issuesPerDay: 10,
  commentsPerDay: 60,
  uploadsPerDay: 30,
  metaFieldMax: 32,
  modsMax: 300,
  modMax: 100,
  logMax: 20_000,
  perPage: 20,
  perPageMax: 50,
  queryMax: 80,
  /** Karten je Roadmap-Spalte beim ersten Laden bzw. je „Mehr laden“. */
  roadmapPer: 20,
  roadmapPerMax: 50,
  filterMax: 300,
} as const

export interface PlayerRefView {
  uuid: string
  name: string
  /** Zuletzt gesehene Skin-Adresse (textures.minecraft.net) für den Kopf, sonst `null`. */
  skin?: string | null
}

/** Bild an einem Issue oder Kommentar (bzw. frisch hochgeladen). */
export interface UploadView {
  id: string
  url: string
  thumbUrl: string
  width: number
  height: number
}

export interface IssueRefView {
  number: number
  title: string
  status: IssueStatus
  url: string
}

/** Listen-Sicht (§28.2). */
export interface IssueView {
  number: number
  url: string
  type: IssueType
  area: IssueArea
  status: IssueStatus
  title: string
  author: PlayerRefView | null
  authorTeam: boolean
  score: number
  up: number
  down: number
  comments: number
  priority: IssuePriority | null
  assignee: PlayerRefView | null
  tags: string[]
  fixedIn: string | null
  duplicateOf: IssueRefView | null
  locked: boolean
  source: 'web' | 'client'
  createdAt: string
  updatedAt: string
  activityAt: string
  closedAt: string | null
  /** Nur bei angemeldeten Anfragen. */
  myVote?: -1 | 0 | 1
  /** Nur für das Team (Admin-Liste, gelöschte Issues). */
  deleted?: boolean
}

/** Technische Infos (vom TRS Client). `log` nur für Ersteller und Team. */
export interface IssueMetaView {
  modVersion: string | null
  mcVersion: string | null
  loader: string | null
  mods: string[]
  hasLog: boolean
  log?: string
}

export interface IssueNoteView {
  id: number
  at: string
  author: PlayerRefView | null
  text: string
}

export interface IssueDetail extends IssueView {
  description: string
  attachments: UploadView[]
  editedAt: string | null
  meta: IssueMetaView | null
  following?: boolean
  can: { edit: boolean, comment: boolean, vote: boolean, manage: boolean, moderate: boolean }
  notes?: IssueNoteView[]
}

export interface IssueCommentView {
  id: number
  author: PlayerRefView | null
  team: boolean
  body: string | null
  attachments: UploadView[]
  createdAt: string
  editedAt: string | null
  deleted: boolean
  deletedBy: 'author' | 'team' | null
  mine: boolean
}

export type IssueHistoryAction =
  | 'status' | 'fixed_in' | 'assignee' | 'priority' | 'type' | 'area' | 'tags' | 'title'
  | 'locked' | 'unlocked' | 'merged_into' | 'merged_from' | 'deleted' | 'restored'

export interface IssueHistoryEntry {
  at: string
  actor: PlayerRefView | null
  action: IssueHistoryAction
  from: string | null
  to: string | null
}

/** Antwort von GET /v1/issues/{number}. */
export interface IssuePageView {
  issue: IssueDetail
  comments: IssueCommentView[]
  history: IssueHistoryEntry[]
}

export interface IssueListResult {
  issues: IssueView[]
  total: number
  page: number
  pages: number
  per: number
  /** Nicht verstandene Teile der Suchsyntax. */
  errors?: string[]
}

export interface RoadmapColumnView {
  status: IssueStatus
  /** Alle Issues dieser Spalte (mit Filter). */
  total: number
  issues: IssueView[]
  hasMore: boolean
}

export interface RoadmapResult {
  columns: RoadmapColumnView[]
  per: number
  errors?: string[]
}

/** Zähler für die Seitenleiste. `mine`/`following` nur angemeldet, `team` nur mit issues.manage. */
export interface IssueSummary {
  open: number
  total: number
  byStatus: Record<IssueStatus, number>
  mine?: number
  following?: number
  team?: { new: number, mine: number }
}

export type IssueChange = 'status' | 'team_comment' | 'fixed' | 'merged'

/** Ereignis an Folgende (`/v1/events/me`, §28.6). */
export interface IssueUpdatedEvent {
  type: 'issue_updated'
  change: IssueChange
  issue: { number: number, title: string, type: IssueType, area: IssueArea, status: IssueStatus, url: string }
  by: PlayerRefView | null
  status: IssueStatus
  fixedIn: string | null
  mergedInto: { number: number, title: string, url: string } | null
  excerpt: string | null
  at: string
}
