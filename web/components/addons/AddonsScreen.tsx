"use client";

import { Plus, Sparkles } from "lucide-react";
import { useState } from "react";
import { useApp } from "@/lib/store";

function resourceLabel(resources: unknown) {
  if (!Array.isArray(resources)) return "manifest";
  const names = resources.map((resource) => {
    if (typeof resource === "string") return resource;
    if (resource && typeof resource === "object") {
      const name = (resource as { name?: unknown }).name;
      return typeof name === "string" ? name : "";
    }
    return "";
  }).filter(Boolean);
  return names.join(", ") || "manifest";
}

export function AddonsScreen() {
  const { addons, installAddon, removeAddon, setToast } = useApp();
  const [url, setUrl] = useState("");
  const [installing, setInstalling] = useState(false);
  const install = async () => {
    if (!url.trim()) {
      setToast("Enter an addon manifest URL first.");
      return;
    }
    setInstalling(true);
    try {
      await installAddon(url.trim());
      setUrl("");
      setToast("Addon installed.");
    } catch (error) {
      setToast(error instanceof Error ? error.message : "Could not install addon.");
    } finally {
      setInstalling(false);
    }
  };
  return (
    <div className="screen">
      <section className="section-heading">
        <p className="eyebrow">Sources</p>
        <h2>Addons</h2>
      </section>
      <div className="inline-form wide">
        <input value={url} onChange={(event) => setUrl(event.target.value)} placeholder="https://addon.example.com/manifest.json" />
        <button type="button" className="primary" disabled={installing} onClick={() => void install()}><Plus size={18} /> {installing ? "Installing..." : "Install"}</button>
      </div>
      <div className="addon-grid">
        {addons.map((addon) => (
          <article className="addon-tile" key={addon.id}>
            <Sparkles size={24} />
            <h3>{addon.name}</h3>
            <p>{addon.description || addon.manifestUrl}</p>
            <div className="chips">
              <span>{addon.version}</span>
              <span>{resourceLabel(addon.resources)}</span>
            </div>
            <button type="button" className="secondary" onClick={() => removeAddon(addon)}>Remove</button>
          </article>
        ))}
      </div>
    </div>
  );
}
