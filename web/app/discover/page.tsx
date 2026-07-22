"use client";

import React, { useState, useEffect } from "react";
import { Copy, Check, Download, HelpCircle, Code, ListVideo, Loader2, Plus, X, Globe, User, BookOpen, AlertCircle } from "lucide-react";
import { config } from "@/lib/config";

export const dynamic = "force-static";

interface CatalogPack {
  id?: string;
  name: string;
  author: string;
  version: string;
  description: string;
  url: string;
  catalogs: string[];
  status?: "pending" | "approved";
}

const STATIC_FALLBACK_PACKS: CatalogPack[] = [
  {
    id: "cinema-essentials",
    name: "Cinema Essentials",
    author: "ARVIO Team",
    version: "1.0.0",
    description: "All the trending movies, popular lists, and upcoming releases you need for a perfect movie night.",
    url: "/packs/cinema-essentials.json",
    catalogs: ["Trending in Movies", "Top 10 Movies Today", "Top Movies This Week", "Coming Soon"]
  },
  {
    id: "tv-binge",
    name: "TV Show Binge Pack",
    author: "ARVIO Team",
    version: "1.0.0",
    description: "Never miss an episode. Popular, trending, and latest airing series in one convenient bundle.",
    url: "/packs/tv-binge.json",
    catalogs: ["Trending in Shows", "Top 10 Shows Today", "Latest Airing"]
  },
  {
    id: "anime-kdrama",
    name: "Otaku & K-Drama Hub",
    author: "Community",
    version: "1.1.2",
    description: "The ultimate pack for anime lovers and K-Drama fans. Auto-updated lists of trending episodes and releases.",
    url: "/packs/anime-kdrama.json",
    catalogs: ["Trending in Anime", "New in K-Dramas"]
  },
  {
    id: "classics-franchises",
    name: "Action & Franchise Classics",
    author: "Cinephile",
    version: "1.0.5",
    description: "Full box-sets and classic franchise collections, including James Bond, Harry Potter, Lord of the Rings, and Jurassic Park.",
    url: "/packs/classics-franchises.json",
    catalogs: [
      "James Bond Collection",
      "Harry Potter Collection",
      "The Matrix Collection",
      "Lord of the Rings and Hobbit Collection",
      "Jurassic Park Collection"
    ]
  }
];

