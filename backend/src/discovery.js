const SECTIONS = Object.freeze({
  brazil: { label: "Em alta no Brasil", provider: "lastfm", method: "geo.gettoptracks", country: "Brazil" },
  world: { label: "Em alta no mundo", provider: "listenbrainz", method: "top-recordings" },
  releases: { label: "Lançamentos recentes", provider: "musicbrainz", method: "recent-releases" },
  sertanejo: { label: "Sertanejo", provider: "lastfm", method: "tag.gettoptracks", tag: "sertanejo" },
  funk: { label: "Funk", provider: "lastfm", method: "tag.gettoptracks", tag: "funk brasileiro" },
  pop: { label: "Pop", provider: "lastfm", method: "tag.gettoptracks", tag: "pop" },
  rock: { label: "Rock", provider: "lastfm", method: "tag.gettoptracks", tag: "rock" },
  electronic: { label: "Eletrônica", provider: "lastfm", method: "tag.gettoptracks", tag: "electronic" },
  pagode: { label: "Pagode", provider: "lastfm", method: "tag.gettoptracks", tag: "pagode" },
  rap: { label: "Rap e trap", provider: "lastfm", method: "tag.gettoptracks", tag: "trap" },
  discoveries: { label: "Descobertas", provider: "listenbrainz", method: "top-recordings" },
  artists: { label: "Artistas em destaque", provider: "lastfm", method: "chart.gettopartists" },
});

const USER_AGENT = "ASC-Music/0.1.0";

function updatedAt() {
  return new Date().toISOString();
}

function normalizeLastFmTrack(item) {
  return {
    title: item.name ?? "",
    artist: item.artist?.name ?? item.artist ?? "",
    sourceUrl: item.url ?? "",
    imageUrl: Array.isArray(item.image) ? item.image.at(-1)?.["#text"] || undefined : undefined,
  };
}

export async function getDiscovery(sectionId, env, fetchImpl = fetch, now = new Date()) {
  const section = SECTIONS[sectionId];
  if (!section) return { error: "Unknown discovery section.", status: 404 };
  const timestamp = updatedAt();
  const date = now.toISOString().slice(0, 10);

  if (section.provider === "lastfm" && env.LASTFM_API_KEY) {
    const url = new URL("https://ws.audioscrobbler.com/2.0/");
    url.searchParams.set("method", section.method);
    url.searchParams.set("api_key", env.LASTFM_API_KEY);
    url.searchParams.set("format", "json");
    url.searchParams.set("limit", "25");
    if (section.country) url.searchParams.set("country", section.country);
    if (section.tag) url.searchParams.set("tag", section.tag);
    const response = await fetchImpl(url, { headers: { "User-Agent": USER_AGENT } });
    if (!response.ok) throw new Error(`Last.fm returned HTTP ${response.status}.`);
    const body = await response.json();
    if (body.error) throw new Error(`Last.fm: ${body.message ?? "request failed"}`);
    const payload = section.method === "chart.gettopartists" ? body.artists?.artist : body.tracks?.track;
    if (!Array.isArray(payload)) throw new Error("Last.fm returned an unexpected response.");
    return {
      section: section.label,
      source: "Last.fm",
      updatedAt: timestamp,
      tracks: payload.map(normalizeLastFmTrack),
    };
  }

  if (section.provider === "musicbrainz") {
    let contactUrl = "";
    try {
      const contact = new URL(env.MUSICBRAINZ_CONTACT_URL || "");
      if (contact.protocol === "https:") contactUrl = contact.href;
    } catch {
      contactUrl = "";
    }
    if (!contactUrl) {
      return {
        section: section.label,
        source: "No configured data source",
        updatedAt: timestamp,
        note: "Set MUSICBRAINZ_CONTACT_URL to a real project or maintainer contact before querying MusicBrainz. Release dates are not Spotify catalog-addition history.",
        tracks: [],
      };
    }
    const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    const url = new URL("https://musicbrainz.org/ws/2/release/");
    url.searchParams.set("query", `date:[${weekAgo} TO ${date}] AND status:official`);
    url.searchParams.set("fmt", "json");
    url.searchParams.set("limit", "25");
    const response = await fetchImpl(url, { headers: { "User-Agent": `ASC-Music/0.1.0 (${contactUrl})` } });
    if (!response.ok) throw new Error(`MusicBrainz returned HTTP ${response.status}.`);
    const body = await response.json();
    if (!Array.isArray(body.releases)) throw new Error("MusicBrainz returned an unexpected response.");
    return {
      section: section.label,
      source: "MusicBrainz",
      updatedAt: timestamp,
      note: `Officially dated releases from ${weekAgo} to ${date}; this is release-date metadata, not Spotify catalog-addition history.`,
      tracks: body.releases.map((release) => ({
        title: release.title ?? "",
        artist: release["artist-credit"]?.map((credit) => credit.name ?? credit.artist?.name).filter(Boolean).join(", ") ?? "",
        album: release.title ?? "",
        sourceUrl: release.id ? `https://musicbrainz.org/release/${encodeURIComponent(release.id)}` : "",
      })),
    };
  }

  if (section.provider === "listenbrainz") {
    const url = new URL("https://api.listenbrainz.org/1/stats/sitewide/top-recordings");
    url.searchParams.set("range", "week");
    url.searchParams.set("count", "25");
    const response = await fetchImpl(url, { headers: { "User-Agent": USER_AGENT } });
    if (!response.ok) throw new Error(`ListenBrainz returned HTTP ${response.status}.`);
    const body = await response.json();
    const payload = body.payload?.recordings;
    if (!Array.isArray(payload)) throw new Error("ListenBrainz returned an unexpected response.");
    return {
      section: section.label,
      source: "ListenBrainz (weekly site-wide listening statistics)",
      updatedAt: timestamp,
      note: "A public listening-statistics fallback, not an official national chart.",
      tracks: payload.map((recording) => ({
        title: recording.track_name ?? "",
        artist: recording.artist_name ?? "",
        album: recording.release_name ?? "",
        sourceUrl: recording.recording_msid
          ? `https://listenbrainz.org/recording/${encodeURIComponent(recording.recording_msid)}`
          : "",
      })),
    };
  }

  return {
    section: section.label,
    source: "No configured data source",
    updatedAt: timestamp,
    note: section.provider === "lastfm"
      ? "Configure LASTFM_API_KEY on the Worker. No chart data is fabricated."
      : "The public provider could not be selected.",
    tracks: [],
  };
}

