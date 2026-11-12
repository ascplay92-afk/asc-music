import { spotifyPlaylistId } from "../../shared/playlist-import.js";

const AUTH_ENDPOINT = "https://accounts.spotify.com/authorize";
const TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
const API_ENDPOINT = "https://api.spotify.com/v1";
const SCOPES = ["playlist-read-private", "playlist-read-collaborative"];
const CLIENT_ID_KEY = "asc.spotify.clientId";
let accessToken = "";
let expiresAt = 0;

function randomVerifier() {
  const bytes = crypto.getRandomValues(new Uint8Array(64));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function challengeFor(verifier) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return btoa(String.fromCharCode(...new Uint8Array(hash))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function spotifyConfigured() {
  return Boolean(localStorage.getItem(CLIENT_ID_KEY)?.trim());
}

export async function connectSpotify() {
  const clientId = localStorage.getItem(CLIENT_ID_KEY)?.trim();
  if (!clientId) throw new Error("Add your Spotify client ID in Settings first.");
  const verifier = randomVerifier();
  const state = crypto.randomUUID();
  sessionStorage.setItem("asc.spotify.verifier", verifier);
  sessionStorage.setItem("asc.spotify.state", state);
  const url = new URL(AUTH_ENDPOINT);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("redirect_uri", window.location.origin);
  url.searchParams.set("scope", SCOPES.join(" "));
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("code_challenge", await challengeFor(verifier));
  window.location.assign(url);
}

export async function completeSpotifyAuth() {
  const url = new URL(window.location.href);
  const code = url.searchParams.get("code");
  const error = url.searchParams.get("error");
  const returnedState = url.searchParams.get("state");
  if (!code && !error) return false;
  const expectedState = sessionStorage.getItem("asc.spotify.state");
  const verifier = sessionStorage.getItem("asc.spotify.verifier");
  sessionStorage.removeItem("asc.spotify.state");
  sessionStorage.removeItem("asc.spotify.verifier");
  url.searchParams.delete("code");
  url.searchParams.delete("state");
  url.searchParams.delete("error");
  window.history.replaceState({}, "", url);
  if (error) throw new Error(`Spotify authorization failed: ${error}`);
  if (!code || !expectedState || returnedState !== expectedState || !verifier) {
    throw new Error("Spotify authorization state could not be verified.");
  }
  const response = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: localStorage.getItem(CLIENT_ID_KEY) || "",
      grant_type: "authorization_code",
      code,
      redirect_uri: window.location.origin,
      code_verifier: verifier,
    }),
  });
  if (!response.ok) throw new Error(`Spotify token exchange failed (HTTP ${response.status}).`);
  const token = await response.json();
  accessToken = token.access_token;
  expiresAt = Date.now() + Number(token.expires_in || 3600) * 1000;
  return true;
}

async function ensureToken() {
  if (!accessToken || expiresAt <= Date.now()) {
    throw new Error("Connect Spotify to import an authorized playlist. You can also use CSV or a song list.");
  }
}

async function spotifyGet(path) {
  await ensureToken();
  const response = await fetch(`${API_ENDPOINT}${path}`, {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (response.status === 401) {
    accessToken = "";
    throw new Error("Spotify authorization expired. Reconnect and retry.");
  }
  if (response.status === 403) {
    throw new Error("Spotify denied this resource. Check playlist visibility, account entitlement, and requested scopes.");
  }
  if (response.status === 429) {
    const retryAfter = Number(response.headers.get("retry-after"));
    throw new Error(Number.isFinite(retryAfter)
      ? `Spotify rate limit reached. Retry in ${Math.max(1, Math.ceil(retryAfter))} seconds.`
      : "Spotify rate limit reached. Retry later.");
  }
  if (!response.ok) throw new Error(`Spotify returned HTTP ${response.status}.`);
  return response.json();
}

function spotifyPath(next) {
  const url = new URL(next, API_ENDPOINT);
  if (url.origin !== API_ENDPOINT) throw new Error("Spotify returned an unexpected pagination URL.");
  return `${url.pathname.replace(/^\/v1(?=\/)/, "")}${url.search}`;
}

function normalizeSpotifyTrack(item) {
  return {
    title: item.name || "",
    artist: item.artists?.map((artist) => artist.name).join(", ") || "",
    artists: item.artists?.map((artist) => artist.name) || [],
    album: item.album?.name || "",
    durationSeconds: item.duration_ms ? item.duration_ms / 1000 : undefined,
    isrc: item.external_ids?.isrc || "",
    sourceUrl: item.external_urls?.spotify || "",
    coverArt: item.album?.images?.[0]?.url || "",
    explicit: item.explicit === true,
    authorizedSourceAvailable: Boolean(item.external_urls?.spotify),
  };
}

export async function importSpotifyPlaylist(input) {
  const playlistId = spotifyPlaylistId(input);
  if (!playlistId) throw new Error("Enter a Spotify playlist link or a 22-character playlist ID.");
  const encodedId = encodeURIComponent(playlistId);
  const playlist = await spotifyGet(`/playlists/${encodedId}?fields=name,description,external_urls,images`);
  const tracks = [];
  let unavailableItems = 0;
  let next = `/playlists/${encodedId}/items?limit=100`;
  while (next && tracks.length < 1000) {
    const page = await spotifyGet(spotifyPath(next));
    for (const entry of page.items || []) {
      const item = entry.item || entry.track;
      if (item?.type === "track" && item.name) tracks.push(normalizeSpotifyTrack(item));
      else unavailableItems++;
      if (tracks.length >= 1000) break;
    }
    next = page.next;
  }
  if (next && tracks.length >= 1000) {
    throw new Error("This playlist exceeds the 1,000-track import limit. Export the complete list to CSV and import that file instead.");
  }
  if (unavailableItems) {
    throw new Error(`Spotify withheld metadata for ${unavailableItems} playlist item(s). No partial playlist was imported; use an authorized CSV export to preserve its full contents.`);
  }
  return {
    name: playlist.name || "Imported Spotify playlist",
    description: playlist.description || "",
    source: "Spotify",
    sourceUrl: playlist.external_urls?.spotify || `https://open.spotify.com/playlist/${playlistId}`,
    coverArt: playlist.images?.[0]?.url || "",
    tracks,
  };
}

export async function listSpotifyPlaylists() {
  const playlists = [];
  let next = "/me/playlists?limit=50";
  while (next && playlists.length < 200) {
    const page = await spotifyGet(spotifyPath(next));
    playlists.push(...(page.items || []));
    next = page.next;
  }
  return playlists;
}

export function setSpotifyClientId(value) {
  if (value.trim()) localStorage.setItem(CLIENT_ID_KEY, value.trim());
  else localStorage.removeItem(CLIENT_ID_KEY);
}
