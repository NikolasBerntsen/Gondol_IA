import { LayoutTemplate } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Panel, PageHeader } from "../components/Panel"
import { EmptyState } from "../components/Controls"
import { NAV, type NavItem } from "../shell/nav"
import type { Role } from "../data"

/** Pantallas del menú que no forman parte de esta referencia de diseño. */
export function PlaceholderScreen({ item, role, onNavigate }: { item: NavItem; role: Role; onNavigate: (key: string) => void }) {
  const available = NAV[role].flatMap((g) => g.items).filter((i) => i.screen !== "placeholder")
  return (
    <div className="flex max-w-[1100px] flex-col gap-5 px-4 py-5 sm:px-6 lg:px-8 lg:py-7">
      <PageHeader title={item.label} description="Esta sección existe en GondolIA pero no está diseñada en esta referencia." />
      <Panel>
        <EmptyState
          icon={<LayoutTemplate />}
          title="Pantalla fuera de la referencia"
          description="Usá los patrones de Góndola UI: tabla densa con franjas de severidad para listados, formulario de una columna para altas y panel de resumen arriba de todo."
          action={
            <div className="flex flex-wrap gap-2">
              {available.map((a) => (
                <Button key={a.key} variant={a.screen === "ds" ? "outline" : "secondary"} size="sm" onClick={() => onNavigate(a.key)}>
                  <a.icon aria-hidden="true" />
                  {a.label}
                </Button>
              ))}
            </div>
          }
        />
      </Panel>
    </div>
  )
}
