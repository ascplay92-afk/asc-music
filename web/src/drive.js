import { readAudioMetadata } from "./metadata.js";
import { isUnchangedDriveFile } from "../../shared/library-index.js";

const DRIVE_API = "https://www.googleapis.com/drive/v3";
const DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder";
const AUDIO_MIME_TYPES = new Set([
  "audio/aac", "audio/flac", "audio/mp4", "audio/mpeg", "audio/ogg",
  "audio/wav", "audio/webm", "audio/x-flac", "audio/x-m4a", "audio/x-ms-wma",
]);
const API_FIELDS = "files(id,name,mimeType,size,modifiedTime,md5Checksum,version,parents,thumbnailLink,webViewLink)";
const CHANGE_FIELDS = `changes(file(${API_FIELDS}),fileId,removed),nextPageToken,newStartPageToken`;

function loadScript(url) {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector(`script[src="${url}"]`);
    if (existing?.dataset.loaded === "true") return resolve();
    const script = existing ?? document.createElement("script");
    script.src = url;
    script.async = true;
    script.onload = () => {
      script.dataset.loaded = "true";
      resolve();
    };
    script.onerror = () => reject(new Error(`Google library failed to load: ${new URL(url).hostname}`));
    if (!existing) document.head.append(script);
  });
}

function authorizedResponse(response) {
  if (response.status === 401) throw new Error("Google Drive authorization expired. Reconnect your account and retry.");
  if (response.status === 403) throw new Error("Google Drive denied this request. Check the read-only scope, folder permission, API key restriction, or quota.");
  if (response.status === 429) throw new Error("Google Drive rate limit reached. Retry the library verification later.");
  if (!response.ok) throw new Error(`Google Drive returned HTTP ${response.status}.`);
  return response;
}

export class DriveConnector {
  constructor({ clientId, apiKey }) {
    this.clientId = clientId.trim();
    this.apiKey = apiKey.trim();
    this.accessToken = "";
    this.tokenExpiresAt = 0;
    this.authorizedOnce = false;
    this.activeController = null;
  }

  configured() {
    return Boolean(this.clientId && this.apiKey);
  }

  async authorize() {
    if (!this.configured()) throw new Error("Add a Google OAuth client ID and restricted API key in Settings first.");
    await loadScript("https://accounts.google.com/gsi/client");
    const scope = "https://www.googleapis.com/auth/drive.readonly";
    await new Promise((resolve, reject) => {
      const client = google.accounts.oauth2.initTokenClient({
        client_id: this.clientId,
        scope,
        callback: (response) => {
          if (response.error || !response.access_token) {
            reject(new Error(response.error_description || response.error || "Google authorization failed."));
            return;
          }
          this.accessToken = response.access_token;
          this.tokenExpiresAt = Date.now() + Number(response.expires_in || 3600) * 1000;
          this.authorizedOnce = true;
          resolve();
        },
        error_callback: (error) => reject(new Error(error.message || "Google authorization failed.")),
      });
      client.requestAccessToken({ prompt: this.authorizedOnce ? "" : "consent" });
    });
  }

  async ensureAuthorized() {
    if (!this.accessToken || this.tokenExpiresAt < Date.now() + 60_000) await this.authorize();
  }

  async selectFolder() {
    await this.ensureAuthorized();
    await loadScript("https://apis.google.com/js/api.js");
    await new Promise((resolve, reject) => {
      if (google.picker) return resolve();
      google.load("picker", { callback: resolve, onerror: () => reject(new Error("Google Picker could not load.")) });
    });
    return new Promise((resolve, reject) => {
      const view = new google.picker.DocsView(google.picker.ViewId.FOLDERS)
        .setIncludeFolders(true)
        .setSelectFolderEnabled(true);
      const picker = new google.picker.PickerBuilder()
        .setDeveloperKey(this.apiKey)
        .setAppId(this.clientId.split("-")[0])
        .setOAuthToken(this.accessToken)
        .addView(view)
        .setTitle("Choose your music folder")
        .setCallback((data) => {
          if (data.action === google.picker.Action.PICKED) {
            const folder = data.docs?.[0];
            if (!folder?.id) {
              reject(new Error("Google Drive returned a selection without a folder ID."));
              return;
            }
            resolve({ id: folder.id, name: folder.name });
          } else if (data.action === google.picker.Action.CANCEL) {
            resolve(null);
          } else if (
            data.error
            || data.action === "error"
            || (google.picker.Action.ERROR && data.action === google.picker.Action.ERROR)
          ) {
            reject(new Error(data.error?.message || "Google Drive folder selection failed."));
          }
        })
        .build();
      picker.setVisible(true);
      if (!picker) reject(new Error("Google Drive folder picker could not be created."));
    });
  }

