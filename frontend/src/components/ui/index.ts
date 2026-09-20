// Kit base de Góndola UI. Importá siempre desde '@/components/ui'.
// Componentes firma del negocio (PriceTag, ExpiryChip, Ticket80mm…) → '@/components/gondola'.
// Escáner y cámara → '@/components/scanner'.

export { Alert, type AlertProps, type AlertTone } from './Alert';
export { AuthImage, type AuthImageProps } from './AuthImage';
export { Avatar, type AvatarProps } from './Avatar';
export {
  Badge,
  expiryBucketTone,
  reorderStatusTone,
  severityTone,
  stockStatusTone,
  type BadgeProps,
  type BadgeTone,
} from './Badge';
export {
  Button,
  ButtonLink,
  buttonClasses,
  buttonVariants,
  type ButtonLinkProps,
  type ButtonProps,
  type ButtonSize,
  type ButtonVariant,
} from './Button';
export {
  Card,
  CardFooter,
  CardHeader,
  Panel,
  PanelHeader,
  type CardHeaderProps,
  type CardPadding,
  type CardProps,
} from './Card';
export { Checkbox, type CheckboxProps } from './Checkbox';
export { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog';
export {
  Kbd,
  QtyStepper,
  Segmented,
  WizardSteps,
  type QtyStepperProps,
  type SegmentedOption,
  type SegmentedProps,
  type WizardStepsProps,
} from './Controls';

// Diálogo compositivo (Radix). Para el caso habitual alcanza con `Modal`.
export {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  DialogTrigger,
} from './Dialog';
export {
  DropdownItem,
  DropdownLabel,
  DropdownPanel,
  DropdownSeparator,
  useDropdown,
  type DropdownItemProps,
  type DropdownPanelProps,
} from './Dropdown';
export {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuPortal,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from './DropdownMenu';
export { EmptyState, type EmptyStateProps } from './EmptyState';
export { ErrorState, type ErrorStateProps } from './ErrorState';
export { Field, fieldDescribedBy, type FieldControlProps, type FieldProps } from './Field';
export {
  CONTROL_SIZE_CLASSES,
  Input,
  controlBaseClasses,
  controlInvalidClasses,
  type ControlSize,
  type InputProps,
} from './Input';
export { Label } from './Label';
export { Modal, type ModalProps, type ModalSize } from './Modal';
export { PageHeader, type PageHeaderProps } from './PageHeader';
export { Pagination, pageInfo, type PaginationProps } from './Pagination';
export { Popover, PopoverAnchor, PopoverContent, PopoverTrigger } from './Popover';
export { Progress } from './Progress';
export { SearchInput, type SearchInputProps } from './SearchInput';
export { SecureContextWarning, type SecureContextWarningProps } from './SecureContextWarning';
export { Select, type SelectOption, type SelectProps } from './Select';

// Select flotante de Radix (`SelectMenu` = `Select` de shadcn). El `<select>` nativo es `Select`.
export {
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectMenu,
  SelectScrollDownButton,
  SelectScrollUpButton,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from './SelectMenu';
export { Separator } from './Separator';
export {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetOverlay,
  SheetPortal,
  SheetTitle,
  SheetTrigger,
} from './Sheet';
export { Skeleton } from './Skeleton';
export { PageSpinner, Spinner, type PageSpinnerProps, type SpinnerProps } from './Spinner';
export { StatCard, type StatCardProps, type StatTone, type StatTrend } from './StatCard';
export { Switch, type SwitchProps } from './Switch';
export {
  Table,
  type RowSeverity,
  type TableAlign,
  type TableColumn,
  type TableEmptyProps,
  type TableMobileSlot,
  type TableProps,
} from './Table';

// Primitivas de tabla (shadcn) para tablas a medida: `TableRoot` = `Table` de shadcn.
export {
  TableBody,
  TableCaption,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRoot,
  TableRow,
} from './TableParts';
export { Tabs, type TabItem, type TabsProps } from './Tabs';
export { Textarea, type TextareaProps } from './Textarea';
export { Toggle, type ToggleProps } from './Toggle';
export { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from './Tooltip';
export { Truncate, useTruncationTitle, type TruncateLines, type TruncateProps } from './Truncate';
