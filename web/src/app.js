import "./styles.css";
import { comparePlaylist, TRACK_STATUS } from "../../shared/music.js";
import {
  parsePlaylistCsv,
  parseTrackList,
  spotifyPlaylistId,
} from "../../shared/playlist-import.js";
import { applyLibraryDelta } from "../../shared/library-index.js";
import { readLibraryIndex, writeLibraryIndex } from "../../shared/library-store.js";
import { appendPlaybackHistory } from "../../shared/playback-history.js";
import { accountStorageKey, readAccountLocalState } from "./account-storage.js";
import {
  authConfigurationError,
  authConfigured,
  completePasswordRecovery,
  currentSession,
  recoverAccount,
  registerAccount,
  signIn,
  signOut,
  supabase,
} from "./auth.js";
import { enqueueSyncChange, resolveSyncConflict, stableTrackId, synchronizeAccount } from "./sync.js";
import { readAudioMetadata } from "./metadata.js";
import { DriveConnector, getDriveConfiguration } from "./drive.js";
import {
  completeSpotifyAuth,
  connectSpotify,
  importSpotifyPlaylist,
  listSpotifyPlaylists,
  setSpotifyClientId,
  spotifyConfigured,
} from "./spotify.js";

const $ = (selector) => document.querySelector(selector);
const content = $("#app-content");
const importDialog = $("#import-dialog");
const settingsDialog = $("#settings-dialog");
const audio = $("#audio-player");
const API_BASE = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
const DISCOVERY_SECTIONS = [
  ["brazil", "Em alta no Brasil"], ["world", "Em alta no mundo"], ["releases", "Lançamentos recentes"],
  ["sertanejo", "Sertanejo"], ["funk", "Funk"], ["pop", "Pop"], ["rock", "Rock"],
  ["electronic", "Eletrônica"], ["pagode", "Pagode"], ["rap", "Rap e trap"],
  ["discoveries", "Descobertas"], ["similar", "Semelhantes à sua biblioteca"], ["artists", "Artistas em destaque"],
];
const STATUS_LABELS = {
  AVAILABLE: "Disponível",
  MISSING: "Faltando",
  POSSIBLE_MATCH: "Confirmar",
  DUPLICATE: "Duplicada",
  UNAVAILABLE_SOURCE: "Sem fonte autorizada",
};

const state = {
  page: "home",
  library: [],
  playlists: [],
  favorites: new Set(),
  history: [],
  confirmedMatches: {},
  localAudio: new Map(),
  driveAudioUrl: "",
  replaceLocalOnNextSelection: false,
  current: null,
  queue: [],
  queueIndex: -1,
  repeat: false,
  shuffle: false,
  discoverySection: "brazil",
  discoveryCache: new Map(),
  discoveryTracks: [],
  discoveryRequest: 0,
  importTab: "url",
  importError: "",
  importTargetPlaylist: "",
  drive: new DriveConnector(getDriveConfiguration()),
  apiAvailable: false,
  session: null,
  syncStatus: "offline",
  syncConflict: null,
  syncAbortController: null,
  recoveryMode: false,
  spotifyPlaylists: [],
};

function escapeHtml(value = "") {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function countLabel(count, singular, plural) {
  return `${count} ${count === 1 ? singular : plural}`;
}

function safeWebUrl(value) {
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? url.href : "";
  } catch {
    return "";
  }
}

function safeImageUrl(value) {
  const dataImage = /^data:image\/(?:jpeg|png|gif|webp);base64,[a-z0-9+/=]+$/i;
  if (typeof value === "string" && value.length <= 1_400_000 && dataImage.test(value)) return value;
  return safeWebUrl(value);
}

function toast(message) {
  const element = $("#toast");
  element.textContent = message;
  element.classList.add("visible");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.classList.remove("visible"), 3900);
}

function formatTime(seconds) {
  if (!Number.isFinite(Number(seconds)) || Number(seconds) < 0) return "—";
  const value = Math.floor(Number(seconds));
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, "0")}`;
}

function makeTrackIdentity(track) {
  return `${String(track.title || "").toLocaleLowerCase("pt-BR")}|${String(track.artist || "").toLocaleLowerCase("pt-BR")}`;
}

function coverMarkup(track, extraClass = "track-art") {
  const image = safeImageUrl(track.coverArt || track.thumbnailUrl || "");
  const alt = escapeHtml(track.title || "Capa de álbum");
  return `<span class="${extraClass}">${image ? `<img src="${escapeHtml(image)}" alt="${alt}" loading="lazy">` : "♪"}</span>`;
}

function playlistById(id) {
  return state.playlists.find((playlist) => playlist.id === id);
}

function playlistTrackKey(playlist, index) {
  const track = playlist?.tracks[index];
  return `${playlist?.id}:${track?.id || index}`;
}

function compare(playlist) {
  const result = comparePlaylist(playlist, state.library);
  result.tracks = result.tracks.map((track, index) => {
    const key = playlistTrackKey(playlist, index);
    const fileId = state.confirmedMatches[key];
    if (!fileId) return track;
    const file = state.library.find((entry) => entry.id === fileId);
    if (!file) return track;
    return {
      ...track,
      match: { status: TRACK_STATUS.AVAILABLE, confidence: 100, candidates: [{ file, score: 100, confirmed: true }] },
    };
  });
  result.summary = summaryFor(result.tracks);
  return result;
}

function summaryFor(tracks) {
  const count = (status) => tracks.filter((track) => track.match.status === status).length;
  const available = count(TRACK_STATUS.AVAILABLE);
  const duplicates = count(TRACK_STATUS.DUPLICATE);
  return {
    total: tracks.length,
    available,
    missing: count(TRACK_STATUS.MISSING),
    possibleMatches: count(TRACK_STATUS.POSSIBLE_MATCH),
    duplicates,
    unavailableSource: count(TRACK_STATUS.UNAVAILABLE_SOURCE),
    completeness: tracks.length ? Math.round(((available + duplicates) / tracks.length) * 100) : 0,
  };
}

function allPlaylistStats() {
  const tracks = state.playlists.flatMap((playlist) => compare(playlist).tracks);
  return summaryFor(tracks);
}

function statusPill(status) {
  return `<span class="status-pill ${status.toLowerCase()}">${escapeHtml(STATUS_LABELS[status] || status)}</span>`;
}

function officialSearchLinks(track) {
  const query = encodeURIComponent(`${track.artist || ""} ${track.title || ""}`.trim());
  const source = safeWebUrl(track.sourceUrl);
  return [
    ...(source ? [{ label: "Abrir origem", href: source }] : []),
    { label: "Apple Music", href: `https://music.apple.com/search?term=${query}` },
    { label: "Bandcamp", href: `https://bandcamp.com/search?q=${query}` },
    { label: "MusicBrainz", href: `https://musicbrainz.org/search?query=${query}&type=recording&method=indexed` },
  ];
}

function trackRow(track, index, { playlistId, trackIndex, showStatus = false, actions = "" } = {}) {
  const status = track.match?.status;
  const playable = state.library.some((file) => file.id === track.id && (file.source !== "local" || state.localAudio.has(file.id)))
    || state.library.some((file) => track.match?.candidates?.some((candidate) => candidate.file.id === file.id)
      && (file.source !== "local" || state.localAudio.has(file.id)));
  const rowActions = actions
    || (track.match?.status === TRACK_STATUS.POSSIBLE_MATCH
      ? `<button class="row-action" data-action="review-match" data-playlist-id="${escapeHtml(playlistId)}" data-track-index="${trackIndex}" aria-label="Confirmar correspondência">◈</button>`
      : "");
  const trackId = playlistId ? `data-playlist-id="${escapeHtml(playlistId)}" data-track-index="${trackIndex}"` : `data-track-id="${escapeHtml(track.id)}"`;
  const favKey = track.id ? `file:${track.id}` : makeTrackIdentity(track);
  const isFavorite = state.favorites.has(favKey) || state.favorites.has(makeTrackIdentity(track));
  return `<div class="track-row">
    <span class="track-number">${String(index + 1).padStart(2, "0")}</span>
    ${coverMarkup(track)}
    <span class="track-main"><strong>${escapeHtml(track.title || "Sem título")}</strong><small>${escapeHtml(track.artist || "Artista não identificado")}</small></span>
    <span class="track-album">${escapeHtml(track.album || (track.metadataError ? "Sem metadados" : "—"))}</span>
    ${showStatus && status ? statusPill(status) : `<span class="track-duration">${formatTime(track.durationSeconds)}</span>`}
    <span class="track-actions">
      ${playable ? `<button class="row-action" data-action="play-track" ${trackId} aria-label="Reproduzir">▶</button>` : ""}
      <button class="row-action" data-action="toggle-favorite" data-favorite-key="${escapeHtml(favKey)}" data-title="${escapeHtml(track.title)}" data-artist="${escapeHtml(track.artist)}" data-album="${escapeHtml(track.album || "")}" aria-label="Favoritar">${isFavorite ? "♥" : "♡"}</button>
      ${rowActions}
      ${playlistId ? `<button class="row-action" data-action="move-track-up" data-playlist-id="${escapeHtml(playlistId)}" data-track-index="${trackIndex}" aria-label="Mover para cima">↑</button><button class="row-action" data-action="move-track-down" data-playlist-id="${escapeHtml(playlistId)}" data-track-index="${trackIndex}" aria-label="Mover para baixo">↓</button><button class="row-action" data-action="remove-track" data-playlist-id="${escapeHtml(playlistId)}" data-track-index="${trackIndex}" aria-label="Remover faixa">×</button>` : ""}
    </span>
  </div>`;
}

function updateSidebar() {
  const stats = allPlaylistStats();
  const missing = stats.missing + stats.possibleMatches + stats.unavailableSource;
  $("#missing-count").textContent = String(missing);
  $("#library-status").textContent = state.library.length
    ? countLabel(state.library.length, "arquivo indexado", "arquivos indexados")
    : "Biblioteca vazia";
  $("#library-dot").classList.toggle("online", state.library.length > 0);
  $("#sidebar-playlists").innerHTML = state.playlists.slice(0, 7).map((playlist) =>
    `<button class="sidebar-playlist" data-view="playlist:${escapeHtml(playlist.id)}">${escapeHtml(playlist.name)}</button>`,
  ).join("");
}

function pageHeading(eyebrow, title, description, actions = "") {
  return `<div class="page-heading"><div><div class="eyebrow">${escapeHtml(eyebrow)}</div><h1>${escapeHtml(title)}</h1><p class="page-subtitle">${escapeHtml(description)}</p></div>${actions}</div>`;
}