export default function DiscoverPage() {
  const [packs, setPacks] = useState<CatalogPack[]>(STATIC_FALLBACK_PACKS);
  const [loading, setLoading] = useState(true);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // Form States
  const [showSubmitModal, setShowSubmitModal] = useState(false);
  const [formName, setFormName] = useState("");
  const [formAuthor, setFormAuthor] = useState("");
  const [formUrl, setFormUrl] = useState("");
  const [formDesc, setFormDesc] = useState("");
  const [submitLoading, setSubmitLoading] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitSuccess, setSubmitSuccess] = useState(false);

  async function loadPacks() {
    try {
      const res = await fetch(`${config.netlifyBackendUrl}/catalog-packs-list`);
      if (res.ok) {
        const data = await res.json();
        const parsed = data.map((item: any) => ({
          ...item,
          catalogs: typeof item.catalogs === "string" ? JSON.parse(item.catalogs) : item.catalogs
        }));
        if (Array.isArray(parsed) && parsed.length > 0) {
          setPacks(parsed);
          setLoading(false);
          return;
        }
      }
    } catch (err) {
      console.warn("Netlify backend packs fetch failed, falling back to static packs.json:", err);
    }

    // Fallback: fetch from /packs.json
    try {
      const res = await fetch("/packs.json");
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data) && data.length > 0) {
          setPacks(data);
        }
      }
    } catch (err) {
      console.error("Local packs.json fetch failed, using default hardcoded list:", err);
    }
    setLoading(false);
  }

  useEffect(() => {
    void loadPacks();
  }, []);

  const getAbsoluteUrl = (url: string) => {
    if (!url) return "";
    if (url.startsWith("/")) {
      if (typeof window !== "undefined") {
        return `${window.location.origin}${url}`;
      }
      return `https://arvio.app${url}`;
    }
    return url;
  };

  const copyToClipboard = (id: string, text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 2000);
    });
  };

  const getDeepLink = (packUrl: string) => {
    return `arvio://install-pack?url=${encodeURIComponent(getAbsoluteUrl(packUrl))}`;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formName.trim() || !formUrl.trim() || !formDesc.trim()) {
      setSubmitError("Please fill out all required fields.");
      return;
    }

    if (!formUrl.startsWith("https://")) {
      setSubmitError("Manifest URL must start with https://");
      return;
    }

    setSubmitLoading(true);
    setSubmitError(null);

    try {
      const res = await fetch(`${config.netlifyBackendUrl}/catalog-packs-submit`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          name: formName.trim(),
          url: formUrl.trim(),
          author: formAuthor.trim() || "Anonymous",
          description: formDesc.trim()
        })
      });

      if (!res.ok) {
        const errorJson = await res.json().catch(() => null);
        throw new Error(errorJson?.message || `Failed to submit: ${res.statusText}`);
      }

      setSubmitSuccess(true);
      setFormName("");
      setFormAuthor("");
      setFormUrl("");
      setFormDesc("");
      setTimeout(() => {
        setSubmitSuccess(false);
        setShowSubmitModal(false);
      }, 3000);
    } catch (err: any) {
      setSubmitError(err.message || "An unexpected error occurred during submission.");
    } finally {
      setSubmitLoading(false);
    }
  };

  return (
    <div className="discover-container">
      <header className="discover-header">
        <div className="header-top">
          <div className="logo-section">
            <img src="/arvio-logo.svg" alt="ARVIO Logo" className="logo" />
            <span className="logo-text">ARVIO</span>
          </div>
          <button
            type="button"
            className="submit-trigger-button"
            onClick={() => {
              setSubmitError(null);
              setSubmitSuccess(false);
              setShowSubmitModal(true);
            }}
          >
            <Plus size={16} /> Submit A Pack
          </button>
        </div>

        <h1 className="hero-title">Catalog Pack Discovery</h1>
        <p className="hero-subtitle">
          Enhance your streaming layout. Browse community-curated catalog packs and install multiple rows of content with a single click.
        </p>
      </header>

      {loading ? (
        <div className="loading-container">
          <Loader2 size={32} className="animate-spin text-muted" />
          <span>Fetching latest catalog packs...</span>
        </div>
      ) : (
        <main className="discover-grid">
          {packs.map((pack) => (
            <article key={pack.id || pack.url} className="pack-card">
              <div className="pack-card-header">
                <h2 className="pack-title">{pack.name}</h2>
                <div className="pack-meta">
                  <span className="pack-author">by {pack.author || "Anonymous"}</span>
                  <span className="pack-version">v{pack.version || "1.0.0"}</span>
                </div>
              </div>
              <p className="pack-description">{pack.description}</p>

              <div className="catalogs-section">
                <h3 className="section-label">
                  <ListVideo size={14} className="icon-inline" /> Included Rows ({pack.catalogs?.length || 0})
                </h3>
                <ul className="catalogs-list">
                  {pack.catalogs?.map((catalog, idx) => (
                    <li key={idx} className="catalog-item-tag">{catalog}</li>
                  ))}
                </ul>
              </div>

              <div className="pack-actions">
                <a
                  href={getDeepLink(pack.url)}
                  className="install-button"
                  title="Install this pack on your Android TV or local app"
                >
                  <Download size={16} /> Install Pack
                </a>
                <button
                  type="button"
                  onClick={() => copyToClipboard(pack.id || pack.url, getAbsoluteUrl(pack.url))}
                  className="copy-button"
                  title="Copy manifest JSON URL to clipboard"
                >
                  {copiedId === (pack.id || pack.url) ? <Check size={16} className="text-green" /> : <Copy size={16} />}
                  {copiedId === (pack.id || pack.url) ? "Copied!" : "Copy URL"}
                </button>
              </div>
            </article>
          ))}
        </main>
      )}

      {/* Submission Modal Overlay */}
      {showSubmitModal && (
        <div className="modal-backdrop">
          <div className="modal-content">
            <button
              type="button"
              className="modal-close"
              onClick={() => setShowSubmitModal(false)}
            >
              <X size={20} />
            </button>

            {submitSuccess ? (
              <div className="submit-feedback success">
                <Check size={48} className="text-green feedback-icon animate-bounce" />
                <h2>Submission Successful!</h2>
                <p>
                  Thank you! Your catalog pack has been submitted for review. It will become visible on the Discovery page once approved by a moderator.
                </p>
              </div>
            ) : (
              <form onSubmit={handleSubmit} className="submission-form">
                <h2>Submit a Catalog Pack</h2>
                <p className="form-intro">
                  Packs are moderated to ensure safety, formatting correctness, and server reliability.
                </p>

                {submitError && (
                  <div className="form-error">
                    <AlertCircle size={16} />
                    <span>{submitError}</span>
                  </div>
                )}

                <div className="form-field">
                  <label htmlFor="pack-name"><BookOpen size={14} /> Pack Name *</label>
                  <input
                    type="text"
                    id="pack-name"
                    required
                    placeholder="e.g. Action Blockbusters Bundle"
                    value={formName}
                    onChange={(e) => setFormName(e.target.value)}
                  />
                </div>

                <div className="form-field">
                  <label htmlFor="pack-url"><Globe size={14} /> Manifest JSON URL *</label>
                  <input
                    type="text"
                    id="pack-url"
                    required
                    placeholder="e.g. https://raw.githubusercontent.com/.../pack.json"
                    value={formUrl}
                    onChange={(e) => setFormUrl(e.target.value)}
                  />
                  <span className="field-hint">Must be a public HTTPS URL that serves valid JSON.</span>
                </div>

                <div className="form-field-row">
                  <div className="form-field">
                    <label htmlFor="pack-author"><User size={14} /> Author / Creator</label>
                    <input
                      type="text"
                      id="pack-author"
                      placeholder="e.g. @cinephile_dev"
                      value={formAuthor}
                      onChange={(e) => setFormAuthor(e.target.value)}
                    />
                  </div>
                </div>

                <div className="form-field">
                  <label htmlFor="pack-desc">Description *</label>
                  <textarea
                    id="pack-desc"
                    required
                    rows={3}
                    placeholder="Describe the content and purpose of this pack..."
                    value={formDesc}
                    onChange={(e) => setFormDesc(e.target.value)}
                  />
                </div>

                <button
                  type="submit"
                  className="form-submit-button"
                  disabled={submitLoading}
                >
                  {submitLoading ? (
                    <>
                      <Loader2 size={16} className="animate-spin" /> Submitting...
                    </>
                  ) : (
                    "Submit for Review"
                  )}
                </button>
              </form>
            )}
          </div>
        </div>
      )}

      <section className="faq-section">
        <h2 className="faq-heading"><HelpCircle size={22} className="icon-inline" /> Frequently Asked Questions</h2>
        <div className="faq-grid">
          <div className="faq-item">
            <h3>How do I install a pack?</h3>
            <p>
              Clicking <strong>Install Pack</strong> will trigger a deep link (<code>arvio://install-pack</code>) to launch the ARVIO app on your device and open the import dialog. If you are browsing on another device, copy the URL and paste it under <strong>Settings &gt; Catalogs &gt; Import Catalog Pack</strong> in your app.
            </p>
          </div>
          <div className="faq-item">
            <h3>How do I host my own Catalog Pack?</h3>
            <p>
              Create a JSON file matching the manifest structure below, host it on a public server (like GitHub Gist, Netlify, or other public hosting), and share your raw link or deep link code!
            </p>
          </div>
        </div>
      </section>

      <section className="manifest-example-section">
        <h2 className="manifest-heading"><Code size={22} className="icon-inline" /> Manifest JSON Structure</h2>
        <pre className="code-block">
          <code>{`{
  "id": "my-custom-pack",
  "name": "My Custom Catalog Pack",
  "author": "CreatorName",
  "version": "1.0.0",
  "description": "A brief description of the pack contents.",
  "catalogs": [
    {
      "name": "Trending Sci-Fi Movies",
      "url": "https://example.com/scifi-movies.json"
    },
    {
      "name": "Upcoming Action Series",
      "url": "https://example.com/action-series.json"
    }
  ]
}`}</code>
        </pre>
      </section>

      <footer className="discover-footer">
        <p>ARVIO TV &copy; {new Date().getFullYear()}. Built with premium aesthetics.</p>
      </footer>

      <style jsx>{`
        .discover-container {
          max-width: 1200px;
          margin: 0 auto;
          padding: 60px 24px;
          color: #ededed;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
          background-color: #000000;
          min-height: 100vh;
        }

        .discover-header {
          text-align: left;
          margin-bottom: 60px;
        }

        .header-top {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 32px;
          flex-wrap: wrap;
          gap: 16px;
        }

        .logo-section {
          display: flex;
          align-items: center;
          gap: 12px;
        }

        .logo {
          width: 48px;
          height: 48px;
        }

        .logo-text {
          font-size: 28px;
          font-weight: 800;
          letter-spacing: 1px;
          color: #fff;
        }

        .submit-trigger-button {
          display: flex;
          align-items: center;
          gap: 8px;
          background: rgba(255, 255, 255, 0.08);
          color: #fff;
          border: 1px solid rgba(255, 255, 255, 0.12);
          padding: 10px 18px;
          border-radius: 20px;
          font-weight: 600;
          font-size: 14px;
          cursor: pointer;
          transition: background 160ms ease, border-color 160ms ease, transform 160ms ease;
        }

        .submit-trigger-button:hover {
          background: rgba(255, 255, 255, 0.15);
          border-color: rgba(255, 255, 255, 0.25);
        }

        .submit-trigger-button:active {
          transform: scale(0.97);
        }

        .hero-title {
          font-size: 42px;
          font-weight: 800;
          margin: 0 0 16px 0;
          background: linear-gradient(135deg, #ffffff 40%, #8c8c9e);
          -webkit-background-clip: text;
          -webkit-text-fill-color: transparent;
        }

        .hero-subtitle {
          font-size: 18px;
          color: rgba(237, 237, 237, 0.7);
          max-width: 700px;
          line-height: 1.6;
          margin: 0;
        }

        .loading-container {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          gap: 16px;
          padding: 80px 0;
          color: rgba(237, 237, 237, 0.6);
        }

        .animate-spin {
          animation: spin 1s linear infinite;
        }

        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }

        .discover-grid {
          display: grid;
          grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
          gap: 30px;
          margin-bottom: 80px;
        }

        .pack-card {
          background: rgba(255, 255, 255, 0.03);
          border: 1px solid rgba(255, 255, 255, 0.08);
          border-radius: 16px;
          padding: 28px;
          display: flex;
          flex-direction: column;
          transition: transform 220ms cubic-bezier(0.2, 0.8, 0.2, 1),
                      border-color 220ms ease,
                      box-shadow 220ms ease;
        }

        .pack-card:hover {
          transform: translateY(-6px);
          border-color: rgba(255, 255, 255, 0.25);
          box-shadow: 0 20px 40px rgba(0, 0, 0, 0.6);
        }

        .pack-card-header {
          margin-bottom: 16px;
        }

        .pack-title {
          font-size: 22px;
          font-weight: 700;
          color: #fff;
          margin: 0 0 8px 0;
        }

        .pack-meta {
          display: flex;
          align-items: center;
          gap: 12px;
          font-size: 13px;
        }

        .pack-author {
          color: rgba(237, 237, 237, 0.5);
        }

        .pack-version {
          background: rgba(255, 255, 255, 0.08);
          color: rgba(255, 255, 255, 0.8);
          padding: 2px 6px;
          border-radius: 6px;
          font-weight: 600;
        }

        .pack-description {
          font-size: 15px;
          color: rgba(237, 237, 237, 0.7);
          line-height: 1.5;
          margin: 0 0 24px 0;
          flex-grow: 1;
        }

        .catalogs-section {
          background: rgba(255, 255, 255, 0.015);
          border: 1px solid rgba(255, 255, 255, 0.04);
          border-radius: 10px;
          padding: 16px;
          margin-bottom: 28px;
        }

        .section-label {
          font-size: 13px;
          font-weight: 700;
          color: rgba(237, 237, 237, 0.4);
          margin: 0 0 12px 0;
          text-transform: uppercase;
          letter-spacing: 0.5px;
          display: flex;
          align-items: center;
          gap: 6px;
        }

        .catalogs-list {
          display: flex;
          flex-wrap: wrap;
          gap: 8px;
          padding: 0;
          margin: 0;
          list-style: none;
        }

        .catalog-item-tag {
          font-size: 13px;
          background: rgba(255, 255, 255, 0.06);
          color: #fff;
          padding: 4px 10px;
          border-radius: 8px;
          border: 1px solid rgba(255, 255, 255, 0.03);
        }

        .pack-actions {
          display: flex;
          gap: 12px;
        }

        .install-button {
          flex: 1;
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
          background: #ffffff;
          color: #000000;
          font-weight: 700;
          font-size: 14px;
          padding: 12px;
          border-radius: 10px;
          text-decoration: none;
          transition: background 160ms ease, transform 160ms ease;
        }

        .install-button:hover {
          background: rgba(255, 255, 255, 0.85);
        }

        .install-button:active {
          transform: scale(0.97);
        }

        .copy-button {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
          background: rgba(255, 255, 255, 0.06);
          color: #ffffff;
          border: 1px solid rgba(255, 255, 255, 0.08);
          font-weight: 600;
          font-size: 14px;
          padding: 12px;
          border-radius: 10px;
          cursor: pointer;
          transition: background 160ms ease, border-color 160ms ease;
        }

        .copy-button:hover {
          background: rgba(255, 255, 255, 0.12);
          border-color: rgba(255, 255, 255, 0.15);
        }

        /* Modal Styles */
        .modal-backdrop {
          position: fixed;
          top: 0;
          left: 0;
          width: 100vw;
          height: 100vh;
          background: rgba(0, 0, 0, 0.8);
          backdrop-filter: blur(12px);
          display: flex;
          align-items: center;
          justify-content: center;
          z-index: 1000;
          padding: 20px;
        }

        .modal-content {
          background: #0a0a0d;
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 20px;
          width: 100%;
          max-width: 580px;
          padding: 40px;
          position: relative;
          box-shadow: 0 30px 60px rgba(0, 0, 0, 0.8);
        }

        .modal-close {
          position: absolute;
          top: 24px;
          right: 24px;
          background: none;
          border: none;
          color: rgba(237, 237, 237, 0.5);
          cursor: pointer;
          transition: color 160ms ease;
        }

        .modal-close:hover {
          color: #fff;
        }

        .submission-form h2 {
          font-size: 26px;
          font-weight: 800;
          color: #fff;
          margin: 0 0 8px 0;
        }

        .form-intro {
          font-size: 14px;
          color: rgba(237, 237, 237, 0.5);
          margin-bottom: 28px;
          line-height: 1.5;
        }

        .form-error {
          background: rgba(231, 76, 60, 0.08);
          border: 1px solid rgba(231, 76, 60, 0.2);
          color: #e74c3c;
          padding: 12px 16px;
          border-radius: 8px;
          font-size: 14px;
          display: flex;
          align-items: center;
          gap: 10px;
          margin-bottom: 24px;
        }

        .form-field {
          display: flex;
          flex-direction: column;
          gap: 8px;
          margin-bottom: 20px;
        }

        .form-field-row {
          display: grid;
          grid-template-columns: 1fr;
          gap: 20px;
        }

        .form-field label {
          font-size: 13px;
          font-weight: 700;
          color: rgba(237, 237, 237, 0.8);
          display: flex;
          align-items: center;
          gap: 6px;
        }

        .form-field input,
        .form-field textarea {
          background: rgba(255, 255, 255, 0.03);
          border: 1px solid rgba(255, 255, 255, 0.08);
          border-radius: 10px;
          padding: 12px 16px;
          color: #fff;
          font-size: 15px;
          transition: border-color 160ms ease, background 160ms ease;
        }

        .form-field input:focus,
        .form-field textarea:focus {
          outline: none;
          border-color: rgba(255, 255, 255, 0.3);
          background: rgba(255, 255, 255, 0.05);
        }

        .field-hint {
          font-size: 12px;
          color: rgba(237, 237, 237, 0.4);
        }

        .form-submit-button {
          width: 100%;
          background: #fff;
          color: #000;
          border: none;
          font-weight: 700;
          font-size: 15px;
          padding: 14px;
          border-radius: 10px;
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
          transition: background 160ms ease, transform 160ms ease;
          margin-top: 10px;
        }

        .form-submit-button:hover {
          background: rgba(255, 255, 255, 0.85);
        }

        .form-submit-button:active {
          transform: scale(0.98);
        }

        .form-submit-button:disabled {
          background: rgba(255, 255, 255, 0.2);
          color: rgba(0, 0, 0, 0.5);
          cursor: not-allowed;
        }

        .submit-feedback {
          text-align: center;
          padding: 20px 0;
        }

        .feedback-icon {
          margin: 0 auto 24px auto;
        }

        .submit-feedback h2 {
          font-size: 24px;
          color: #fff;
          margin-bottom: 12px;
        }

        .submit-feedback p {
          color: rgba(237, 237, 237, 0.7);
          font-size: 15px;
          line-height: 1.6;
        }

        .text-green {
          color: #00d588;
        }

        .icon-inline {
          display: inline-block;
          vertical-align: middle;
        }

        .faq-section {
          border-top: 1px solid rgba(255, 255, 255, 0.08);
          padding-top: 60px;
          margin-bottom: 60px;
        }

        .faq-heading {
          font-size: 26px;
          font-weight: 800;
          margin: 0 0 30px 0;
          color: #fff;
          display: flex;
          align-items: center;
          gap: 10px;
        }

        .faq-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
          gap: 40px;
        }

        .faq-item h3 {
          font-size: 18px;
          font-weight: 700;
          color: #fff;
          margin: 0 0 12px 0;
        }

        .faq-item p {
          font-size: 15px;
          color: rgba(237, 237, 237, 0.75);
          line-height: 1.6;
          margin: 0;
        }

        .manifest-example-section {
          border-top: 1px solid rgba(255, 255, 255, 0.08);
          padding-top: 60px;
          margin-bottom: 80px;
        }

        .manifest-heading {
          font-size: 26px;
          font-weight: 800;
          margin: 0 0 20px 0;
          color: #fff;
          display: flex;
          align-items: center;
          gap: 10px;
        }

        .code-block {
          background: rgba(255, 255, 255, 0.02);
          border: 1px solid rgba(255, 255, 255, 0.05);
          border-radius: 12px;
          padding: 24px;
          font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
          font-size: 14px;
          line-height: 1.6;
          color: rgba(237, 237, 237, 0.85);
          overflow-x: auto;
        }

        .discover-footer {
          text-align: center;
          border-top: 1px solid rgba(255, 255, 255, 0.05);
          padding-top: 30px;
          font-size: 14px;
          color: rgba(237, 237, 237, 0.4);
        }
      `}</style>
    </div>
  );
}
