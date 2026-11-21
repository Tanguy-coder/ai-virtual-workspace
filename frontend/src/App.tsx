import { useQuery } from "@tanstack/react-query";
import { Navigate, Route, Routes } from "react-router";
import { BranchesPage } from "./pages/engineering/BranchesPage";
import { CodePage } from "./pages/engineering/CodePage";
import { MergeRequestsPage } from "./pages/engineering/MergeRequestsPage";
import { RunPage } from "./pages/engineering/RunPage";
import { RunsPage } from "./pages/engineering/RunsPage";
import { SecurityPage } from "./pages/engineering/SecurityPage";
import { TestingPage } from "./pages/engineering/TestingPage";
import { api, ApiError } from "./api/client";
import type { User, Workspace } from "./api/types";
import { Shell } from "./components/Shell";
import { ActivityPage } from "./pages/ActivityPage";
import { AuthPage } from "./pages/AuthPage";
import { DashboardPage } from "./pages/DashboardPage";
import { DocumentsPage } from "./pages/DocumentsPage";
import { InboxPage } from "./pages/InboxPage";
import { LeadBriefingPage } from "./pages/LeadBriefingPage";
import { MeetingsPage } from "./pages/MeetingsPage";
import { MemoryPage } from "./pages/MemoryPage";
import { TasksPage } from "./pages/TasksPage";
import { NotAvailablePage } from "./pages/NotAvailablePage";
import { OfficePage } from "./pages/OfficePage";
import { OnboardingPage } from "./pages/OnboardingPage";
import { OrgChartPage } from "./pages/OrgChartPage";
import { RecruitmentPage } from "./pages/RecruitmentPage";
import { ProjectsPage } from "./pages/ProjectsPage";
import { SettingsPage } from "./pages/SettingsPage";
import { TeamPage } from "./pages/TeamPage";

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => api.get<User>("/api/v1/auth/me"),
    retry: false,
  });
}

export function App() {
  const me = useMe();
  const workspaces = useQuery({
    queryKey: ["workspaces"],
    queryFn: () => api.get<Workspace[]>("/api/v1/workspaces"),
    enabled: me.isSuccess,
  });

  if (me.isPending) return <Splash />;
  if (me.error instanceof ApiError && me.error.status === 401) {
    return (
      <Routes>
        <Route path="/register" element={<AuthPage mode="register" />} />
        <Route path="*" element={<AuthPage mode="login" />} />
      </Routes>
    );
  }
  if (me.isError) return <Splash message={me.error.message} />;
  if (workspaces.isPending) return <Splash />;
  const first = workspaces.data?.[0];

  return (
    <Routes>
      <Route path="/onboarding" element={<OnboardingPage />} />
      <Route path="/w/:workspaceId" element={<Shell />}>
        <Route index element={<Navigate to="projects" replace />} />
        <Route path="projects" element={<ProjectsPage />} />
        <Route path="inbox" element={<InboxPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="p/:projectId">
          <Route index element={<Navigate to="dashboard" replace />} />
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="team" element={<TeamPage />} />
          <Route path="documents" element={<DocumentsPage />} />
          <Route path="activity" element={<ActivityPage />} />
          <Route path="office" element={<OfficePage />} />
          <Route path="org-chart" element={<OrgChartPage />} />
          <Route path="recruitment" element={<RecruitmentPage />} />
          <Route path="briefing" element={<LeadBriefingPage />} />
          <Route path="tasks" element={<TasksPage />} />
          <Route path="memory" element={<MemoryPage />} />
          <Route path="meetings" element={<MeetingsPage />} />
          <Route path="meetings/:meetingId" element={<MeetingsPage />} />
          <Route path="runs" element={<RunsPage />} />
          <Route path="runs/:taskId" element={<RunPage />} />
          <Route path="merge-requests" element={<MergeRequestsPage />} />
          <Route path="merge-requests/:mrId" element={<MergeRequestsPage />} />
          <Route path="code" element={<CodePage />} />
          <Route path="git" element={<BranchesPage />} />
          <Route path="testing" element={<TestingPage />} />
          <Route path="security" element={<SecurityPage />} />
          <Route path="deployment" element={<NotAvailablePage feature="Deployment Center" increment={6} />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to={first ? `/w/${first.id}/projects` : "/onboarding"} replace />} />
    </Routes>
  );
}

function Splash({ message }: { message?: string }) {
  return (
    <div className="flex h-full items-center justify-center text-[13px] text-muted">
      {message ?? "Loading…"}
    </div>
  );
}
