import * as React from "react"
import { toast } from "sonner"
import { TooltipProvider } from "@/components/ui/tooltip"
import { AppShell, type Scope, type ThemeChoice } from "@/gondola/shell/AppShell"
import { ROLE_HOME, findNavItem } from "@/gondola/shell/nav"
import { Toaster } from "@/gondola/components/Toaster"
import { DashboardScreen } from "@/gondola/screens/Dashboard"
import { PosScreen } from "@/gondola/screens/Pos"
import { IntakeScreen } from "@/gondola/screens/Intake"
import { ImportReviewScreen } from "@/gondola/screens/ImportReview"
import { OwnerModulesScreen } from "@/gondola/screens/OwnerModules"
import { DesignSystemScreen } from "@/gondola/screens/DesignSystem"
import { PlaceholderScreen } from "@/gondola/screens/Placeholder"
import { RecallAlert } from "@/gondola/screens/RecallAlert"
import type { Role } from "@/gondola/data"

function readInitialTheme(): ThemeChoice {
  const a = document.documentElement.getAttribute("data-theme")
  return a === "dark" || a === "light" ? a : "system"
}

function useResolvedTheme(choice: ThemeChoice): "light" | "dark" {
  const mq = React.useMemo(() => window.matchMedia?.("(prefers-color-scheme: dark)"), [])
  const [systemDark, setSystemDark] = React.useState(() => !!mq?.matches)
  React.useEffect(() => {
    if (!mq) return
    const on = (e: MediaQueryListEvent) => setSystemDark(e.matches)
    mq.addEventListener?.("change", on)
    return () => mq.removeEventListener?.("change", on)
  }, [mq])
  return choice === "system" ? (systemDark ? "dark" : "light") : choice
}

export default function App() {
  const [role, setRole] = React.useState<Role>("TENANT_ADMIN")
  const [navKey, setNavKey] = React.useState("inicio")
  const [scope, setScope] = React.useState<Scope>("all")
  const [theme, setTheme] = React.useState<ThemeChoice>(readInitialTheme)
  const [recallOpen, setRecallOpen] = React.useState(false)
  const [recallSeen, setRecallSeen] = React.useState(false)
  const resolved = useResolvedTheme(theme)

  React.useEffect(() => {
    const el = document.documentElement
    if (theme === "system") el.removeAttribute("data-theme")
    else el.setAttribute("data-theme", theme)
  }, [theme])

  const navigate = (key: string) => {
    setNavKey(key)
    document.getElementById("contenido")?.scrollTo({ top: 0 })
  }

  const changeRole = (r: Role) => {
    setRole(r)
    setScope("all")
    const current = findNavItem(r, navKey)
    // Góndola UI se mantiene; el resto va al inicio del rol
    navigate(current && current.key === "ds" ? "ds" : ROLE_HOME[r])
  }

  const item = findNavItem(role, navKey) ?? findNavItem(role, ROLE_HOME[role])!

  let screen: React.ReactNode
  switch (item.screen) {
    case "inicio":
      screen = <DashboardScreen role={role} scope={scope} onScope={setScope} onOpenRecall={() => setRecallOpen(true)} recallAcknowledged={recallSeen} />
      break
    case "pos":
      screen = <PosScreen />
      break
    case "carga":
      screen = <IntakeScreen />
      break
    case "importar":
      screen = <ImportReviewScreen />
      break
    case "modulos":
      screen = <OwnerModulesScreen />
      break
    case "ds":
      screen = <DesignSystemScreen resolvedTheme={resolved} />
      break
    default:
      screen = <PlaceholderScreen item={item} role={role} onNavigate={navigate} />
  }

  return (
    <TooltipProvider delayDuration={250}>
      <AppShell
        role={role}
        onRole={changeRole}
        navKey={item.key}
        onNavigate={navigate}
        theme={theme}
        onTheme={setTheme}
        scope={scope}
        onScope={setScope}
        compact={item.screen === "pos"}
        onSimulateRecall={() => setRecallOpen(true)}
      >
        <React.Fragment key={`${role}-${item.key}`}>{screen}</React.Fragment>
      </AppShell>
      <RecallAlert
        open={recallOpen}
        onOpenChange={setRecallOpen}
        onAcknowledge={() => {
          setRecallOpen(false)
          setRecallSeen(true)
        }}
        onRemove={() => {
          setRecallOpen(false)
          setRecallSeen(true)
          toast("Seguridad alimentaria · Fisherton", { description: "En la app real se abre el detalle para registrar el retiro de las 24 u." })
        }}
      />
      <Toaster />
    </TooltipProvider>
  )
}
