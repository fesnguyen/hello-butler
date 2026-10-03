export interface ShowcaseMedia {
  id: string;
  url: string;
  display_order: number;
}

export interface ShowcaseRelease {
  version: string;
  download_url: string;
  published_at: string;
}

export interface ShowcaseApplication {
  id: string;
  name: string;
  description: string;
  github_url: string | null;
  media: ShowcaseMedia[];
  latest_release: ShowcaseRelease | null;
}

export function applicationsUrl(baseUrl: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/api/showcase/applications`;
}

export async function fetchApplications(
  baseUrl: string,
  signal?: AbortSignal,
): Promise<ShowcaseApplication[]> {
  const response = await fetch(applicationsUrl(baseUrl), { signal });
  if (!response.ok) throw new Error('The applications could not be loaded.');
  return (await response.json()) as ShowcaseApplication[];
}
