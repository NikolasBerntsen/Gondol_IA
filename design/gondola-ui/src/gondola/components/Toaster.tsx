import { Toaster as Sonner } from "sonner"
import { AlertTriangle, CheckCircle2, Info, XCircle } from "lucide-react"

/** Toaster de Góndola UI: superficies y textos desde tokens, íconos con color semántico. */
export function Toaster() {
  return (
    <Sonner
      position="bottom-right"
      closeButton
      style={
        {
          "--normal-bg": "hsl(var(--card))",
          "--normal-text": "hsl(var(--foreground))",
          "--normal-border": "hsl(var(--border))",
          "--border-radius": "12px",
          fontFamily: "var(--font-sans)",
        } as React.CSSProperties
      }
      toastOptions={{
        classNames: {
          toast: "!shadow-pop !gap-2.5 !text-base",
          title: "!font-semibold !text-base",
          description: "!text-sm !text-muted-foreground",
          actionButton: "!bg-primary !text-primary-foreground !rounded-control !font-semibold",
          closeButton: "!bg-card !border-border !text-muted-foreground",
        },
      }}
      icons={{
        success: <CheckCircle2 className="h-[18px] w-[18px] text-ok" />,
        error: <XCircle className="h-[18px] w-[18px] text-crit" />,
        warning: <AlertTriangle className="h-[18px] w-[18px] text-warn" />,
        info: <Info className="h-[18px] w-[18px] text-info" />,
      }}
    />
  )
}