export async function getSimilarDiscovery(artistInput, env, fetchImpl = fetch) {
  const seeds = [...new Set(String(artistInput || "").split("|").map((artist) => artist.trim()).filter(Boolean))].slice(0, 3);
  const timestamp = updatedAt();
  if (!env.LASTFM_API_KEY) {
    return {
      section: "Descobertas semelhantes",
      source: "No configured data source",
      updatedAt: timestamp,
      note: "Configure LASTFM_API_KEY to find artists similar to your indexed collection. No recommendations are fabricated.",
      tracks: [],
    };
  }
  if (!seeds.length) {
    return {
      section: "Descobertas semelhantes",
      source: "Last.fm",
      updatedAt: timestamp,
      note: "Index a library with artist metadata first; no personal seed artists are currently available.",
      tracks: [],
    };
  }

  async function lastFm(method, artist, limit) {
    const url = new URL("https://ws.audioscrobbler.com/2.0/");
    url.searchParams.set("method", method);
    url.searchParams.set("api_key", env.LASTFM_API_KEY);
    url.searchParams.set("format", "json");
    url.searchParams.set("artist", artist);
    url.searchParams.set("limit", String(limit));
    const response = await fetchImpl(url, { headers: { "User-Agent": USER_AGENT } });
    if (!response.ok) throw new Error(`Last.fm returned HTTP ${response.status}.`);
    const body = await response.json();
    if (body.error) throw new Error(`Last.fm: ${body.message ?? "request failed"}`);
    return body;
  }

  const tracks = [];
  for (const seed of seeds) {
    const similarBody = await lastFm("artist.getsimilar", seed, 3);
    const similarArtists = similarBody.similarartists?.artist ?? [];
    for (const artist of similarArtists) {
      if (!artist.name) continue;
      const topBody = await lastFm("artist.gettoptracks", artist.name, 5);
      for (const track of topBody.toptracks?.track ?? []) {
        tracks.push({
          title: track.name ?? "",
          artist: artist.name,
          sourceUrl: track.url ?? artist.url ?? "",
          imageUrl: Array.isArray(track.image) ? track.image.at(-1)?.["#text"] || undefined : undefined,
        });
      }
    }
  }
  const unique = [...new Map(tracks.map((track) => [
    `${track.title.toLocaleLowerCase("en")}|${track.artist.toLocaleLowerCase("en")}`,
    track,
  ])).values()].slice(0, 25);
  return {
    section: "Descobertas semelhantes à sua biblioteca",
    source: "Last.fm artist.getsimilar + artist.gettoptracks",
    updatedAt: timestamp,
    note: `Recommendations seeded by ${seeds.join(", ")}. Provider data is curated listening metadata, not a local-library match until compared.`,
    tracks: unique,
  };
}

export { SECTIONS };