function renderHome() {
  const stats = allPlaylistStats();
  const recent = state.library.slice(-5).reverse();
  const availabilityMetrics = `<div class="completeness">
    <div class="ring${stats.total ? "" : " ring-empty"}" style="--progress:${stats.completeness}%" aria-label="${stats.total ? `${stats.completeness}% de disponibilidade` : "Disponibilidade ainda não calculada"}"><strong>${stats.total ? `${stats.completeness}%` : "—"}</strong></div>
    <div class="metric-list">
      <div class="metric-row"><span><i class="metric-dot"></i>Arquivos indexados</span><strong>${state.library.length}</strong></div>
      <div class="metric-row"><span><i class="metric-dot"></i>Músicas analisadas</span><strong>${stats.total}</strong></div>
      <div class="metric-row"><span><i class="metric-dot"></i>Encontradas</span><strong>${stats.available + stats.duplicates} / ${stats.total}</strong></div>
      <div class="metric-row"><span><i class="metric-dot warning"></i>Para confirmar</span><strong>${stats.possibleMatches}</strong></div>
      <div class="metric-row"><span><i class="metric-dot missing"></i>Faltantes</span><strong>${stats.missing + stats.unavailableSource}</strong></div>
      <div class="metric-row"><span><i class="metric-dot"></i>Duplicadas</span><strong>${stats.duplicates}</strong></div>
      <small class="metric-note">Duplicadas também contam como encontradas utilizáveis.</small>
    </div>
  </div>`;
  const hero = `<section class="hero">
    <div class="hero-content">
      <div class="eyebrow">MÚSICA, SEM PONTAS SOLTAS</div>
      <h1>Sua música,<br>por inteiro.</h1>
      <p>Encontre o que falta na sua coleção. Compare playlists com sua biblioteca e ouça seus arquivos em um só lugar.</p>
      <div class="hero-buttons">
        <button class="button button-primary button-small" data-action="${state.library.length ? "open-matches" : "connect-drive"}">${state.library.length ? "Ver ASC Match" : "Conectar minha biblioteca"} <span>↗</span></button>
        <button class="button button-outline button-small" data-action="open-import">＋ Importar playlist</button>
      </div>
    </div>
    <div class="hero-visual" aria-hidden="true"><span class="hero-spark hero-spark-one">✳</span><span class="hero-disc"></span><span class="hero-spark hero-spark-two">✦</span></div>
  </section>
  <div class="home-grid">
    <section class="panel">
      <div class="panel-header"><div><div class="eyebrow">SUA COLEÇÃO</div><h2>Panorama de disponibilidade</h2></div><button class="section-action" data-view="matches">Ver análise ↗</button></div>
      ${availabilityMetrics}
      ${stats.total ? "" : `<div class="empty-state"><span class="empty-icon">◈</span><strong>Seu primeiro match começa aqui</strong><p>Adicione músicas à sua biblioteca e importe uma playlist para ver o que está disponível ou faltando.</p><div class="empty-actions"><button class="button button-primary button-small" data-action="connect-drive">Conectar Drive</button><button class="button button-outline button-small" data-action="add-local-files">Adicionar arquivos</button></div></div>`}
    </section>
    <section class="panel">
      <div class="panel-header"><div><div class="eyebrow">PRÓXIMO PASSO</div><h2>Deixe tudo no mesmo lugar</h2></div><span class="panel-note">${state.playlists.length} playlists</span></div>
      <div class="update-card"><div><strong>Importe qualquer playlist</strong><p>Spotify autorizado, CSV ou lista de músicas.</p></div><button class="button button-outline button-small" data-action="open-import">Importar ↗</button></div>
      <div class="update-card" style="margin-top:9px"><div><strong>Reverifique sua biblioteca</strong><p>Atualize faixas novas ou removidas.</p></div><button class="button button-outline button-small" data-action="verify-library">Reverificar</button></div>
    </section>
  </div>
  <div class="section-heading"><h2>Atalhos</h2></div>
  <div class="quick-grid">
    <button class="quick-card" data-view="discover"><span class="quick-icon">✳</span><span><strong>Em alta no Brasil</strong><small>Explore fontes disponíveis</small></span></button>
    <button class="quick-card" data-action="create-smart" data-smart="releases"><span class="quick-icon">◷</span><span><strong>Lançamentos da semana</strong><small>Datas de lançamento verificadas</small></span></button>
    <button class="quick-card" data-view="library"><span class="quick-icon">▤</span><span><strong>Minha coleção completa</strong><small>${state.library.length} faixas indexadas</small></span></button>
    <button class="quick-card" data-view="missing"><span class="quick-icon">↗</span><span><strong>Músicas que faltam</strong><small>${stats.missing + stats.possibleMatches + stats.unavailableSource} pendências</small></span></button>
  </div>
  <div class="section-heading"><h2>Adicionadas recentemente</h2><button class="section-action" data-view="library">Ver biblioteca ↗</button></div>
  ${recent.length ? `<div class="track-list">${recent.map((track, index) => trackRow(track, index)).join("")}</div>` : `<div class="empty-state"><strong>Ainda não há arquivos indexados</strong><p>Conecte uma pasta do Drive ou selecione arquivos de áudio locais. Apenas metadados são guardados no índice.</p></div>`}`;
  return hero;
}

function renderLibrary() {
  const actions = `<div class="inline-actions"><button class="button button-outline button-small" data-action="verify-library">⟳ Verificar biblioteca novamente</button><button class="button button-primary button-small" data-action="add-local-files">＋ Adicionar arquivos</button></div>`;
  const files = state.library;
  const recent = [...files].sort((a, b) => String(b.checkedAt || b.modifiedTime || "").localeCompare(String(a.checkedAt || a.modifiedTime || "")));
  const warningCount = files.filter((file) => file.metadataError).length;
  return `${pageHeading("BIBLIOTECA", "Sua coleção", `${files.length} arquivos indexados. O ASC guarda metadados e referências — não cópias dos seus arquivos.`, actions)}
    ${warningCount ? `<div class="callout" style="margin-bottom:14px">${countLabel(warningCount, "arquivo sem metadados legíveis", "arquivos sem metadados legíveis")}. O nome do arquivo foi mantido como título; revise a correspondência antes de confirmar.</div>` : ""}
    ${files.length ? `<div class="panel"><div class="panel-header"><div><div class="eyebrow">FAIXAS INDEXADAS</div><h2>Biblioteca musical</h2></div><span class="panel-note">${files.filter((file) => file.source === "drive").length} no Drive · ${files.filter((file) => file.source === "local").length} local(is)</span></div><div class="track-list">${recent.map((track, index) => trackRow(track, index)).join("")}</div></div>`
      : `<div class="empty-state"><span class="empty-icon">▤</span><strong>Sua biblioteca está vazia</strong><p>Conecte uma pasta do Google Drive ou escolha arquivos de áudio. As faixas removidas da pasta deixam de aparecer após a reverificação.</p><div class="empty-actions"><button class="button button-primary button-small" data-action="connect-drive">Conectar Google Drive</button><button class="button button-outline button-small" data-action="add-local-files">Escolher arquivos</button></div></div>`}
    <input id="audio-files" type="file" accept="audio/*,.flac,.m4a,.mp3,.ogg,.opus,.wav,.wma" multiple hidden>`;
}

function renderPlaylistCards() {
  if (!state.playlists.length) {
    return `<div class="empty-state"><span class="empty-icon">≡</span><strong>Nenhuma playlist por aqui ainda</strong><p>Crie uma lista manual, importe um CSV ou conecte o Spotify para importar playlists acessíveis pela API oficial.</p><div class="empty-actions"><button class="button button-primary button-small" data-action="open-import">＋ Criar ou importar</button></div></div>`;
  }
  return `<div class="playlist-card-grid">${state.playlists.map((playlist) => {
    const stats = compare(playlist).summary;
    return `<button class="playlist-card" data-view="playlist:${escapeHtml(playlist.id)}"><span class="playlist-cover">${playlist.source === "Spotify" ? "♫" : "≋"}</span><span><strong>${escapeHtml(playlist.name)}</strong><small>${stats.total} faixas · ${stats.completeness}% disponíveis</small></span></button>`;
  }).join("")}</div>`;
}

function renderPlaylists() {
  return `${pageHeading("SUAS PLAYLISTS", "Playlists", "Playlists pessoais, importadas ou montadas a partir de músicas que você descobriu.", `<div class="inline-actions"><button class="button button-outline button-small" data-action="open-import">Importar playlist</button><button class="button button-primary button-small" data-action="new-playlist">＋ Nova playlist</button></div>`)}
    <div class="section-heading"><h2>Suas listas</h2></div>${renderPlaylistCards()}
    <div class="section-heading"><h2>Playlists inteligentes</h2></div>
    <div class="quick-grid">
      <button class="quick-card" data-action="create-smart" data-smart="collection"><span class="quick-icon">▤</span><span><strong>Minha coleção completa</strong><small>Faixas indexadas no momento</small></span></button>
      <button class="quick-card" data-view="missing"><span class="quick-icon">↗</span><span><strong>Músicas que faltam</strong><small>Pendências de todas as listas</small></span></button>
      <button class="quick-card" data-action="create-smart" data-smart="discoveries"><span class="quick-icon">✳</span><span><strong>Descobertas semelhantes</strong><small>Curadoria pública, com fonte</small></span></button>
      <button class="quick-card" data-action="create-smart" data-smart="brazil"><span class="quick-icon">◉</span><span><strong>Em alta no Brasil</strong><small>Importe quando a fonte estiver disponível</small></span></button>
    </div>`;
}

function renderPlaylistDetail(id) {
  const playlist = playlistById(id);
  if (!playlist) return `${pageHeading("PLAYLISTS", "Lista não encontrada", "Esta playlist pode ter sido removida.")}<button class="button button-outline button-small" data-view="playlists">Voltar às playlists</button>`;
  const result = compare(playlist);
  const stats = result.summary;
  const tracks = result.tracks;
  return `<div class="playlist-header"><div class="playlist-cover-large">${playlist.source === "Spotify" ? "♫" : "≋"}</div><div class="playlist-details">
    <div class="eyebrow">${escapeHtml(playlist.source || "PLAYLIST")} · ${stats.total} FAIXAS</div><h1>${escapeHtml(playlist.name)}</h1>
    <p>${escapeHtml(playlist.description || "Lista completa preservada; as faixas ausentes não são ocultadas.")}</p>
    <div class="playlist-summary"><span class="summary-item"><strong>${stats.available}</strong> disponíveis</span><span class="summary-item warn"><strong>${stats.possibleMatches}</strong> confirmar</span><span class="summary-item bad"><strong>${stats.missing + stats.unavailableSource}</strong> faltando</span><span class="summary-item"><strong>${stats.duplicates}</strong> duplicadas</span></div>
    <div class="inline-actions" style="margin-top:14px"><button class="button button-primary button-small" data-action="play-playlist" data-playlist-id="${escapeHtml(playlist.id)}">▶ Tocar disponíveis</button><button class="button button-outline button-small" data-action="verify-library">⟳ Verificar novamente</button><button class="button button-outline button-small" data-action="rename-playlist" data-playlist-id="${escapeHtml(playlist.id)}">Renomear</button><button class="button button-outline button-small" data-action="delete-playlist" data-playlist-id="${escapeHtml(playlist.id)}">Excluir</button></div>
  </div></div>
  <div class="match-progress"><span style="width:${stats.completeness}%"></span></div>
  <div class="source-stamp"><span>Correspondências automáticas exigem título e artista confiáveis; versões diferentes nunca são confirmadas automaticamente.</span><span>${stats.completeness}% de disponibilidade utilizável</span></div>
  <div class="section-heading"><h2>Todas as músicas <span class="panel-note">(${stats.total})</span></h2><button class="section-action" data-action="add-playlist-tracks" data-playlist-id="${escapeHtml(playlist.id)}">＋ Adicionar faixas</button></div>
  ${tracks.length ? `<div class="panel"><div class="track-list">${tracks.map((track, index) => `<div class="playlist-track-wrap">${trackRow(track, index, { playlistId: playlist.id, trackIndex: index, showStatus: true })}${track.match.candidates.length > 1 ? `<div class="panel-note" style="padding:0 12px 8px">${track.match.candidates.length} arquivos com correspondência semelhante</div>` : ""}</div>`).join("")}</div></div>` : `<div class="empty-state"><strong>Esta playlist ainda não tem músicas</strong><p>Importe faixas por CSV, texto ou uma URL do Spotify autorizada.</p></div>`}`;
}

