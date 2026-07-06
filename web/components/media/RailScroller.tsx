"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { ReactNode, useCallback, useEffect, useRef, useState } from "react";

export function RailScroller({
  children,
  className,
  ariaLabel
}: {
  children: ReactNode;
  className: string;
  ariaLabel: string;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [canPrev, setCanPrev] = useState(false);
  const [canNext, setCanNext] = useState(false);

  const update = useCallback(() => {
    const node = ref.current;
    if (!node) return;
    const max = node.scrollWidth - node.clientWidth;
    setCanPrev(node.scrollLeft > 6);
    setCanNext(node.scrollLeft < max - 6);
  }, []);

  const scrollByPage = (direction: -1 | 1) => {
    const node = ref.current;
    if (!node) return;
    node.scrollBy({
      left: direction * Math.max(220, node.clientWidth * 0.82),
      behavior: "smooth"
    });
  };

  useEffect(() => {
    const node = ref.current;
    if (!node) return undefined;
    update();
    const onScroll = () => update();
    node.addEventListener("scroll", onScroll, { passive: true });
    const resizeObserver = new ResizeObserver(update);
    resizeObserver.observe(node);
    const timer = window.setTimeout(update, 250);
    return () => {
      window.clearTimeout(timer);
      node.removeEventListener("scroll", onScroll);
      resizeObserver.disconnect();
    };
  }, [update, children]);

  return (
    <div className={`rail-scroll-shell ${canPrev ? "can-prev" : ""} ${canNext ? "can-next" : ""}`}>
      <button
        type="button"
        className="rail-arrow rail-arrow-left"
        onClick={() => scrollByPage(-1)}
        disabled={!canPrev}
        aria-label={`Scroll ${ariaLabel} left`}
      >
        <ChevronLeft size={24} />
      </button>
      <div ref={ref} className={className}>
        {children}
      </div>
      <button
        type="button"
        className="rail-arrow rail-arrow-right"
        onClick={() => scrollByPage(1)}
        disabled={!canNext}
        aria-label={`Scroll ${ariaLabel} right`}
      >
        <ChevronRight size={24} />
      </button>
    </div>
  );
}
