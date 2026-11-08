import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity, BarChart3, BookOpen, Building2, ChevronsUpDown, Code2, FileText, FlaskConical, FolderKanban, GitBranch, Inbox,
  KanbanSquare, LayoutDashboard, LogOut, Moon, Network, Rocket, Search, Settings, ShieldCheck, Sparkles, Sun,
  TerminalSquare, UserPlus, Users, Video, type LucideIcon,
} from "lucide-react";
import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from "react-router";
import { api } from "../api/client";
import type { Briefing, BudgetView, Project, Workspace } from "../api/types";
import { useMe } from "../App";
import { usd } from "../lib/format";
import { useAgents } from "../lib/queries";
import { connectRealtime, useRealtime } from "../realtime/realtime";
import { AgentDrawer } from "./AgentDrawer";
import { CommandPalette, useCommandPalette } from "./CommandPalette";
import { TaskDrawer } from "./TaskDrawer";

type Space = "executive" | "office" | "agents" | "project" | "engineering";
interface NavItem { to: string; label: string; icon: LucideIcon; increment?: number }

/** The five work environments (docs/06 §2.6). The rail never changes; the context panel follows the space. */
export const SPACES: { id: Space; label: string; icon: LucideIcon; home: string }[] = [
  { id: "executive", label: "Executive", icon: BarChart3, home: "dashboard" },
  { id: "office", label: "Virtual Office", icon: Building2, home: "office" },
  { id: "agents", label: "Agents", icon: Users, home: "team" },
  { id: "project", label: "Project", icon: FolderKanban, home: "tasks" },
  { id: "engineering", label: "Engineering", icon: Code2, home: "code" },
];

export const NAV: Record<Space, NavItem[]> = {
  executive: [
    { to: "dashboard", label: "Overview", icon: LayoutDashboard },
    { to: "activity", label: "Activity", icon: Activity },
  ],
  office: [
    { to: "office", label: "Floor", icon: Building2 },
    { to: "meetings", label: "Meetings", icon: Video },
  ],
  agents: [
    { to: "team", label: "Directory", icon: Users },
    { to: "recruitment", label: "Recruitment", icon: UserPlus },
    { to: "org-chart", label: "Org chart", icon: Network },
  ],
  project: [
    { to: "briefing", label: "Lead briefing", icon: Sparkles },
    { to: "tasks", label: "Tasks", icon: KanbanSquare },
    { to: "memory", label: "Shared memory", icon: BookOpen },
    { to: "documents", label: "Documents", icon: FileText },
  ],
  engineering: [
    { to: "code", label: "Code", icon: TerminalSquare, increment: 4 },
    { to: "git", label: "Git", icon: GitBranch, increment: 4 },
    { to: "testing", label: "Testing", icon: FlaskConical, increment: 4 },
    { to: "security", label: "Security", icon: ShieldCheck, increment: 5 },
    { to: "deployment", label: "Deployment", icon: Rocket, increment: 6 },
  ],
};

function locate(path: string): { space: Space | null; page: string } {
  const parts = path.split("/").filter(Boolean);
  const p = parts.indexOf("p");
  const page = p >= 0 ? parts[p + 2] ?? "" : parts[2] ?? "";
  for (const s of Object.keys(NAV) as Space[]) if (NAV[s].some((i) => i.to === page)) return { space: s, page };
  return { space: null, page };
}