  async request(path, parameters = {}) {
    await this.ensureAuthorized();
    const url = new URL(`${DRIVE_API}/${path}`);
    const { signal, ...query } = parameters;
    for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
    const response = await fetch(url, {
      headers: { authorization: `Bearer ${this.accessToken}` },
      signal,
    });
    return this.checkedResponse(response).json();
  }

  checkedResponse(response) {
    if (response.status === 401) {
      this.accessToken = "";
      this.tokenExpiresAt = 0;
    }
    return authorizedResponse(response);
  }

  cancel() {
    this.activeController?.abort();
  }

  async enumerateFolder(folderId, collected = [], visited = new Set(), signal) {
    if (visited.has(folderId)) return collected;
    visited.add(folderId);
    let pageToken;
    do {
      const result = await this.request("files", {
        q: `'${folderId.replace(/'/g, "\\'")}' in parents and trashed = false`,
        fields: `${API_FIELDS},nextPageToken`,
        pageSize: "1000",
        orderBy: "folder,name",
        signal,
        ...(pageToken ? { pageToken } : {}),
      });
      for (const entry of result.files ?? []) {
        if (entry.mimeType === DRIVE_FOLDER_MIME) {
          await this.enumerateFolder(entry.id, collected, visited, signal);
        } else if (
          AUDIO_MIME_TYPES.has(entry.mimeType)
          || /\.(aac|flac|m4a|mp3|ogg|opus|wav|wma)$/i.test(entry.name)
        ) {
          collected.push(entry);
        }
      }
      pageToken = result.nextPageToken;
    } while (pageToken);
    return collected;
  }

  async getStartPageToken(signal) {
    const result = await this.request("changes/startPageToken", { spaces: "drive", signal });
    return result.startPageToken || "";
  }

  async listChanges(pageToken, signal) {
    const changes = [];
    let nextPageToken = pageToken;
    let newStartPageToken = "";
    do {
      const page = await this.request("changes", {
        pageToken: nextPageToken,
        spaces: "drive",
        includeRemoved: "true",
        restrictToMyDrive: "true",
        pageSize: "1000",
        fields: CHANGE_FIELDS,
        signal,
      });
      changes.push(...(page.changes || []));
      nextPageToken = page.nextPageToken || "";
      newStartPageToken = page.newStartPageToken || newStartPageToken;
    } while (nextPageToken);
    return { changes, newStartPageToken };
  }

  async isInsideFolder(file, folderId, cache = new Map(), signal) {
    if (!file?.id || file.trashed) return false;
    if (file.id === folderId) return false;
    if (cache.has(file.id)) return cache.get(file.id);
    const parents = [...(file.parents || [])];
    const visited = new Set();
    const visit = async (parentId) => {
      if (parentId === folderId) return true;
      if (!parentId || visited.has(parentId)) return false;
      visited.add(parentId);
      if (cache.has(parentId)) return cache.get(parentId);
      const parent = await this.request(`files/${encodeURIComponent(parentId)}`, {
        fields: "id,parents,mimeType,trashed",
        signal,
      });
      return (parent.parents || []).some((id) => visit(id));
    };
    const inside = (await Promise.all(parents.map((parentId) => visit(parentId)))).some(Boolean);
    cache.set(file.id, inside);
    return inside;
  }

