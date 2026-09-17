// Módulos por tenant (SPEC §14). Los tipos y etiquetas viven en '@/api/types'.
export { default as ModuleDisabledPage, MODULE_DISABLED_MESSAGE } from './ModuleDisabledPage';
export { RequireModule, type RequireModuleProps } from './RequireModule';
export { useModules, type ModulesState } from './useModules';
