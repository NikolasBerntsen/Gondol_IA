import { useQuery } from '@tanstack/react-query';
import { ImageOff } from 'lucide-react';
import { useEffect, useState, type ImgHTMLAttributes, type ReactNode } from 'react';
import { fetchBlob } from '@/api/client';
import { cn } from '@/lib/cn';

export interface AuthImageProps extends Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'> {
  /** Ruta de la API que requiere token (p. ej. `/api/attachments/12`). */
  src: string | null | undefined;
  alt: string;
  /** Contenido si la imagen no se pudo cargar. */
  fallback?: ReactNode;
  /** Clases del contenedor mientras carga / en error. */
  placeholderClassName?: string;
}

/**
 * Imagen protegida: la descarga con el token (axios) como `Blob` y la muestra con un object URL.
 * Los bytes se cachean con React Query (`['auth-image', src]`); el object URL se libera al desmontar.
 */
export function AuthImage({ src, alt, fallback, className, placeholderClassName, ...imgProps }: AuthImageProps) {
  const query = useQuery({
    queryKey: ['auth-image', src],
    queryFn: ({ signal }) => fetchBlob(src as string, { signal, branch: null }),
    enabled: !!src,
    staleTime: Infinity,
    gcTime: 10 * 60_000,
    retry: 1,
  });

  const [objectUrl, setObjectUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!query.data) {
      setObjectUrl(null);
      return;
    }
    const url = URL.createObjectURL(query.data);
    setObjectUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [query.data]);

  if (query.isError || !src) {
    return (
      <>
        {fallback ?? (
          <div
            role="img"
            aria-label={`${alt} (no disponible)`}
            className={cn(
              'flex items-center justify-center gap-2 rounded-xl bg-slate-100 p-4 text-xs text-slate-500',
              placeholderClassName ?? className,
            )}
          >
            <ImageOff className="h-5 w-5" aria-hidden="true" />
            <span>No se pudo cargar la imagen</span>
          </div>
        )}
      </>
    );
  }

  if (!objectUrl) {
    return (
      <div
        role="img"
        aria-label={`${alt} (cargando)`}
        aria-busy="true"
        className={cn('min-h-[6rem] animate-pulse rounded-xl bg-slate-100', placeholderClassName ?? className)}
      />
    );
  }

  return <img src={objectUrl} alt={alt} className={className} {...imgProps} />;
}
