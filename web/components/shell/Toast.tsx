"use client";

import { useEffect } from "react";
import { useApp } from "@/lib/store";

export function Toast() {
  const { toast, setToast } = useApp();
  useEffect(() => {
    if (!toast) return undefined;
    const timer = window.setTimeout(() => setToast(null), 3200);
    return () => window.clearTimeout(timer);
  }, [setToast, toast]);

  if (!toast) return null;
  return (
    <button type="button" className="toast" onClick={() => setToast(null)}>
      {toast}
    </button>
  );
}
