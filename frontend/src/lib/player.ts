import type { PlayerIdentifier } from '../types/player';

export function parsePlayerQuery(query: string): PlayerIdentifier | null {
  const [name, tag, ...rest] = query.trim().split('#');
  if (!name?.trim() || !tag?.trim() || rest.length) return null;
  return { name: name.trim(), tag: tag.trim() };
}

export function parsePlayerFromUrl(pathname = window.location.pathname): PlayerIdentifier | null {
  const uuidMatch = pathname.match(/^\/player\/([^/]+)$/i);
  if (uuidMatch) {
    try {
      return { name: '', tag: '', puuid: decodeURIComponent(uuidMatch[1]) };
    } catch {
      return null;
    }
  }

  const match = pathname.match(/^\/id\/(.+)$/i);
  if (!match) return null;

  try {
    const decoded = decodeURIComponent(match[1]);
    const divider = decoded.lastIndexOf('_');
    if (divider <= 0 || divider === decoded.length - 1) return null;
    return { name: decoded.slice(0, divider), tag: decoded.slice(divider + 1) };
  } catch {
    return null;
  }
}

export function playerPath(player: PlayerIdentifier) {
  return `/id/${encodeURIComponent(`${player.name}_${player.tag}`.toLowerCase())}`;
}

export function playerUuidPath(puuid: string) {
  return `/player/${encodeURIComponent(puuid)}`;
}
