import { useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import type { ShowcaseMedia } from './api';

export function MediaGallery({
  name,
  media,
}: {
  name: string;
  media: ShowcaseMedia[];
}) {
  const id = useId();
  const track = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);
  const [failed, setFailed] = useState<Set<string>>(new Set());
  const ordered = [...media].sort(
    (a, b) => a.display_order - b.display_order || a.id.localeCompare(b.id),
  );

  function goTo(index: number) {
    const element = track.current;
    const slide = element?.children[index] as HTMLElement | undefined;
    if (!element || !slide) return;
    const reducedMotion = window.matchMedia(
      '(prefers-reduced-motion: reduce)',
    ).matches;
    element.scrollTo({
      left: slide.offsetLeft,
      behavior: reducedMotion ? 'instant' : 'smooth',
    });
    setActive(index);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const index =
      event.key === 'ArrowRight'
        ? Math.min(active + 1, ordered.length - 1)
        : event.key === 'ArrowLeft'
          ? Math.max(active - 1, 0)
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? ordered.length - 1
              : null;
    if (index === null) return;
    event.preventDefault();
    goTo(index);
  }

  function onScroll() {
    const element = track.current;
    if (!element) return;
    let closest = 0;
    let distance = Infinity;
    Array.from(element.children).forEach((child, index) => {
      const slide = child as HTMLElement;
      const nextDistance = Math.abs(slide.offsetLeft - element.scrollLeft);
      if (nextDistance < distance) {
        closest = index;
        distance = nextDistance;
      }
    });
    setActive(closest);
  }

  return (
    <section className="product-gallery" aria-label={`${name} screenshots`}>
      <div
        className="gallery-track"
        id={id}
        ref={track}
        tabIndex={0}
        aria-label="Product previews. Use Left and Right arrows, Home or End to navigate."
        onKeyDown={onKeyDown}
        onScroll={onScroll}
      >
        {ordered.map((item, index) => (
          <figure className="gallery-slide" key={item.id}>
            {failed.has(item.id) || !item.url ? (
              <div
                className="media-unavailable"
                role="img"
                aria-label={`${name} preview ${index + 1} unavailable`}
              >
                <span className="unavailable-initial" aria-hidden="true">
                  {name.charAt(0)}
                </span>
                <span>Preview unavailable</span>
              </div>
            ) : (
              <img
                src={item.url}
                alt={`${name} preview ${index + 1}`}
                loading="lazy"
                decoding="async"
                onError={() =>
                  setFailed((previous) => new Set(previous).add(item.id))
                }
              />
            )}
          </figure>
        ))}
      </div>
      <div className="gallery-toolbar">
        <span className="gallery-label">In the details</span>
        <div className="gallery-navigation">
          <span className="gallery-count" aria-live="polite" aria-atomic="true">
            {active + 1} / {ordered.length}
          </span>
          {ordered.length > 1 && (
            <>
              <button
                type="button"
                aria-label={`Previous ${name} preview`}
                aria-controls={id}
                disabled={active === 0}
                onClick={() => goTo(active - 1)}
              >
                ←
              </button>
              <button
                type="button"
                aria-label={`Next ${name} preview`}
                aria-controls={id}
                disabled={active === ordered.length - 1}
                onClick={() => goTo(active + 1)}
              >
                →
              </button>
            </>
          )}
        </div>
      </div>
    </section>
  );
}
