import type { ShowcaseApplication } from './api';
import { MediaGallery } from './MediaGallery';

export function ApplicationCard({
  application,
}: {
  application: ShowcaseApplication;
}) {
  const release = application.latest_release;

  return (
    <article className="app-card">
      {application.media.length ? (
        <MediaGallery
          key={application.media
            .map((item) => `${item.id}:${item.url}`)
            .join('|')}
          name={application.name}
          media={application.media}
        />
      ) : (
        <div className="app-cover" aria-hidden="true">
          <span className="cover-orbit" />
          <span className="cover-initial">{application.name.charAt(0)}</span>
          <span className="cover-label">Made with care.</span>
        </div>
      )}
      <div className="app-details">
        <div className="app-heading">
          <h3>{application.name}</h3>
          {release && <span className="version">v{release.version}</span>}
        </div>
        <p className="app-description">{application.description}</p>
        <div className="app-actions">
          {application.github_url && (
            <a
              className="source-link"
              href={application.github_url}
              target="_blank"
              rel="noopener noreferrer"
            >
              View source <span aria-hidden="true">↗</span>
            </a>
          )}
          {release ? (
            <a className="download-link" href={release.download_url}>
              Download <span aria-hidden="true">↓</span>
            </a>
          ) : (
            <span className="release-empty">No downloadable release yet</span>
          )}
        </div>
      </div>
    </article>
  );
}