function renderMatches() {
  const stats = allPlaylistStats();
  const actions = `<div class="inline-actions"><button class="button button-outline button-small" data-action="verify-library">⟳ Verificar biblioteca novamente</button><button class="button button-primary button-small" data-action="open-import">＋ Comparar playlist</button></div>`;
  if (!state.playlists.length) {
    return `${pageHeading("ASC MUSIC MATCH", "Correspondência inteligente", "Compare cada faixa com seus arquivos; títulos parecidos não bastam para confirmar uma gravação.", actions)}${renderPlaylistCards()}`;
  }
  return `${pageHeading("ASC MUSIC MATCH", "Completude da coleção", "As versões ao vivo, remix, acústica, instrumental, remasterizada e clean/explicit são avaliadas separadamente.", actions)}
    <div class="panel"><div class="panel-header"><div><div class="eyebrow">RESUMO DE TODAS AS PLAYLISTS</div><h2>${stats.total} faixas analisadas</h2></div><div class="ring" style="--progress:${stats.completeness}%"><strong>${stats.completeness}%</strong></div></div>
      <div class="playlist-summary"><span class="summary-item"><strong>${stats.available}</strong> disponíveis</span><span class="summary-item"><strong>${stats.duplicates}</strong> com duplicatas</span><span class="summary-item warn"><strong>${stats.possibleMatches}</strong> precisam de confirmação</span><span class="summary-item bad"><strong>${stats.missing}</strong> faltando</span><span class="summary-item bad"><strong>${stats.unavailableSource}</strong> sem fonte autorizada</span></div>
    </div><div class="section-heading"><h2>Por playlist</h2></div>${renderPlaylistCards()}`;
}

