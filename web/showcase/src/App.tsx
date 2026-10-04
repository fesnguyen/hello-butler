import { useQuery } from '@tanstack/react-query';
import { ApplicationCard } from './ApplicationCard';
import { fetchApplications } from './api';

export default function App() {
  const applications = useQuery({
    queryKey: ['showcase-applications'],
    queryFn: ({ signal }) =>
      fetchApplications(import.meta.env.VITE_API_BASE_URL || '', signal),
    staleTime: 60_000,
    retry: 1,
  });

  return (
    <div className="page-shell">
      <header className="site-header">
        <a className="wordmark" href="#">
          EX’S LAB
          <span className="brand-dot" />
        </a>
        <span className="header-note">A small collection of useful things</span>
      </header>
      <main>
        <section className="intro" aria-labelledby="intro-title">
          <p className="eyebrow">Independent projects · Open possibilities</p>
          <h1 id="intro-title">
            Things I’ve <em>built.</em>
          </h1>
          <p className="intro-description">
            Applications, experiments, and demos.
            <br />
            Ideas turned into things you can actually use.
          </p>
        </section>
        <section className="applications" aria-labelledby="applications-title">
          <div className="section-heading">
            <h2 id="applications-title">The collection</h2>
            <span>Built to be explored</span>
          </div>
          {applications.isPending && (
            <p className="status" role="status">
              Loading the collection…
            </p>
          )}
          {applications.isError && (
            <div className="status" role="alert">
              <p>The collection is temporarily unavailable.</p>
              <button
                onClick={() => void applications.refetch()}
                disabled={applications.isFetching}
              >
                {applications.isFetching ? 'Loading…' : 'Try again'}
              </button>
            </div>
          )}
          {applications.isSuccess &&
            (applications.data.length ? (
              <div className="app-grid">
                {applications.data.map((application) => (
                  <ApplicationCard
                    key={application.id}
                    application={application}
                  />
                ))}
              </div>
            ) : (
              <p className="status">New projects will appear here soon.</p>
            ))}
        </section>
      </main>
      <footer className="site-footer">
        <span>EX’S LAB</span>
        <span>Small ideas. Real applications.</span>
      </footer>
    </div>
  );
}
