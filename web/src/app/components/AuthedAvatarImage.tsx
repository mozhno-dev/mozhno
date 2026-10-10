import { AvatarImage } from '@/app/components/ui/avatar';
import { useAuthedImage } from '@/app/hooks/queries';

/**
 * Avatar image loaded through an authenticated fetch (see useAuthedImage).
 * Protected endpoints cannot be used as plain <img src> values because browser
 * image requests do not carry the Authorization header.
 */
export function AuthedAvatarImage({ src, alt }: { src: string | null; alt: string }) {
  const url = useAuthedImage(src);
  return <AvatarImage src={url ?? undefined} alt={alt} />;
}