  async indexFile(remote, previous) {
    const revision = String(remote.version ?? remote.md5Checksum ?? "");
    if (isUnchangedDriveFile(previous, { revision, modifiedTime: remote.modifiedTime })) {
      return { ...previous, checkedAt: new Date().toISOString() };
    }

    await this.ensureAuthorized();
    const response = await fetch(`${DRIVE_API}/files/${encodeURIComponent(remote.id)}?alt=media`, {
      headers: { authorization: `Bearer ${this.accessToken}` },
    });
    this.checkedResponse(response);
    const blob = await response.blob();
    const file = new File([blob], remote.name, {
      type: remote.mimeType || blob.type,
      lastModified: Date.parse(remote.modifiedTime) || Date.now(),
    });
    const metadata = await readAudioMetadata(file, remote.id);
    return {
      ...metadata,
      source: "drive",
      revision,
      modifiedTime: remote.modifiedTime,
      sizeBytes: Number(remote.size) || file.size,
      thumbnailUrl: remote.thumbnailLink || "",
      webViewUrl: remote.webViewLink || "",
      checkedAt: new Date().toISOString(),
    };
  }

  async verify(folder, currentIndex, onProgress = () => {}) {
    const controller = new AbortController();
    this.activeController = controller;
    const signal = controller.signal;
    const previousById = new Map(currentIndex.filter((file) => file.source === "drive").map((file) => [file.id, file]));
    try {
      let remoteFiles;
      let changedFiles = null;
      if (folder.changesToken) {
        try {
          const delta = await this.listChanges(folder.changesToken, signal);
          const cache = new Map();
          const structuralChange = delta.changes.some((change) => change.file?.mimeType === DRIVE_FOLDER_MIME);
          if (!structuralChange) {
            changedFiles = [];
            for (const change of delta.changes) {
              if (change.removed || change.file?.trashed) {
                previousById.delete(change.fileId);
                continue;
              }
              if (change.file && await this.isInsideFolder(change.file, folder.id, cache, signal)) changedFiles.push(change.file);
              else previousById.delete(change.fileId);
            }
            folder.changesToken = delta.newStartPageToken || folder.changesToken;
          }
        } catch (error) {
          if (error.name === "AbortError") throw error;
          changedFiles = null;
        }
      }
      if (!changedFiles) {
        const checkpoint = await this.getStartPageToken(signal);
        remoteFiles = await this.enumerateFolder(folder.id, [], new Set(), signal);
        changedFiles = remoteFiles;
        folder.changesToken = checkpoint;
      }
      const next = [];
      const concurrency = 3;
      for (let offset = 0; offset < changedFiles.length; offset += concurrency) {
        const batch = changedFiles.slice(offset, offset + concurrency);
        const previousBatch = batch.map((remote) => previousById.get(remote.id));
        batch.forEach((remote) => previousById.delete(remote.id));
        const indexed = await Promise.all(batch.map((remote, index) => this.indexFile(remote, previousBatch[index])));
        next.push(...indexed);
        indexed.forEach((file, index) => {
          onProgress({ current: offset + index + 1, total: changedFiles.length, name: batch[index].name });
        });
      }
      if (remoteFiles) {
        return [...currentIndex.filter((file) => file.source !== "drive"), ...next];
      }
      return [
        ...currentIndex.filter((file) => file.source !== "drive"),
        ...[...previousById.values(), ...next],
      ];
    } finally {
      if (this.activeController === controller) this.activeController = null;
    }
  }

  async audioUrl(fileId) {
    await this.ensureAuthorized();
    const response = await fetch(`${DRIVE_API}/files/${encodeURIComponent(fileId)}?alt=media`, {
      headers: { authorization: `Bearer ${this.accessToken}` },
    });
    this.checkedResponse(response);
    return URL.createObjectURL(await response.blob());
  }
}

export function getDriveConfiguration() {
  return {
    clientId: localStorage.getItem("asc.google.clientId") || "",
    apiKey: localStorage.getItem("asc.google.apiKey") || "",
  };
}