function renderMissing() {
  const entries = [];
  for (const playlist of state.playlists) {
    const result = compare(playlist);
    result.tracks.forEach((track, index) => {
      if ([TRACK_STATUS.AVAILABLE, TRACK_STATUS.DUPLICATE].includes(track.match.status)) return;
      entries.push({ playlist, track, index });
    });
  }
  const rows = entries.map(({ playlist, track, index }, rowIndex) => {
    const links = officialSearchLinks(track);
    return `<div class="panel" style="margin-bottom:10px"><div class="panel-header" style="align-items:center;margin-bottom:10px">${coverMarkup(track, "playlist-cover")}<div style="flex:1;min-width:0"><strong style="display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px">${escapeHtml(track.title)}</strong><small class="panel-note">${escapeHtml(track.artist || "Artista não identificado")} · ${escapeHtml(track.album || "Álbum não informado")}</small></div>${statusPill(track.match.status)}</div>
      <div class="panel-note">Playlist de origem: <button class="section-action" data-view="playlist:${escapeHtml(playlist.id)}">${escapeHtml(playlist.name)}</button></div>
      <div class="inline-actions" style="margin-top:12px">${links.map((link) => `<a class="button button-outline button-small" href="${escapeHtml(link.href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(link.label)} ↗</a>`).join("")}
        <button class="button button-outline button-small" data-action="mark-acquired" data-playlist-id="${escapeHtml(playlist.id)}" data-track-index="${index}">Adquirida? verificar biblioteca</button>
        <button class="button button-outline button-small" data-action="toggle-source-availability" data-playlist-id="${escapeHtml(playlist.id)}" data-track-index="${index}">${track.authorizedSourceAvailable === false ? "Marcar fonte encontrada" : "Sem fonte autorizada"}</button>
        ${track.match.status === TRACK_STATUS.POSSIBLE_MATCH ? `<button class="button button-outline button-small" data-action="review-match" data-playlist-id="${escapeHtml(playlist.id)}" data-track-index="${index}">Revisar correspondência</button>` : ""}
      </div></div>`;
  }).join("");
  return `${pageHeading("CENTRAL DE PENDÊNCIAS", "Músicas faltantes", `${entries.length} faixa(s) aguardam ação. As opções abrem páginas oficiais de busca; nenhuma música é baixada automaticamente.`, `<button class="button button-outline button-small" data-action="verify-library">⟳ Verificar biblioteca</button>`)}
    ${rows || `<div class="empty-state"><span class="empty-icon">✓</span><strong>Nenhuma música pendente</strong><p>Todas as faixas das suas playlists estão disponíveis ou já correspondem a arquivos utilizáveis.</p></div>`}`;
}

function renderFavorites() {
  const tracks = [...state.library, ...state.playlists.flatMap((playlist) => playlist.tracks)];
  const seen = new Set();
  const favorites = tracks.filter((track) => {
    const identity = makeTrackIdentity(track);
    if (!state.favorites.has(`file:${track.id}`) && !state.favorites.has(identity)) return false;
    if (seen.has(identity)) return false;
    seen.add(identity);
    return true;
  });
  return `${pageHeading("SUA COLEÇÃO", "Favoritas", "Faixas marcadas para encontrar de novo rapidamente.", `<button class="button button-outline button-small" data-view="library">Ver biblioteca</button>`)}
    ${favorites.length ? `<div class="panel"><div class="track-list">${favorites.map((track, index) => trackRow(track, index)).join("")}</div></div>` : `<div class="empty-state"><span class="empty-icon">♡</span><strong>Suas favoritas aparecem aqui</strong><p>Use o coração ao lado de uma música para salvá-la nesta lista.</p><button class="button button-outline button-small" data-view="library">Explorar biblioteca</button></div>`}`;
}

function renderHistory() {
  const rows = state.history.map((entry, index) => {
    const time = Number.isSafeInteger(entry.playedAt) ? entry.playedAt : Date.parse(entry.playedAt);
    const when = Number.isFinite(time) ? new Date(time).toLocaleString("pt-BR") : "Horário não disponível";
    return `<div><div class="panel-note">${escapeHtml(when)}</div>${trackRow(entry, index)}</div>`;
  }).join("");
  return `${pageHeading("REPRODUZIDAS RECENTEMENTE", "Histórico", "Reproduções recentes neste navegador.", `<button class="button button-outline button-small" data-action="clear-history">Limpar histórico</button>`)}
    ${rows ? `<div class="panel"><div class="track-list">${rows}</div></div>` : `<div class="empty-state"><span class="empty-icon">◷</span><strong>Seu histórico está vazio</strong><p>As faixas entram aqui quando começam a tocar.</p></div>`}`;
}

function renderDiscover() {
  const tabs = DISCOVERY_SECTIONS.map(([id, label]) => `<button class="discovery-tab ${state.discoverySection === id ? "active" : ""}" data-action="discovery-section" data-section="${id}">${escapeHtml(label)}</button>`).join("");
  const cache = state.discoveryCache.get(state.discoverySection);
  const loading = state.loadingDiscovery;
  const error = state.discoveryError;
  const results = cache?.tracks || [];
  state.discoveryTracks = results;
  return `${pageHeading("DESCOBERTA", "Descubra o que vem por aí", "Tendências e lançamentos com origem e data explícitas. Nenhum dado de catálogo é inventado.", `<button class="button button-outline button-small" data-action="connect-spotify">Conectar Spotify</button>`)}
    <div class="discovery-tabs" role="tablist">${tabs}</div>
    ${cache ? `<div class="source-stamp"><span>Fonte: ${escapeHtml(cache.source || "Desconhecida")}</span><span>Atualizado: ${escapeHtml(new Date(cache.updatedAt).toLocaleString("pt-BR"))}</span></div>${cache.note ? `<div class="source-note">${escapeHtml(cache.note)}</div>` : ""}` : ""}
    ${error ? `<div class="callout error-callout" style="margin:15px 0">${escapeHtml(error)} Configure o backend/fontes nas instruções ou tente outra categoria. CSV e texto continuam disponíveis.</div>` : ""}
    ${loading ? `<div class="discovery-grid">${Array.from({ length: 10 }, () => `<div class="skeleton"></div>`).join("")}</div>`
      : results.length ? `<div class="discovery-grid">${results.map((track, index) => {
        const artwork = safeImageUrl(track.imageUrl || "");
        return `<article class="discovery-card"><div class="discovery-cover">${artwork ? `<img src="${escapeHtml(artwork)}" alt="" loading="lazy">` : escapeHtml((track.title || "♪").slice(0, 1))}</div><strong title="${escapeHtml(track.title)}">${escapeHtml(track.title)}</strong><small>${escapeHtml(track.artist || "Artista não informado")}</small><button class="row-action" data-action="add-discovery-track" data-track-index="${index}" aria-label="Adicionar à playlist">＋ Adicionar</button></article>`;
      }).join("")}</div><div class="section-heading"><button class="button button-primary button-small" data-action="save-discovery-playlist">Salvar esta seleção como playlist</button></div>`
      : !error ? `<div class="empty-state"><span class="empty-icon">✳</span><strong>Esta fonte ainda não retornou faixas</strong><p>Configure Last.fm no backend para rankings e tags. Lançamentos e tendências públicas usam provedores alternativos com indicação da origem.</p></div>` : ""}
    ${!cache && !loading && !error ? `<div class="empty-state"><span class="empty-icon">◷</span><strong>Buscando na fonte oficial</strong><p>Os resultados só aparecem quando o provedor retorna dados verificáveis.</p></div>` : ""}`;
}

function render() {
  updateSidebar();
  document.querySelectorAll("[data-view]").forEach((button) => {
    button.classList.toggle("active", button.dataset.view === state.page || state.page.startsWith(`${button.dataset.view}:`));
  });
  const [view, id] = state.page.split(":");
  const title = {
    home: "Início", discover: "Descobrir", library: "Biblioteca", playlists: "Playlists",
    matches: "ASC Match", missing: "Músicas faltantes", favorites: "Favoritas", history: "Histórico", search: "Buscar",
  }[view] || (view === "playlist" ? playlistById(id)?.name || "Playlist" : "Início");
  $("#page-crumb").textContent = title;
  if (view === "home") content.innerHTML = renderHome();
  else if (view === "discover") content.innerHTML = renderDiscover();
  else if (view === "library") content.innerHTML = renderLibrary();
  else if (view === "playlists") content.innerHTML = renderPlaylists();
  else if (view === "playlist") content.innerHTML = renderPlaylistDetail(id);
  else if (view === "matches") content.innerHTML = renderMatches();
  else if (view === "missing") content.innerHTML = renderMissing();
  else if (view === "favorites") content.innerHTML = renderFavorites();
  else if (view === "history") content.innerHTML = renderHistory();
  else if (view === "search") content.innerHTML = renderSearch();
  if (view === "discover" && !state.discoveryCache.has(state.discoverySection) && !state.loadingDiscovery) {
    void loadDiscovery(state.discoverySection);
  }
  renderPlayer();
}

function renderSearch() {
  return `${pageHeading("BUSCAR", "Encontre uma música", "Busque na biblioteca indexada ou nas playlists importadas.")}
    <label class="search-bar"><span>⌕</span><input id="global-search" placeholder="Título, artista ou álbum…" autocomplete="off"></label>
    <div id="search-results"></div>`;
}

function renderPlayer() {
  if (!state.current) {
    $("#player-bar").hidden = true;
    return;
  }
  $("#player-bar").hidden = false;
  $("#player-title").textContent = state.current.title || "Sem título";
  $("#player-artist").textContent = state.current.artist || "Artista não identificado";
  const cover = safeImageUrl(state.current.coverArt || state.current.thumbnailUrl || "");
  $("#player-cover").src = cover || "data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=";
  $("#player-cover").alt = cover ? `Capa de ${state.current.title || "álbum"}` : "";
  $("#player-status").textContent = audio.error ? "Falha na reprodução" : (audio.paused ? "ASC Music" : "Reproduzindo");
  $("#play-button").textContent = audio.paused ? "▶" : "Ⅱ";
  $("#current-time").textContent = formatTime(audio.currentTime);
  $("#duration-time").textContent = formatTime(audio.duration);
  $("#seek-bar").value = audio.duration ? String(Math.round((audio.currentTime / audio.duration) * 1000)) : "0";
  document.querySelector('[data-action="shuffle"]')?.classList.toggle("enabled", state.shuffle);
  document.querySelector('[data-action="repeat"]')?.classList.toggle("enabled", state.repeat);
}

function setPage(page) {
  state.page = page;
  render();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function storePlaylists() {
  localStorage.setItem(accountStorageKey("playlists", state.session?.user?.id), JSON.stringify(state.playlists));
}

function persistPreferences() {
  const ownerId = state.session?.user?.id;
  localStorage.setItem(accountStorageKey("favorites", ownerId), JSON.stringify([...state.favorites]));
  localStorage.setItem(accountStorageKey("confirmed", ownerId), JSON.stringify(state.confirmedMatches));
  localStorage.setItem(accountStorageKey("playback", ownerId), JSON.stringify({
    shuffle: state.shuffle,
    repeat: state.repeat,
    volume: audio.volume,
    queueIds: state.queue.map((track) => track.id).filter(Boolean),
    queueIndex: state.queueIndex,
  }));
}

function queuePlaybackSetting(key, value) {
  const ownerId = state.session?.user?.id;
  if (!ownerId) return;
  void enqueueSyncChange(ownerId, "setting", key, { value })
    .then(() => synchronizeAccountNow())
    .catch((error) => {
      state.syncStatus = "pending";
      toast(`Preferência salva localmente; sincronização pendente: ${error.message}`);
    });
}

function restoreAccountLocalState(ownerId = state.session?.user?.id) {
  const accountState = readAccountLocalState(localStorage, ownerId);
  state.playlists = accountState.playlists;
  state.favorites = accountState.favorites;
  state.history = accountState.history;
  try {
    state.confirmedMatches = JSON.parse(localStorage.getItem(accountStorageKey("confirmed", ownerId)) || "{}");
  } catch {
    state.confirmedMatches = {};
  }
  try {
    const playback = JSON.parse(localStorage.getItem(accountStorageKey("playback", ownerId)) || "{}");
    state.shuffle = playback.shuffle === true;
    state.repeat = playback.repeat === true;
    audio.volume = Number.isFinite(Number(playback.volume))
      ? Math.min(1, Math.max(0, Number(playback.volume)))
      : 0.8;
    const queueIds = Array.isArray(playback.queueIds) ? playback.queueIds : [];
    const legacyQueue = Array.isArray(playback.queue) ? playback.queue : [];
    state.queue = queueIds.length
      ? queueIds.map((id) => state.library.find((track) => track.id === id)).filter(Boolean)
      : legacyQueue.map((track) => state.library.find((entry) => entry.id === track.id) || track);
    const savedQueueIndex = Number.isInteger(playback.queueIndex) ? playback.queueIndex : -1;
    state.queueIndex = state.queue.length
      ? Math.min(Math.max(savedQueueIndex, -1), state.queue.length - 1)
      : -1;
  } catch {
    state.shuffle = false;
    state.repeat = false;
    state.queue = [];
    state.queueIndex = -1;
  }
}

function hasLegacyLocalState() {
  return ["playlists", "favorites", "history"].some((name) => localStorage.getItem(accountStorageKey(name)) !== null);
}

async function importLegacyLocalState() {
  const ownerId = state.session?.user?.id;
  if (!ownerId) throw new Error("Entre na conta antes de importar os dados legados.");
  const legacy = readAccountLocalState(localStorage, null);
  const existingIds = new Set(state.playlists.map((playlist) => playlist.id));
  const importedPlaylists = legacy.playlists.map((playlist) => {
    let id = playlist.id || `legacy-${crypto.randomUUID()}`;
    if (existingIds.has(id)) id = `legacy-${crypto.randomUUID()}`;
    existingIds.add(id);
    return { ...playlist, id };
  });
  state.playlists = [...state.playlists, ...importedPlaylists];
  state.favorites = new Set([...state.favorites, ...legacy.favorites]);
  state.history = [...state.history, ...legacy.history]
    .sort((left, right) => (Date.parse(right.playedAt) || Number(right.playedAt) || 0) - (Date.parse(left.playedAt) || Number(left.playedAt) || 0))
    .slice(0, 100);
  storePlaylists();
  persistPreferences();
  for (const playlist of importedPlaylists) {
    await enqueueSyncChange(ownerId, "playlist", playlist.id, syncPlaylistPayload(playlist));
  }
  for (const track of state.library) {
    const key = `file:${track.id}`;
    const identity = makeTrackIdentity(track);
    if (legacy.favorites.has(key) || legacy.favorites.has(identity)) {
      const id = await stableTrackId(track.title || track.name, track.artist, track.album);
      await enqueueSyncChange(ownerId, "favorite", id, { favorite: true });
    }
  }
  for (const entry of legacy.history) {
    const playedAt = Number.isSafeInteger(entry.playedAt) ? entry.playedAt : Date.parse(entry.playedAt);
    if (!Number.isSafeInteger(playedAt)) continue;
    const trackId = await stableTrackId(entry.title, entry.artist, entry.album);
    await enqueueSyncChange(ownerId, "history", `history-${crypto.randomUUID()}`, {
      trackId,
      title: entry.title || "Faixa legada",
      artist: entry.artist || "",
      album: entry.album || "",
      playedAt,
    });
  }
  localStorage.setItem(accountStorageKey("legacy-imported", ownerId), new Date().toISOString());
  await synchronizeAccountNow();
  toast("Dados legados importados para esta conta; as cópias originais foram preservadas.");
  openSettings();
}

function switchAccountSession(session) {
  const previousOwner = state.session?.user?.id;
  const nextOwner = session?.user?.id;
  if (previousOwner !== nextOwner) {
    state.session = session;
    try {
      restoreAccountLocalState(nextOwner);
    } catch (error) {
      state.playlists = [];
      state.favorites = new Set();
      state.history = [];
      state.confirmedMatches = {};
      state.queue = [];
      state.queueIndex = -1;
      toast(`Os dados locais desta conta não puderam ser lidos: ${error.message}`);
    }
  } else {
    state.session = session;
  }
}

function syncPlaylistPayload(playlist) {
  return {
    name: playlist.name,
    description: playlist.description || "",
    source: playlist.source || "manual",
    sourceUrl: playlist.sourceUrl || "",
    tracks: playlist.tracks.map(({ title, artist, artists, album, durationSeconds, isrc, sourceUrl, authorizedSourceAvailable, explicit }) => ({
      title, artist, artists, album, durationSeconds, isrc, sourceUrl, authorizedSourceAvailable, explicit,
    })),
  };
}

async function apiRequest(path, { method = "GET", body, signal } = {}) {
  const session = state.session || await currentSession();
  const headers = body ? { "content-type": "application/json" } : {};
  if (session?.access_token) headers.Authorization = `Bearer ${session.access_token}`;
  const response = await fetch(`${API_BASE}/api${path}`, {
    method,
    credentials: session ? "omit" : "include",
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal,
  });
  if (response.status === 204) return null;
  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new Error("ASC Music API returned a non-JSON response. Check the Worker route and deployment.");
  }
  if (!response.ok) throw new Error(payload?.error || `ASC Music API returned HTTP ${response.status}.`);
  return payload;
}

async function applyRemoteSyncChange(change) {
  if (change.type === "playlist") {
    const remaining = state.playlists.filter((playlist) => playlist.id !== change.id);
    if (!change.deleted) remaining.push({ id: change.id, ...change.payload });
    state.playlists = remaining;
    localStorage.setItem(accountStorageKey("playlists", state.session?.user?.id), JSON.stringify(remaining));
  } else if (change.type === "favorite") {
    for (const track of state.library) {
      const id = await stableTrackId(track.title || track.name, track.artist, track.album);
      if (id === change.id) {
        const key = makeTrackIdentity(track);
        if (change.deleted || !change.payload.favorite) {
          state.favorites.delete(`file:${track.id}`);
          state.favorites.delete(key);
          state.favorites.delete(id);
        } else {
          state.favorites.add(`file:${track.id}`);
          state.favorites.add(key);
          state.favorites.add(id);
        }
      }
    }
    persistPreferences();
  } else if (change.type === "history" && !change.deleted) {
    const record = change.payload;
    if (!state.history.some((item) => item.syncId === change.id)) {
      state.history = [{ ...record, syncId: change.id }, ...state.history].slice(0, 100);
      localStorage.setItem(accountStorageKey("history", state.session?.user?.id), JSON.stringify(state.history));
    }
  } else if (change.type === "setting" && !change.deleted) {
    if (change.id === "shuffle") state.shuffle = change.payload.value === true;
    if (change.id === "repeat") state.repeat = change.payload.value === "all";
    if (change.id === "volume" && Number.isFinite(change.payload.value)) {
      audio.volume = Math.min(1, Math.max(0, change.payload.value));
    }
    persistPreferences();
  } else if (change.type === "library") {
    // Account metadata may be cached remotely, but the physical library stays
    // device-shared and is never replaced by a remote synchronization page.
  } else {
    throw new Error(`O cliente Web ainda não consegue aplicar alterações do tipo ${change.type}; o cursor foi preservado.`);
  }
}

async function synchronizeAccountNow() {
  if (!state.session?.user?.id) throw new Error("Entre na conta ASC Music para sincronizar.");
  if (state.syncAbortController) return;
  const controller = new AbortController();
  state.syncAbortController = controller;
  state.syncStatus = "syncing";
  render();
  try {
    const result = await synchronizeAccount(
      state.session.user.id,
      (path, options = {}) => apiRequest(path, { ...options, signal: controller.signal }),
      applyRemoteSyncChange,
      () => state.session?.user?.id,
    );
    state.syncStatus = result.pending ? "pending" : "synced";
    state.syncConflict = null;
    state.apiAvailable = true;
    render();
    toast(result.pending ? "Há alterações locais aguardando resolução de conflito." : "Dados sincronizados com a conta ASC Music.");
  } catch (error) {
    if (error.change && error.conflict) state.syncConflict = { change: error.change, current: error.conflict };
    state.syncStatus = error.name === "AbortError" ? "pending" : "offline";
    render();
    toast(`Sincronização pausada; alterações locais preservadas: ${error.message}`);
    throw error;
  } finally {
    if (state.syncAbortController === controller) state.syncAbortController = null;
  }
}

function cancelAccountSync() {
  state.syncAbortController?.abort();
  state.syncStatus = "pending";
  render();
}

async function synchronizePlaylist(playlist, create = false) {
  if (state.session?.user?.id) {
    await enqueueSyncChange(state.session.user.id, "playlist", playlist.id, syncPlaylistPayload(playlist));
    await synchronizeAccountNow();
    return;
  }
  if (!state.apiAvailable) return;
  const cloudId = playlist.cloudId;
  const payload = {
    name: playlist.name,
    description: playlist.description || "",
    source: playlist.source || "manual",
    sourceUrl: playlist.sourceUrl || "",
    tracks: playlist.tracks.map(({ title, artist, artists, album, durationSeconds, isrc, sourceUrl, authorizedSourceAvailable, explicit }) => ({
      title, artist, artists, album, durationSeconds, isrc, sourceUrl, authorizedSourceAvailable, explicit,
    })),
  };
  try {
    const result = create || !cloudId
      ? await apiRequest("/playlists", { method: "POST", body: payload })
      : await apiRequest(`/playlists/${encodeURIComponent(cloudId)}`, { method: "PUT", body: payload });
    if (result?.id && result.id !== playlist.cloudId) {
      playlist.cloudId = result.id;
      storePlaylists();
    }
  } catch (error) {
    state.apiAvailable = false;
    toast(`Playlist salva neste dispositivo. Nuvem indisponível: ${error.message}`);
  }
}

async function loadCloudData() {
  if (state.session?.user?.id) {
    try {
      await synchronizeAccountNow();
    } catch {
      $("#library-status").textContent = "Offline · alterações locais preservadas";
    }
    return;
  }
  try {
    const [remotePlaylists, remoteLibrary] = await Promise.all([
      apiRequest("/playlists"),
      apiRequest("/library"),
    ]);
    state.apiAvailable = true;
    for (const remote of remotePlaylists.playlists || []) {
      const current = state.playlists.find((playlist) => playlist.cloudId === remote.id);
      if (current) continue;
      state.playlists.push({ ...remote, id: `cloud-${remote.id}`, cloudId: remote.id });
    }
    const remoteFiles = new Map((remoteLibrary.files || []).map((file) => [
      file.id,
      { ...file, source: file.source || "drive" },
    ]));
    const localOnly = state.library.filter((file) => file.source === "local");
    const inSession = state.library.filter((file) => file.source === "drive" && remoteFiles.has(file.id));
    for (const file of inSession) remoteFiles.set(file.id, { ...remoteFiles.get(file.id), ...file });
    state.library = [...localOnly, ...remoteFiles.values()];
    await writeLibraryIndex(state.library);
    storePlaylists();
    render();
  } catch (error) {
    state.apiAvailable = false;
    $("#library-status").textContent = "Somente neste dispositivo";
    console.info("ASC Music cloud sync is not available:", error.message);
  }
}

async function syncLibraryToCloud(removedFiles = []) {
  const ownerId = state.session?.user?.id;
  if (ownerId) {
    const changes = state.library.map(async (file) => ({
      id: await stableTrackId(file.title || file.name, file.artist, file.album),
      payload: {
        source: file.source === "local" ? "local" : "drive",
        name: file.name,
        title: file.title || "",
        artist: file.artist || "",
        album: file.album || "",
        durationSeconds: file.durationSeconds ?? null,
        format: file.format || "",
        sizeBytes: Number.isSafeInteger(file.sizeBytes) ? file.sizeBytes : null,
        revision: file.revision || "",
        isrc: file.isrc || "",
        artists: Array.isArray(file.artists) ? file.artists : [],
      },
    }))
    for (const change of await Promise.all(changes)) {
      await enqueueSyncChange(ownerId, "library", change.id, change.payload);
    }
    for (const file of removedFiles) {
      const id = await stableTrackId(file.title || file.name, file.artist, file.album);
      await enqueueSyncChange(ownerId, "library", id, {}, true);
    }
    await synchronizeAccountNow();
    return;
  }
  if (!state.apiAvailable) return;
  const files = state.library.map((file) => ({
    id: file.id,
    source: file.source === "local" ? "local" : "drive",
    name: file.name,
    title: file.title,
    artist: file.artist,
    album: file.album,
    durationSeconds: file.durationSeconds,
    format: file.format,
    sizeBytes: file.sizeBytes,
    revision: file.revision,
    isrc: file.isrc,
    artists: file.artists,
    metadataError: file.metadataError,
  }));
  let updated = 0;
  for (let offset = 0; offset < files.length; offset += 200) {
    const result = await apiRequest("/library/sync", {
      method: "POST",
      body: { files: files.slice(offset, offset + 200), removedIds: [] },
    });
    updated += result.updated;
  }
  const removedIds = removedFiles.map((file) => file.id);
  for (let offset = 0; offset < removedIds.length; offset += 400) {
    await apiRequest("/library/sync", {
      method: "POST",
      body: { files: [], removedIds: removedIds.slice(offset, offset + 400) },
    });
  }
  if (updated || removedIds.length) {
    toast(`${updated} faixa(s) sincronizadas; ${removedIds.length} removida(s) da biblioteca da conta.`);
  }
}

function getSettingsHtml() {
  const drive = getDriveConfiguration();
  const clientId = localStorage.getItem("asc.spotify.clientId") || "";
  const accountHtml = !authConfigured
    ? `<div class="field-help">${escapeHtml(authConfigurationError || "Autenticação ASC ainda não configurada. Defina VITE_SUPABASE_URL e VITE_SUPABASE_PUBLISHABLE_KEY no build.")}</div>`
    : state.recoveryMode
      ? `<div class="field"><label for="account-password">Nova senha</label><input id="account-password" type="password" autocomplete="new-password" minlength="8"></div><button class="button button-outline button-small" data-action="auth-update-password">Atualizar senha</button>`
      : state.session?.user
        ? `<p>Conectado como ${escapeHtml(state.session.user.email || "conta ASC")}</p><p class="field-help">Estado: ${escapeHtml(state.syncStatus)}. Alterações locais ficam na fila até confirmação do Worker.</p><button class="button button-outline button-small" data-action="sync-now">Sincronizar agora</button> <button class="button button-outline button-small" data-action="auth-signout">Sair</button>`
        : `<div class="field"><label for="account-email">E-mail</label><input id="account-email" type="email" autocomplete="email"></div><div class="field"><label for="account-password">Senha</label><input id="account-password" type="password" autocomplete="current-password"></div><button class="button button-outline button-small" data-action="auth-signin">Entrar</button> <button class="button button-outline button-small" data-action="auth-signup">Criar conta</button> <button class="button button-outline button-small" data-action="auth-recover">Recuperar acesso</button>`;
  return `<div class="modal-inner">
    <div class="modal-header"><div><div class="eyebrow">PREFERÊNCIAS</div><h2>Configurações e conexões</h2></div><button class="modal-close" data-action="close-settings" aria-label="Fechar">×</button></div>
    <div class="settings-grid">
      <section class="integration-card"><h3>Google Drive</h3><p>O ASC solicita acesso somente de leitura. Tokens ficam em memória e os arquivos não são enviados ou copiados para o banco.</p>
        <div class="field"><label for="google-client-id">OAuth client ID</label><input id="google-client-id" autocomplete="off" value="${escapeHtml(drive.clientId)}" placeholder="...apps.googleusercontent.com"></div>
        <div class="field"><label for="google-api-key">API key restrita</label><input id="google-api-key" autocomplete="off" value="${escapeHtml(drive.apiKey)}" placeholder="Chave com restrição de domínio e API"></div>
        <div class="field-help">No Google Cloud, habilite Google Drive API e Google Picker API; configure a origem atual em OAuth e restrinja a API key por domínio.</div>
        <button class="button button-outline button-small" data-action="save-settings">Salvar configuração</button>
      </section>
      <section class="integration-card"><h3>Spotify — somente descoberta e playlists</h3><p>OAuth Authorization Code + PKCE, sem client secret. Spotify fornece metadados; a reprodução usa seus arquivos.</p>
        <div class="field"><label for="spotify-client-id">Spotify client ID</label><input id="spotify-client-id" autocomplete="off" value="${escapeHtml(clientId)}" placeholder="Client ID do Spotify Developer Dashboard"></div>
        <div class="field-help">Cadastre a URL exata desta origem como Redirect URI no painel Spotify. Se a API não autorizar a playlist, importe por CSV ou texto.</div>
        <button class="button button-outline button-small" data-action="save-settings">Salvar configuração</button>
      </section>
      <section class="integration-card"><h3>Conta ASC Music</h3><p>Web e Android usam a identidade compartilhada configurada no Supabase. A chave publishable é pública; chaves de serviço nunca devem ser incluídas no frontend.</p>
        ${state.spotifyConnected ? `<div class="field"><button class="button button-outline button-small" data-action="spotify-playlists">Listar minhas playlists do Spotify</button>${state.spotifyPlaylists.length ? `<div class="provider-list">${state.spotifyPlaylists.map((playlist) => `<button class="button button-outline button-small" data-action="import-spotify-playlist" data-playlist-id="${escapeHtml(playlist.id)}">${escapeHtml(playlist.name || "Playlist sem nome")}</button>`).join("")}</div>` : ""}</div>` : ""}
        ${state.syncAbortController ? `<button class="button button-outline button-small" data-action="cancel-sync">Cancelar sincronização</button>` : ""}
        ${accountHtml}
        ${state.session?.user && hasLegacyLocalState() && !localStorage.getItem(accountStorageKey("legacy-imported", state.session.user.id)) ? `<button class="button button-outline button-small" data-action="import-legacy">Importar dados deste navegador</button>` : ""}
        ${state.syncConflict ? `<div class="callout error-callout"><strong>Conflito de sincronização</strong><p>${escapeHtml(state.syncConflict.change.type)} / ${escapeHtml(state.syncConflict.change.id)} mudou no servidor.</p><button class="button button-outline button-small" data-action="conflict-remote">Usar versão remota</button> <button class="button button-outline button-small" data-action="conflict-local">Manter minha versão</button></div>` : ""}
        <div class="field-help">Estado da sincronização: ${escapeHtml(state.syncStatus)}. Worker HTTPS: ${escapeHtml(API_BASE || window.location.origin)}. CF Access continua sendo uma política de borda separada.</div>
      </section>
    </div>
    <div id="settings-message" class="field-help" role="status" style="margin-top:15px"></div>
  </div>`;
}

function openSettings() {
  settingsDialog.innerHTML = getSettingsHtml();
  settingsDialog.showModal();
}

function closeSettings() {
  if (settingsDialog.open) settingsDialog.close();
}

function renderImportDialog() {
  const tabs = [
    ["url", "URL do Spotify"], ["csv", "Arquivo CSV"], ["text", "Lista de músicas"],
  ].map(([id, label]) => `<button class="modal-tab ${state.importTab === id ? "active" : ""}" data-action="import-tab" data-tab="${id}">${label}</button>`).join("");
  const input = state.importTab === "url"
    ? `<div class="field"><label for="playlist-url">Link da playlist</label><input id="playlist-url" type="url" placeholder="https://open.spotify.com/playlist/..." autocomplete="url"><span class="field-help">A importação consulta a Web API oficial depois do OAuth PKCE. Sem acesso configurado, nenhuma extração ou scraping é realizado.</span></div>`
    : state.importTab === "csv"
      ? `<div class="field"><label for="playlist-name">Nome da playlist (opcional)</label><input id="playlist-name" placeholder="Minha playlist"></div><div class="field"><label for="playlist-csv">Arquivo CSV</label><input id="playlist-csv" type="file" accept=".csv,text/csv"><span class="field-help">Colunas aceitas: Title/Track/Name, Artist, Album, Duration, ISRC, URL. CSVs exportados com aspas são compatíveis.</span></div>`
      : `<div class="field"><label for="playlist-name">Nome da playlist (opcional)</label><input id="playlist-name" placeholder="Minha seleção"></div><div class="field"><label for="track-list">Uma faixa por linha: Artista — Música</label><textarea id="track-list" placeholder="Liniker — Caju&#10;Djavan — Oceano"></textarea><span class="field-help">Você também pode escrever apenas o título; esses casos exigem confirmação manual na comparação.</span></div>`;
  return `<div class="modal-inner">
    <div class="modal-header"><div><div class="eyebrow">PLAYLISTS</div><h2>Importar ou montar uma lista</h2></div><button class="modal-close" data-action="close-import" aria-label="Fechar">×</button></div>
    <div class="modal-tabs">${tabs}</div>
    ${state.importError ? `<div class="callout error-callout" style="margin-bottom:13px">${escapeHtml(state.importError)}</div>` : ""}
    <div id="import-form-fields">${input}</div>
    ${state.importTab === "url" ? `<div class="field-help">Se o endpoint ou a autenticação do Spotify não estiver disponível, use a aba CSV ou cole as músicas como texto.</div>` : ""}
    <div class="modal-actions"><button class="button button-outline button-small" data-action="close-import">Cancelar</button><button class="button button-primary button-small" data-action="submit-import">${state.importTab === "url" ? "Continuar com Spotify" : "Criar playlist"} ↗</button></div>
  </div>`;
}

function openImport(tab = "url", targetPlaylistId = "") {
  state.importTab = tab;
  state.importError = "";
  state.importTargetPlaylist = targetPlaylistId;
  importDialog.innerHTML = renderImportDialog();
  importDialog.showModal();
}

function closeImport() {
  if (importDialog.open) importDialog.close();
}

function saveImportedPlaylist(imported, desiredName) {
  const target = playlistById(state.importTargetPlaylist);
  if (target) {
    target.tracks.push(...imported.tracks.map((track) => ({ ...track, id: track.id || crypto.randomUUID() })));
    if (imported.description) target.description = imported.description;
    storePlaylists();
    void synchronizePlaylist(target);
    const targetId = target.id;
    state.importTargetPlaylist = "";
    closeImport();
    setPage(`playlist:${targetId}`);
    toast(`${imported.tracks.length} faixa(s) adicionadas à playlist.`);
    return;
  }
  const playlist = {
    id: crypto.randomUUID(),
    name: desiredName?.trim() || imported.name || "Nova playlist",
    description: imported.description || "",
    source: imported.source || "manual",
    sourceUrl: imported.sourceUrl || "",
    coverArt: imported.coverArt || "",
    createdAt: new Date().toISOString(),
    tracks: imported.tracks.map((track) => ({ ...track, id: track.id || crypto.randomUUID() })),
  };
  state.playlists.unshift(playlist);
  storePlaylists();
  void synchronizePlaylist(playlist, true);
  closeImport();
  setPage(`playlist:${playlist.id}`);
  toast(`${countLabel(playlist.tracks.length, "faixa adicionada à playlist", "faixas adicionadas à playlist")}.`);
}

async function submitImport() {
  state.importError = "";
  if (state.importTab === "url") {
    const value = $("#playlist-url").value.trim();
    if (!spotifyPlaylistId(value)) {
      state.importError = "Cole um link de playlist pública ou autorizada do Spotify. Você também pode importar por CSV ou texto.";
    } else if (!spotifyConfigured()) {
      state.importError = "Configure um Spotify client ID em Configurações. Enquanto isso, importe as faixas por CSV ou texto.";
    } else if (!state.spotifyConnected) {
      sessionStorage.setItem("asc.spotify.pendingPlaylist", value);
      try {
        await connectSpotify();
      } catch (error) {
        state.importError = error.message;
      }
      return;
    } else {
      try {
        const imported = await importSpotifyPlaylist(value);
        saveImportedPlaylist(imported, imported.name);
      } catch (error) {
        state.importError = `${error.message} Como alternativa, importe a lista por CSV ou texto.`;
      }
    }
  } else if (state.importTab === "csv") {
    const file = $("#playlist-csv").files?.[0];
    if (!file) state.importError = "Escolha um arquivo CSV para continuar.";
    else {
      try {
        const imported = parsePlaylistCsv(await file.text());
        saveImportedPlaylist(imported, $("#playlist-name").value);
      } catch (error) {
        state.importError = error.message;
      }
    }
  } else {
    try {
      const imported = parseTrackList($("#track-list").value);
      saveImportedPlaylist(imported, $("#playlist-name").value);
    } catch (error) {
      state.importError = error.message;
    }
  }
  if (importDialog.open) importDialog.innerHTML = renderImportDialog();
}

async function saveSettings() {
  const googleClientId = $("#google-client-id")?.value.trim() || "";
  const googleApiKey = $("#google-api-key")?.value.trim() || "";
  const spotifyId = $("#spotify-client-id")?.value.trim() || "";
  if (googleClientId && !googleClientId.endsWith(".apps.googleusercontent.com")) {
    $("#settings-message").textContent = "O Google OAuth client ID precisa terminar em .apps.googleusercontent.com.";
    return;
  }
  localStorage.setItem("asc.google.clientId", googleClientId);
  localStorage.setItem("asc.google.apiKey", googleApiKey);
  setSpotifyClientId(spotifyId);
  state.drive = new DriveConnector({ clientId: googleClientId, apiKey: googleApiKey });
  $("#settings-message").textContent = "Configuração salva neste navegador. Segredos privados não devem ser adicionados aqui.";
  toast("Configurações salvas neste dispositivo.");
  setTimeout(closeSettings, 800);
}

async function selectDriveFolder() {
  if (!state.drive.configured()) {
    openSettings();
    toast("Configure o OAuth client ID e a API key do Google primeiro.");
    return;
  }
  const folder = await state.drive.selectFolder();
  if (!folder) return;
  localStorage.setItem(accountStorageKey("drive.folder", state.session?.user?.id), JSON.stringify(folder));
  await verifyDrive(folder);
}

async function loadSpotifyPlaylists() {
  if (!state.spotifyConnected) {
    await connectSpotify();
    return;
  }
  state.spotifyPlaylists = await listSpotifyPlaylists();
  openSettings();
}

async function verifyDrive(selectedFolder) {
  const rawFolder = selectedFolder || localStorage.getItem(accountStorageKey("drive.folder", state.session?.user?.id));
  if (!rawFolder) {
    await selectDriveFolder();
    return;
  }
  const folder = typeof rawFolder === "string" ? JSON.parse(rawFolder) : rawFolder;
  if (!state.drive.configured()) {
    openSettings();
    toast("Configure as credenciais do Google para verificar o Drive.");
    return;
  }
  await state.drive.ensureAuthorized();
  toast(`Verificando a pasta “${folder.name}”…`);
  const previous = state.library;
  const next = await state.drive.verify(folder, previous, ({ current, total }) => {
    $("#library-status").textContent = `Indexando ${current}/${total}`;
  });
  localStorage.setItem(accountStorageKey("drive.folder", state.session?.user?.id), JSON.stringify(folder));
  const oldDriveIds = new Set(previous.filter((file) => file.source === "drive").map((file) => file.id));
  const nextDriveIds = new Set(next.filter((file) => file.source === "drive").map((file) => file.id));
  const removedFiles = previous.filter((file) => file.source === "drive" && !nextDriveIds.has(file.id));
  state.library = next;
  await writeLibraryIndex(next);
  render();
  if (state.apiAvailable || state.session?.user?.id) await syncLibraryToCloud(removedFiles);
  toast(`${next.filter((file) => file.source === "drive").length} faixa(s) do Drive verificadas. ${removedFiles.length} removida(s) do índice.`);
}

async function verifyLibrary() {
  if (state.library.some((file) => file.source === "local")) {
    toast("Selecione novamente os arquivos locais para atualizar e reconciliar a biblioteca.");
    await addLocalFiles({ replaceLocal: true });
    return;
  }
  if (localStorage.getItem(accountStorageKey("drive.folder", state.session?.user?.id))) {
    await verifyDrive();
    return;
  }
  await selectDriveFolder();
}

async function addLocalFiles({ replaceLocal = false } = {}) {
  state.replaceLocalOnNextSelection = replaceLocal;
  let picker = $("#audio-files");
  if (!picker) {
    setPage("library");
    picker = $("#audio-files");
  }
  picker.click();
}

async function indexLocalFiles(files, { replaceLocal = false } = {}) {
  const entries = [];
  const existingByIdentity = new Map();
  for (const file of state.library.filter((entry) => entry.source === "local")) {
    const identity = file.identityKey || `${file.name}\u0000${file.sizeBytes}\u0000${Date.parse(file.modifiedTime) || 0}`;
    const matches = existingByIdentity.get(identity) || [];
    matches.push(file);
    existingByIdentity.set(identity, matches);
  }
  const occurrenceByIdentity = new Map();
  for (const file of files) {
    const identity = `${file.name}\u0000${file.size}\u0000${file.lastModified}`;
    const occurrence = occurrenceByIdentity.get(identity) || 0;
    occurrenceByIdentity.set(identity, occurrence + 1);
    const previous = existingByIdentity.get(identity)?.[occurrence];
    const id = previous?.id || `local-${crypto.randomUUID()}`;
    const revision = `${file.size}:${file.lastModified}`;
    const track = previous?.revision === revision
      ? { ...previous, identityKey: identity }
      : { ...await readAudioMetadata(file, id), identityKey: identity };
    const oldUrl = state.localAudio.get(id);
    if (oldUrl) URL.revokeObjectURL(oldUrl);
    state.localAudio.set(id, URL.createObjectURL(file));
    entries.push(track);
  }
  const selectedIds = new Set(entries.map((entry) => entry.id));
  const removedFiles = replaceLocal
    ? state.library.filter((file) => file.source === "local" && !selectedIds.has(file.id))
    : [];
  for (const { id } of removedFiles) {
    const objectUrl = state.localAudio.get(id);
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    state.localAudio.delete(id);
  }
  state.library = applyLibraryDelta(
    replaceLocal ? state.library.filter((file) => file.source !== "local") : state.library,
    entries,
    removedFiles.map((file) => file.id),
  );
  await writeLibraryIndex(state.library);
  render();
  if (state.apiAvailable || state.session?.user?.id) await syncLibraryToCloud(removedFiles);
  const withoutMetadata = entries.filter((entry) => entry.metadataError).length;
  toast(`${countLabel(entries.length, "faixa adicionada", "faixas adicionadas")}.${withoutMetadata ? ` ${countLabel(withoutMetadata, "arquivo sem metadados exige confirmação", "arquivos sem metadados exigem confirmação")}.` : ""}`);
}

async function loadDiscovery(section) {
  state.loadingDiscovery = true;
  state.discoveryError = "";
  const request = ++state.discoveryRequest;
  render();
  try {
    const artists = [...new Set(state.library.map((file) => file.artist).filter(Boolean))].slice(0, 5);
    const path = section === "similar"
      ? `/discovery/similar?artists=${encodeURIComponent(artists.join("|"))}`
      : `/discovery/${encodeURIComponent(section)}`;
    const result = await apiRequest(path);
    if (request !== state.discoveryRequest) return;
    state.discoveryCache.set(section, result);
  } catch (error) {
    if (request !== state.discoveryRequest) return;
    state.discoveryError = error.message.includes("Unexpected token")
      ? "O backend de descoberta ainda não está implantado nesta origem."
      : error.message;
  } finally {
    if (request === state.discoveryRequest) {
      state.loadingDiscovery = false;
      render();
    }
  }
}

async function playTrack(track, queue = state.library) {
  const file = state.library.find((entry) => entry.id === track.id)
    || track.match?.candidates?.[0]?.file;
  if (!file) throw new Error("Esta faixa não está disponível na biblioteca.");
  let source = state.localAudio.get(file.id);
  if (!source && file.source === "drive") source = await state.drive.audioUrl(file.id);
  if (!source) throw new Error("Selecione novamente o arquivo local para reproduzi-lo. O índice não guarda cópias de áudio.");
  if (state.driveAudioUrl) URL.revokeObjectURL(state.driveAudioUrl);
  state.driveAudioUrl = file.source === "drive" ? source : "";
  audio.pause();
  audio.src = source;
  audio.load();
  state.current = file;
  state.queue = queue;
  state.queueIndex = queue.findIndex((entry) => entry.id === file.id);
  persistPreferences();
  renderPlayer();
  try {
    await audio.play();
    state.history = appendPlaybackHistory(state.history, file);
    if (state.session?.user?.id) {
      const playedAt = Date.now();
      const trackId = await stableTrackId(file.title || file.name, file.artist, file.album);
      const historyId = `history-${crypto.randomUUID()}`;
      void enqueueSyncChange(state.session.user.id, "history", historyId, {
        trackId,
        title: file.title || file.name,
        artist: file.artist || "",
        album: file.album || "",
        playedAt,
      }).then(() => synchronizeAccountNow()).catch((error) => {
        state.syncStatus = "pending";
        toast(`Histórico salvo offline; sincronização pendente: ${error.message}`);
      });
    }
    try {
      localStorage.setItem(accountStorageKey("history", state.session?.user?.id), JSON.stringify(state.history));
    } catch (error) {
      toast(`O histórico não pôde ser salvo neste navegador: ${error.message}`);
    }
  } catch (error) {
    if (state.driveAudioUrl) URL.revokeObjectURL(state.driveAudioUrl);
    state.driveAudioUrl = "";
    state.current = null;
    renderPlayer();
    throw new Error(`A reprodução falhou: ${error instanceof Error ? error.message : "formato ou conexão indisponível"}`);
  }
  renderPlayer();
}

async function playNext(direction = 1) {
  if (!state.queue.length) return;
  const candidates = state.queue.filter((track) => state.localAudio.has(track.id) || track.source === "drive");
  if (!candidates.length) return;
  let nextIndex = state.queueIndex + direction;
  if (state.shuffle && direction > 0) nextIndex = Math.floor(Math.random() * candidates.length);
  if (direction > 0 && !state.shuffle && nextIndex >= candidates.length) {
    audio.pause();
    audio.removeAttribute("src");
    audio.load();
    if (state.driveAudioUrl) URL.revokeObjectURL(state.driveAudioUrl);
    state.driveAudioUrl = "";
    state.current = null;
    state.queueIndex = -1;
    renderPlayer();
    return;
  }
  if (nextIndex < 0) nextIndex = candidates.length - 1;
  if (nextIndex >= candidates.length) nextIndex = 0;
  const track = candidates[nextIndex];
  state.queueIndex = nextIndex;
  await playTrack(track, candidates);
}

async function saveDiscoveryPlaylist() {
  const current = state.discoveryCache.get(state.discoverySection);
  if (!current?.tracks?.length) throw new Error("Não há resultados para adicionar.");
  const playlist = {
    name: current.section || "Descobertas",
    description: `${current.source} · atualizado em ${new Date(current.updatedAt).toLocaleDateString("pt-BR")}`,
    source: current.source || "Discovery",
    tracks: current.tracks.map((track) => ({ ...track, id: crypto.randomUUID() })),
  };
  state.playlists.unshift({ id: crypto.randomUUID(), ...playlist });
  storePlaylists();
  const saved = state.playlists[0];
  void synchronizePlaylist(saved, true);
  setPage(`playlist:${saved.id}`);
  toast("Seleção salva como playlist; cada faixa será comparada com sua biblioteca.");
}

function createSmartPlaylist(kind) {
  if (kind === "collection") {
    state.playlists.unshift({
      id: crypto.randomUUID(),
      name: "Minha coleção completa",
      description: "Faixas atualmente indexadas na biblioteca.",
      source: "ASC Music",
      tracks: state.library.map((file) => ({ ...file })),
    });
    const playlist = state.playlists[0];
    storePlaylists();
    setPage(`playlist:${playlist.id}`);
    void synchronizePlaylist(playlist, true);
    return;
  }
  const section = kind === "releases" ? "releases" : kind;
  state.discoverySection = section;
  state.discoveryCache.delete(section);
  setPage("discover");
}

function openMatchReview(playlistId, trackIndex) {
  const playlist = playlistById(playlistId);
  if (!playlist) return;
  const result = compare(playlist);
  const track = result.tracks[trackIndex];
  if (!track?.match?.candidates?.length) throw new Error("Não há arquivos semelhantes para confirmar.");
  const candidates = track.match.candidates.map((candidate) => {
    const file = candidate.file;
    return `<div class="match-candidate"><strong>${escapeHtml(file.title || file.name)}</strong><small>${escapeHtml(file.artist || "Artista desconhecido")} · ${escapeHtml(file.album || "Álbum não identificado")} · ${candidate.score}%${candidate.variants.length ? ` · versão diferente: ${escapeHtml(candidate.variants.join(", "))}` : ""}</small>
      <button class="button button-outline button-small" style="margin-top:9px" data-action="confirm-match" data-playlist-id="${escapeHtml(playlistId)}" data-track-index="${trackIndex}" data-file-id="${escapeHtml(file.id)}">Confirmar este arquivo</button>
    </div>`;
  }).join("");
  importDialog.innerHTML = `<div class="modal-inner"><div class="modal-header"><div><div class="eyebrow">CONFIRMAÇÃO HUMANA</div><h2>${escapeHtml(track.title)}</h2></div><button class="modal-close" data-action="close-import" aria-label="Fechar">×</button></div>
    <p class="panel-note">${escapeHtml(track.artist || "Artista não identificado")} · Selecione apenas a gravação correta. Uma versão incompatível não será marcada automaticamente.</p>${candidates}<div class="modal-actions"><button class="button button-outline button-small" data-action="close-import">Manter como possível correspondência</button></div></div>`;
  importDialog.showModal();
}

async function handleAction(button) {
  const { action } = button.dataset;
  if (action === "settings") openSettings();
  else if (action === "auth-signin" || action === "auth-signup" || action === "auth-recover") {
    const email = $("#account-email")?.value.trim() || "";
    const password = $("#account-password")?.value || "";
    if (!email) throw new Error("Informe o e-mail da conta.");
    if (action !== "auth-recover" && password.length < 8) throw new Error("A senha deve ter pelo menos 8 caracteres.");
    let message;
    if (action === "auth-signin") {
      const { data } = await signIn(email, password);
      switchAccountSession(data.session);
      state.syncStatus = "pending";
      await synchronizeAccountNow();
      message = `Conectado como ${data.user.email || email}.`;
    } else if (action === "auth-signup") {
      const { data } = await registerAccount(email, password);
      switchAccountSession(data.session);
      message = data.session ? "Conta criada e conectada." : "Confirme seu e-mail para ativar a conta.";
      if (data.session) await synchronizeAccountNow();
    } else {
      await recoverAccount(email);
      message = "Se a conta existir, você receberá um link de recuperação.";
    }
    if ($("#settings-message")) $("#settings-message").textContent = message;
    openSettings();
    toast(message);
  } else if (action === "auth-signout") {
    await signOut();
    switchAccountSession(null);
    state.syncStatus = "offline";
    openSettings();
    toast("Sessão encerrada neste navegador.");
  } else if (action === "conflict-remote" || action === "conflict-local") {
    const ownerId = state.session?.user?.id;
    const conflict = state.syncConflict;
    if (!ownerId || !conflict) throw new Error("Não há conflito ativo para resolver.");
    await resolveSyncConflict(
      ownerId,
      conflict.change,
      conflict.current,
      action === "conflict-remote" ? "remote" : "local",
      applyRemoteSyncChange,
      () => state.session?.user?.id,
    );
    state.syncConflict = null;
    state.syncStatus = "pending";
    openSettings();
    await synchronizeAccountNow();
  } else if (action === "import-legacy") {
    await importLegacyLocalState();
  } else if (action === "auth-update-password") {
    const password = $("#account-password")?.value || "";
    if (password.length < 8) throw new Error("A nova senha deve ter pelo menos 8 caracteres.");
    await completePasswordRecovery(password);
    state.recoveryMode = false;
    openSettings();
    toast("Senha atualizada.");
  } else if (action === "sync-now") await synchronizeAccountNow();
  else if (action === "cancel-sync") cancelAccountSync();
  else if (action === "connect-drive") await selectDriveFolder();
  else if (action === "verify-library") await verifyLibrary();
  else if (action === "add-local-files") await addLocalFiles();
  else if (action === "connect-spotify") {
    if (!spotifyConfigured()) openSettings();
    else await connectSpotify();
  } else if (action === "spotify-playlists") await loadSpotifyPlaylists();
  else if (action === "import-spotify-playlist") {
    const imported = await importSpotifyPlaylist(button.dataset.playlistId);
    closeSettings();
    saveImportedPlaylist(imported, imported.name);
  } else if (action === "open-import") openImport("url");
  else if (action === "new-playlist") openImport("text");
  else if (action === "add-playlist-tracks") openImport("url", button.dataset.playlistId);
  else if (action === "close-import") closeImport();
  else if (action === "close-settings") closeSettings();
  else if (action === "import-tab") {
    state.importTab = button.dataset.tab;
    state.importError = "";
    importDialog.innerHTML = renderImportDialog();
  } else if (action === "submit-import") await submitImport();
  else if (action === "save-settings") await saveSettings();
  else if (action === "open-matches") setPage("matches");
  else if (action === "discovery-section") {
    state.discoverySection = button.dataset.section;
    state.discoveryError = "";
    state.loadingDiscovery = false;
    render();
  } else if (action === "save-discovery-playlist") await saveDiscoveryPlaylist();
  else if (action === "add-discovery-track") {
    const track = state.discoveryTracks[Number(button.dataset.trackIndex)];
    if (!track) return;
    const imported = { ...track, id: crypto.randomUUID(), source: state.discoveryCache.get(state.discoverySection)?.source || "Discovery" };
    const playlist = state.playlists.find((entry) => entry.name === "Descobertas salvas");
    if (playlist) playlist.tracks.push(imported);
    else state.playlists.unshift({ id: crypto.randomUUID(), name: "Descobertas salvas", source: "Discovery", tracks: [imported] });
    storePlaylists();
    const saved = state.playlists.find((entry) => entry.name === "Descobertas salvas");
    void synchronizePlaylist(saved, saved.tracks.length === 1);
    toast(`“${track.title}” adicionada à playlist Descobertas salvas.`);
    render();
  } else if (action === "create-smart") createSmartPlaylist(button.dataset.smart);
  else if (action === "delete-playlist") {
    const playlist = playlistById(button.dataset.playlistId);
    if (!playlist) return;
    if (state.session?.user?.id) {
      await enqueueSyncChange(state.session.user.id, "playlist", playlist.id, {}, true);
      void synchronizeAccountNow().catch(() => {});
    } else if (playlist.cloudId && state.apiAvailable) await apiRequest(`/playlists/${encodeURIComponent(playlist.cloudId)}`, { method: "DELETE" });
    state.playlists = state.playlists.filter((entry) => entry.id !== playlist.id);
    storePlaylists();
    setPage("playlists");
    toast("Playlist removida.");
  } else if (action === "remove-track") {
    const playlist = playlistById(button.dataset.playlistId);
    const trackIndex = Number(button.dataset.trackIndex);
    if (!playlist || !Number.isInteger(trackIndex)) return;
    playlist.tracks.splice(trackIndex, 1);
    storePlaylists();
    await synchronizePlaylist(playlist);
    render();
  } else if (action === "move-track-up" || action === "move-track-down") {
    const playlist = playlistById(button.dataset.playlistId);
    const index = Number(button.dataset.trackIndex);
    const targetIndex = index + (action === "move-track-up" ? -1 : 1);
    if (!playlist || targetIndex < 0 || targetIndex >= playlist.tracks.length) return;
    [playlist.tracks[index], playlist.tracks[targetIndex]] = [playlist.tracks[targetIndex], playlist.tracks[index]];
    storePlaylists();
    await synchronizePlaylist(playlist);
    render();
  } else if (action === "rename-playlist") {
    const playlist = playlistById(button.dataset.playlistId);
    if (!playlist) return;
    const name = window.prompt("Novo nome da playlist", playlist.name)?.trim();
    if (!name) return;
    if (name.length > 200) throw new Error("O nome deve ter até 200 caracteres.");
    playlist.name = name;
    storePlaylists();
    await synchronizePlaylist(playlist);
    render();
  } else if (action === "review-match") openMatchReview(button.dataset.playlistId, Number(button.dataset.trackIndex));
  else if (action === "confirm-match") {
    const playlist = playlistById(button.dataset.playlistId);
    const key = playlistTrackKey(playlist, Number(button.dataset.trackIndex));
    state.confirmedMatches[key] = button.dataset.fileId;
    persistPreferences();
    closeImport();
    render();
    toast("Correspondência confirmada. A disponibilidade foi atualizada.");
  } else if (action === "mark-acquired") {
    await verifyLibrary();
  } else if (action === "toggle-source-availability") {
    const playlist = playlistById(button.dataset.playlistId);
    const track = playlist?.tracks[Number(button.dataset.trackIndex)];
    if (!playlist || !track) return;
    track.authorizedSourceAvailable = track.authorizedSourceAvailable === false;
    storePlaylists();
    await synchronizePlaylist(playlist);
    render();
  } else if (action === "play-track") {
    const playlist = button.dataset.playlistId && playlistById(button.dataset.playlistId);
    const track = playlist && compare(playlist).tracks[Number(button.dataset.trackIndex)];
    const file = track?.match?.candidates?.[0]?.file
      || state.library.find((entry) => entry.id === button.dataset.trackId);
    if (!file) throw new Error("Arquivo removido do índice.");
    await playTrack(file, state.library);
  } else if (action === "play-playlist") {
    const playlist = playlistById(button.dataset.playlistId);
    const result = playlist && compare(playlist);
    const tracks = result?.tracks
      .filter((track) => [TRACK_STATUS.AVAILABLE, TRACK_STATUS.DUPLICATE].includes(track.match.status))
      .map((track) => track.match.candidates[0].file) || [];
    if (!tracks.length) throw new Error("Nenhuma faixa disponível para reproduzir. A playlist completa continua visível para comparação.");
    state.queue = tracks;
    state.queueIndex = -1;
    await playNext(1);
  } else if (action === "toggle-play") {
    if (!state.current) throw new Error("Selecione uma música disponível na biblioteca.");
    if (audio.paused) await audio.play();
    else audio.pause();
    renderPlayer();
  } else if (action === "previous") await playNext(-1);
  else if (action === "next") await playNext(1);
  else if (action === "shuffle") {
    state.shuffle = !state.shuffle;
    persistPreferences();
    queuePlaybackSetting("shuffle", state.shuffle);
    renderPlayer();
  } else if (action === "repeat") {
    state.repeat = !state.repeat;
    persistPreferences();
    queuePlaybackSetting("repeat", state.repeat ? "all" : "off");
    renderPlayer();
  } else if (action === "toggle-favorite") {
    const key = button.dataset.favoriteKey;
    if (state.favorites.has(key)) state.favorites.delete(key);
    else state.favorites.add(key);
    persistPreferences();
    if (state.session?.user?.id) {
      const id = await stableTrackId(button.dataset.title, button.dataset.artist, button.dataset.album);
      const favorite = state.favorites.has(key);
      if (favorite) state.favorites.add(id);
      else state.favorites.delete(id);
      void enqueueSyncChange(state.session.user.id, "favorite", id, { favorite })
        .then(() => synchronizeAccountNow())
        .catch((error) => toast(`Favorito salvo localmente; sincronização pendente: ${error.message}`));
    }
    render();
  } else if (action === "toggle-current-favorite") {
    if (!state.current) return;
    const key = `file:${state.current.id}`;
    if (state.favorites.has(key)) state.favorites.delete(key);
    else state.favorites.add(key);
    persistPreferences();
    if (state.session?.user?.id) {
      const track = state.current;
      const id = await stableTrackId(track.title || track.name, track.artist, track.album);
      const favorite = state.favorites.has(key);
      if (favorite) state.favorites.add(id);
      else state.favorites.delete(id);
      void enqueueSyncChange(state.session.user.id, "favorite", id, { favorite })
        .then(() => synchronizeAccountNow())
        .catch((error) => toast(`Favorito salvo localmente; sincronização pendente: ${error.message}`));
    }
    renderPlayer();
  } else if (action === "clear-history") {
    state.history = [];
    localStorage.removeItem(accountStorageKey("history", state.session?.user?.id));
    render();
  } else if (action === "volume") {
    $("#volume-bar").hidden = !$("#volume-bar").hidden;
  } else if (action === "search") {
    setPage("search");
    $("#global-search")?.focus();
  }
}

document.addEventListener("click", async (event) => {
  const viewButton = event.target.closest("[data-view]");
  if (viewButton) {
    event.preventDefault();
    setPage(viewButton.dataset.view);
    return;
  }
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled) return;
  try {
    await handleAction(button);
  } catch (error) {
    const message = error instanceof Error ? error.message : "A operação não pôde ser concluída.";
    if (importDialog.open) {
      state.importError = message;
      importDialog.innerHTML = renderImportDialog();
    } else toast(message);
  }
});

document.addEventListener("submit", (event) => {
  if (event.target.id === "settings-form") event.preventDefault();
});

document.addEventListener("change", async (event) => {
  if (event.target.id === "audio-files") {
    const replaceLocal = state.replaceLocalOnNextSelection;
    state.replaceLocalOnNextSelection = false;
    try {
      await indexLocalFiles([...event.target.files], { replaceLocal });
      event.target.value = "";
    } catch (error) {
      toast(`Falha ao indexar arquivos: ${error.message}`);
    }
  }
});

document.addEventListener("cancel", (event) => {
  if (event.target.id === "audio-files") state.replaceLocalOnNextSelection = false;
}, true);

document.addEventListener("input", (event) => {
  if (event.target.id !== "global-search") return;
  const query = event.target.value.trim().toLocaleLowerCase("pt-BR");
  const results = state.library.filter((track) =>
    `${track.title} ${track.artist} ${track.album}`.toLocaleLowerCase("pt-BR").includes(query),
  );
  const playlistResults = state.playlists.flatMap((playlist) => playlist.tracks
    .filter((track) => `${track.title} ${track.artist} ${track.album}`.toLocaleLowerCase("pt-BR").includes(query))
    .map((track) => ({ ...track, playlistName: playlist.name })));
  const target = $("#search-results");
  if (target) target.innerHTML = results.length || playlistResults.length
    ? `<div class="section-heading"><h2>Na sua biblioteca (${results.length})</h2></div><div class="panel"><div class="track-list">${results.map((track, index) => trackRow(track, index)).join("")}</div></div>`
      + (playlistResults.length ? `<div class="section-heading"><h2>Nas playlists (${playlistResults.length})</h2></div><div class="panel"><div class="track-list">${playlistResults.slice(0, 100).map((track, index) => trackRow(track, index)).join("")}</div></div>` : "")
    : `<div class="empty-state" style="margin-top:15px"><strong>Nenhuma faixa encontrada</strong><p>Adicione arquivos ou busque em uma playlist importada.</p></div>`;
});

$("#seek-bar").addEventListener("input", () => {
  if (audio.duration) audio.currentTime = (Number($("#seek-bar").value) / 1000) * audio.duration;
  renderPlayer();
});
$("#volume-bar").addEventListener("input", () => {
  audio.volume = Number($("#volume-bar").value) / 100;
  persistPreferences();
  queuePlaybackSetting("volume", audio.volume);
});
audio.volume = 0.8;
audio.addEventListener("timeupdate", renderPlayer);
audio.addEventListener("play", renderPlayer);
audio.addEventListener("pause", renderPlayer);
audio.addEventListener("loadedmetadata", renderPlayer);
audio.addEventListener("ended", () => {
  if (state.repeat) {
    audio.currentTime = 0;
    void audio.play().catch((error) => toast(`Não foi possível repetir a música: ${error.message}`));
  } else void playNext(1).catch((error) => toast(error.message));
});
audio.addEventListener("error", () => {
  if (state.driveAudioUrl) URL.revokeObjectURL(state.driveAudioUrl);
  state.driveAudioUrl = "";
  $("#player-status").textContent = "Falha na reprodução";
  toast("O arquivo não pôde ser reproduzido. Verifique a conexão, a autorização ou o formato.");
});
importDialog.addEventListener("click", (event) => {
  if (event.target === importDialog) closeImport();
});
settingsDialog.addEventListener("click", (event) => {
  if (event.target === settingsDialog) closeSettings();
});

async function initialize() {
  try {
    state.library = await readLibraryIndex();
  } catch (error) {
    toast(`O índice local não pôde ser lido: ${error.message}`);
  }
  if (supabase) {
    try {
      state.session = await currentSession();
    } catch (error) {
      toast(`A sessão ASC não pôde ser restaurada: ${error.message}`);
    }
  }
  try {
    restoreAccountLocalState();
  } catch (error) {
    state.playlists = [];
    state.favorites = new Set();
    state.confirmedMatches = {};
    toast(`Preferências locais inválidas; uma lista vazia foi carregada: ${error.message}`);
  }
  try {
    state.history = JSON.parse(localStorage.getItem(accountStorageKey("history", state.session?.user?.id)) || "[]");
  } catch (error) {
    state.history = [];
    toast(`O histórico salvo não pôde ser lido: ${error.message}`);
  }
  if (supabase) {
    supabase.auth.onAuthStateChange((event, session) => {
      const previousOwner = state.session?.user?.id;
      switchAccountSession(session);
      if (event === "PASSWORD_RECOVERY") state.recoveryMode = true;
      render();
      if (settingsDialog.open) settingsDialog.innerHTML = getSettingsHtml();
      if (previousOwner !== session?.user?.id && session) void loadCloudData();
    });
  }
  render();
  void loadCloudData();
  try {
    state.spotifyConnected = await completeSpotifyAuth();
    if (state.spotifyConnected) {
      const pending = sessionStorage.getItem("asc.spotify.pendingPlaylist");
      sessionStorage.removeItem("asc.spotify.pendingPlaylist");
      if (pending) {
        const playlist = await importSpotifyPlaylist(pending);
        saveImportedPlaylist(playlist, playlist.name);
      } else toast("Spotify conectado para consultar playlists autorizadas.");
    }
  } catch (error) {
    toast(error.message);
    if (importDialog.open) {
      state.importError = `${error.message} Você ainda pode importar por CSV ou texto.`;
      importDialog.innerHTML = renderImportDialog();
      importDialog.showModal();
    }
  }
}

void initialize();
