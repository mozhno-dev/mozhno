import { useEffect, useRef, useState } from 'react';
import { getToken } from '@/api/modules/http';

/**
 * Loads an image from an authenticated API endpoint and returns a blob object URL.
 *
 * Protected binary endpoints cannot be used as plain {@code <img src>} values because
 * browser image requests do not carry the Authorization header; fetch the bytes with
 * the token and hand the resulting object URL to the image element instead.
 */
export function useAuthedImage(src: string | null | undefined): string | null {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const loadedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!src) {
      loadedRef.current = null;
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setObjectUrl((prevUrl) => {
        if (prevUrl) URL.revokeObjectURL(prevUrl);
        return null;
      });
      return;
    }

    if (src === loadedRef.current) {
      return;
    }

    let cancelled = false;
    const controller = new AbortController();

    (async () => {
      try {
        const res = await fetch(src, {
          headers: { Authorization: `Bearer ${getToken()}` },
          signal: controller.signal,
        });
        if (!res.ok || cancelled) return;
        const blob = await res.blob();
        if (cancelled) return;
        const url = URL.createObjectURL(blob);
        if (cancelled) return;
        loadedRef.current = src;
        setObjectUrl((prevUrl) => {
          if (prevUrl) URL.revokeObjectURL(prevUrl);
          return url;
        });
      } catch {
        // ignore abort/network errors — the fallback stays visible
      }
    })();

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [src]);

  return objectUrl;
}