export function Shell() {
  const { workspaceId = "", projectId } = useParams();
  const queryClient = useQueryClient();
  const location = useLocation();
  const navigate = useNavigate();
  const palette = useCommandPalette();

  useEffect(() => connectRealtime(workspaceId, queryClient), [workspaceId, queryClient]);

  const workspaces = useQuery({ queryKey: ["workspaces"], queryFn: () => api.get<Workspace[]>("/api/v1/workspaces") });
  const projects = useQuery({
    queryKey: ["projects", workspaceId],
    queryFn: () => api.get<Project[]>(`/api/v1/workspaces/${workspaceId}/projects`),
  });
  const wsDashboard = useQuery({
    queryKey: ["workspace-dashboard", workspaceId],
    queryFn: () => api.get<{ activeProjects: number; pendingApprovals: number }>(`/api/v1/workspaces/${workspaceId}/dashboard`),
  });
  const waiting = useQuery({
    queryKey: ["waiting", workspaceId],
    queryFn: () => api.get<Briefing[]>(`/api/v1/workspaces/${workspaceId}/briefings/waiting`),
  });
  const inboxCount = (wsDashboard.data?.pendingApprovals ?? 0) + (waiting.data?.length ?? 0);

  const { space, page } = locate(location.pathname);
  const base = `/w/${workspaceId}`;
  const projectBase = projectId ? `${base}/p/${projectId}` : null;
  const workspace = workspaces.data?.find((w) => w.id === workspaceId);
  const project = projects.data?.find((p) => p.id === projectId);
  const immersive = page === "office";
  const pageLabel = space ? NAV[space].find((i) => i.to === page)?.label : ({ projects: "Projects", inbox: "Inbox", settings: "Settings" } as Record<string, string>)[page];

  return (
    <div className="flex h-full flex-col md:flex-row">
      {/* Rail: graphite, fixed, the five spaces */}
      <nav className="hidden w-14 shrink-0 flex-col items-center bg-graphite py-3 md:flex" aria-label="Spaces">
        <Link to={`${base}/projects`} className="mb-5 mt-1 flex h-8 w-8 items-center justify-center" title="All projects">
          <span className="h-3 w-3 rotate-45 bg-accent" />
        </Link>
        <div className="flex flex-1 flex-col items-center gap-1">
          {SPACES.map((s) => (
            <RailButton key={s.id} label={s.label} icon={s.icon} active={space === s.id}
              to={projectBase ? `${projectBase}/${s.home}` : `${base}/projects`} disabled={!projectBase} />
          ))}
        </div>
        <div className="flex flex-col items-center gap-1">
          <button onClick={palette.open} title="Search and commands (Ctrl K)" aria-label="Search and commands"
            className="flex h-9 w-9 items-center justify-center rounded-sm text-graphite-muted transition-colors hover:bg-graphite-2 hover:text-graphite-fg">
            <Search size={17} strokeWidth={1.7} />
          </button>
          <RailButton label="Inbox" icon={Inbox} to={`${base}/inbox`} active={page === "inbox"} count={inboxCount} />
          <RailButton label="Settings" icon={Settings} to={`${base}/settings`} active={page === "settings"} />
          <ThemeToggle />
          <UserMenu />
        </div>
      </nav>

      {/* Context panel */}
      {!immersive && (
        <aside className="hidden w-56 shrink-0 flex-col border-r border-line lg:flex">
          <div className="px-3 pb-2 pt-3">
            <p className="label mb-1.5 px-1">{workspace?.name ?? "Workspace"}</p>
            <label className="relative block">
              <span className="sr-only">Project</span>
              <select
                className="h-8 w-full cursor-pointer appearance-none rounded-sm border border-line-strong bg-panel pl-2.5 pr-7 text-[13px] font-medium outline-none hover:border-faint focus:border-accent"
                value={projectId ?? ""}
                onChange={(e) => navigate(e.target.value ? `${base}/p/${e.target.value}/${space ? NAV[space][0].to : "dashboard"}` : `${base}/projects`)}
              >
                <option value="">All projects</option>
                {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
              <ChevronsUpDown size={13} className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-faint" />
            </label>
          </div>
          <nav className="flex-1 overflow-y-auto px-3 py-2">
            {space && projectBase ? (
              <>
                <p className="label mb-1 px-1">{SPACES.find((s) => s.id === space)?.label}</p>
                {NAV[space].map((item) => (
                  <PanelLink key={item.to} to={`${projectBase}/${item.to}`} label={item.label} icon={item.icon} increment={item.increment} />
                ))}
              </>
            ) : (
              <>
                <p className="label mb-1 px-1">Workspace</p>
                <PanelLink to={`${base}/projects`} label="Projects" icon={FolderKanban} />
                <PanelLink to={`${base}/inbox`} label="Inbox" icon={Inbox} count={inboxCount} />
                <PanelLink to={`${base}/settings`} label="Settings" icon={Settings} />
              </>
            )}
          </nav>
          <div className="border-t border-line px-3 py-3">
            <button onClick={palette.open} className="flex h-8 w-full items-center gap-2 rounded-sm border border-line-strong bg-panel px-2.5 text-[12px] text-faint hover:border-faint">
              <Search size={13} /> Go to…
              <kbd className="ml-auto font-mono text-[11px]">Ctrl K</kbd>
            </button>
          </div>
        </aside>
      )}

      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {!immersive && (
          <header className="flex h-11 shrink-0 items-center gap-3 border-b border-line px-4 sm:px-6">
            <nav className="flex min-w-0 items-center gap-1.5 text-[13px]" aria-label="Breadcrumb">
              <Link to={`${base}/projects`} className="truncate text-faint hover:text-fg">{workspace?.name ?? "Workspace"}</Link>
              {project && <><span className="text-line-strong">/</span><Link to={`${projectBase}/dashboard`} className="truncate text-muted hover:text-fg">{project.name}</Link></>}
              {pageLabel && <><span className="text-line-strong">/</span><span className="truncate font-medium">{pageLabel}</span></>}
            </nav>
            <div className="ml-auto flex items-center gap-3">
              {projectId && <BudgetMeter projectId={projectId} />}
            </div>
          </header>
        )}
        <main className={`relative min-h-0 flex-1 ${immersive ? "overflow-hidden" : "overflow-y-auto px-4 py-6 sm:px-8"}`}>
          {immersive ? <Outlet /> : <div className="mx-auto max-w-[1360px]"><Outlet /></div>}
        </main>
        <StatusBar projectId={projectId} pending={inboxCount} />
        <MobileTabs base={base} projectBase={projectBase} space={space} inboxCount={inboxCount} />
      </div>
      <AgentDrawer workspaceId={workspaceId} />
      <TaskDrawer workspaceId={workspaceId} />
      <CommandPalette state={palette} workspaceId={workspaceId} projectId={projectId} />
    </div>
  );
}

function RailButton({ to, label, icon: Icon, active, disabled, count }: { to: string; label: string; icon: LucideIcon; active?: boolean; disabled?: boolean; count?: number }) {
  return (
    <Link to={to} title={disabled ? `${label} — choose a project first` : label} aria-label={label} aria-current={active ? "page" : undefined}
      className={`relative flex h-9 w-9 items-center justify-center rounded-sm transition-colors ${active ? "bg-graphite-2 text-graphite-fg shadow-[inset_2px_0_0_var(--accent)]" : disabled ? "text-graphite-muted/50" : "text-graphite-muted hover:bg-graphite-2 hover:text-graphite-fg"}`}>
      <Icon size={17} strokeWidth={1.7} />
      {!!count && <span className="tabular absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 font-mono text-[10px] font-medium text-white">{count}</span>}
    </Link>
  );
}

function PanelLink({ to, label, icon: Icon, increment, count }: { to: string; label: string; icon: LucideIcon; increment?: number; count?: number }) {
  return (
    <NavLink to={to}
      className={({ isActive }) =>
        `mb-px flex h-8 items-center gap-2.5 rounded-sm px-2 text-[13px] transition-colors ${isActive ? "bg-panel font-medium text-fg shadow-[inset_2px_0_0_var(--accent)]" : increment ? "text-faint hover:text-muted" : "text-muted hover:bg-panel-2 hover:text-fg"}`}>
      <Icon size={15} strokeWidth={1.7} className="shrink-0" />
      <span className="flex-1 truncate">{label}</span>
      {increment && <span className="font-mono text-[10px] text-faint" title={`Available in increment ${increment}`}>inc.{increment}</span>}
      {!!count && <span className="tabular font-mono text-[11px] text-accent-2">{count}</span>}
    </NavLink>
  );
}

function BudgetMeter({ projectId }: { projectId: string }) {
  const budget = useQuery({
    queryKey: ["budget", projectId],
    queryFn: () => api.get<BudgetView>(`/api/v1/projects/${projectId}/budget`),
  });
  if (!budget.data) return null;
  const limit = budget.data.dailyLimitUsd;
  const spent = budget.data.spend.todayUsd;
  const ratio = limit ? Math.min(spent / limit, 1) : 0;
  const color = ratio >= 1 ? "bg-danger" : ratio >= 0.8 ? "bg-warn" : "bg-fg";
  return (
    <div className="hidden items-center gap-2 sm:flex" title="Model spend today (UTC) for this project">
      <span className="text-[12px] text-faint">Today</span>
      <span className="tabular font-mono text-[12px] font-medium">{usd(spent)}</span>
      {limit ? (
        <>
          <span className="h-1 w-14 overflow-hidden rounded-full bg-line">
            <span className={`block h-full ${color}`} style={{ width: `${ratio * 100}%` }} />
          </span>
          <span className="tabular font-mono text-[12px] text-faint">{usd(limit, 2)}</span>
        </>
      ) : <span className="text-[12px] text-faint">no limit</span>}
    </div>
  );
}

function ThemeToggle() {
  const [theme, setTheme] = useState(document.documentElement.dataset.theme ?? "light");
  return (
    <button
      className="flex h-9 w-9 items-center justify-center rounded-sm text-graphite-muted transition-colors hover:bg-graphite-2 hover:text-graphite-fg"
      aria-label={theme === "dark" ? "Switch to paper theme" : "Switch to slate theme"}
      title={theme === "dark" ? "Paper theme" : "Slate theme"}
      onClick={() => {
        const next = theme === "dark" ? "light" : "dark";
        document.documentElement.dataset.theme = next;
        try {
          localStorage.setItem("aiw-theme", next);
        } catch {
          /* storage unavailable */
        }
        setTheme(next);
      }}
    >
      {theme === "dark" ? <Sun size={16} strokeWidth={1.7} /> : <Moon size={16} strokeWidth={1.7} />}
    </button>
  );
}

function UserMenu() {
  const me = useMe();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const name = me.data?.displayName ?? "";
  return (
    <div className="relative mt-2">
      <button onClick={() => setOpen(!open)} aria-label="Account" aria-expanded={open}
        className="flex h-8 w-8 items-center justify-center rounded-full bg-graphite-2 text-[11px] font-semibold text-graphite-fg ring-1 ring-white/10">
        {name.split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase()}
      </button>
      {open && (
        <div className="slide-in absolute bottom-0 left-11 z-50 w-56 rounded-md border border-line bg-panel p-1 shadow-panel">
          <div className="px-2.5 py-2">
            <p className="truncate text-[13px] font-medium">{name}</p>
            <p className="truncate text-[12px] text-faint">{me.data?.email}</p>
          </div>
          <button
            className="flex h-8 w-full items-center gap-2 rounded-sm px-2.5 text-[13px] text-muted hover:bg-panel-2 hover:text-fg"
            onClick={async () => {
              await api.post("/api/v1/auth/logout");
              queryClient.clear();
              window.location.href = "/";
            }}
          >
            <LogOut size={14} /> Sign out
          </button>
        </div>
      )}
    </div>
  );
}

function StatusBar({ projectId, pending }: { projectId?: string; pending: number }) {
  const { status, lastSeq } = useRealtime();
  const agents = useAgents(projectId ?? "");
  const list = projectId ? agents.data ?? [] : [];
  const working = list.filter((a) => a.status === "ACTIVE" && !["AVAILABLE", "BLOCKED"].includes(a.activity)).length;
  const blocked = list.filter((a) => a.status === "ACTIVE" && a.activity === "BLOCKED").length;
  const label = { live: "Live", connecting: "Connecting", reconnecting: "Reconnecting · live updates paused", offline: "Offline" }[status];
  const dot = status === "live" ? "bg-ok" : status === "offline" ? "bg-danger" : "bg-warn";
  return (
    <footer className="hidden h-7 shrink-0 items-center gap-4 bg-graphite px-4 font-mono text-[11px] text-graphite-muted md:flex">
      <span className="flex items-center gap-1.5 text-graphite-fg">
        <span className={`h-1.5 w-1.5 rounded-full ${dot}`} />
        {label}
      </span>
      {lastSeq >= 0 && <span className="tabular">seq {lastSeq}</span>}
      {projectId && agents.data && <span className="tabular">{working} at work · {list.length} agents</span>}
      {blocked > 0 && <span className="tabular text-[#E0505A]">{blocked} blocked</span>}
      {pending > 0 && <span className="tabular text-[#F06A3F]">{pending} waiting for you</span>}
    </footer>
  );
}

function MobileTabs({ base, projectBase, space, inboxCount }: { base: string; projectBase: string | null; space: Space | null; inboxCount: number }) {
  return (
    <nav className="flex h-14 shrink-0 items-stretch bg-graphite md:hidden" aria-label="Spaces">
      {SPACES.filter((s) => s.id !== "engineering").map((s) => (
        <Link key={s.id} to={projectBase ? `${projectBase}/${s.home}` : `${base}/projects`}
          className={`flex flex-1 flex-col items-center justify-center gap-0.5 text-[10px] ${space === s.id ? "text-graphite-fg shadow-[inset_0_2px_0_var(--accent)]" : "text-graphite-muted"}`}>
          <s.icon size={17} strokeWidth={1.7} />
          {s.label.replace("Virtual ", "")}
        </Link>
      ))}
      <Link to={`${base}/inbox`} className="relative flex flex-1 flex-col items-center justify-center gap-0.5 text-[10px] text-graphite-muted">
        <Inbox size={17} strokeWidth={1.7} />
        Inbox
        {inboxCount > 0 && <span className="absolute right-[28%] top-2 h-2 w-2 rounded-full bg-accent" />}
      </Link>
    </nav>
  );
}
