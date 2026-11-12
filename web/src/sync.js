const DATABASE = "asc-music-sync-v1";
const VERSION = 1;
const OUTBOX = "outbox";
const ENTITY_VERSIONS = "entityVersions";
const METADATA = "metadata";

export async function stableTrackId(title, artist, album = "") {
  const normalize = (value) => String(value || "")
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
  const data = new TextEncoder().encode([normalize(title), normalize(artist), normalize(album)].join("\u001f"));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  const binary = String.fromCharCode(...digest);
  return `track-${btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`;
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(OUTBOX)) database.createObjectStore(OUTBOX, { keyPath: "operationId" });
      if (!database.objectStoreNames.contains(ENTITY_VERSIONS)) database.createObjectStore(ENTITY_VERSIONS, { keyPath: "key" });
      if (!database.objectStoreNames.contains(METADATA)) database.createObjectStore(METADATA, { keyPath: "key" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Não foi possível abrir o armazenamento de sincronização."));
  });
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Falha no armazenamento de sincronização."));
  });
}

async function transaction(stores, mode, action) {
  const database = await openDatabase();
  try {
    const tx = database.transaction(stores, mode);
    const result = await action(tx);
    await new Promise((resolve, reject) => {
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error || new Error("Falha ao persistir a fila de sincronização."));
      tx.onabort = () => reject(tx.error || new Error("A transação de sincronização foi cancelada."));
    });
    return result;
  } finally {
    database.close();
  }
}

function entityKey(ownerId, type, id) {
  return `${ownerId}:${type}:${id}`;
}

export async function enqueueSyncChange(ownerId, type, id, payload, deleted = false) {
  if (!ownerId) throw new Error("Uma identidade autenticada é necessária para enfileirar alterações.");
  if (!["playlist", "favorite", "history", "library", "setting"].includes(type) || typeof id !== "string" || !id) {
    throw new Error("A alteração de sincronização tem identidade inválida.");
  }
  return transaction([OUTBOX, ENTITY_VERSIONS], "readwrite", async (tx) => {
    const outbox = tx.objectStore(OUTBOX);
    const versions = tx.objectStore(ENTITY_VERSIONS);
    const key = entityKey(ownerId, type, id);
    const [queued, savedVersion] = await Promise.all([
      requestResult(outbox.getAll()),
      requestResult(versions.get(key)),
    ]);
    const sameEntity = queued.filter((change) => change.ownerId === ownerId && change.type === type && change.id === id)
      .sort((left, right) => left.createdAt - right.createdAt);
    const serialized = JSON.stringify({ payload, deleted });
    if (sameEntity.at(-1)?.serialized === serialized) return;
    const change = {
      operationId: crypto.randomUUID(),
      ownerId,
      type,
      id,
      baseVersion: (savedVersion?.version ?? 0) + sameEntity.length,
      payload,
      deleted,
      serialized,
      createdAt: Date.now(),
    };
    await requestResult(outbox.put(change));
  });
}

export async function clearSyncQueue(ownerId) {
  if (!ownerId) return;
  return transaction([OUTBOX], "readwrite", async (tx) => {
    const store = tx.objectStore(OUTBOX);
    const rows = await requestResult(store.getAll());
    await Promise.all(rows.filter((row) => row.ownerId === ownerId).map((row) => requestResult(store.delete(row.operationId))));
  });
}

async function ownerRows(storeName, ownerId) {
  return transaction([storeName], "readonly", async (tx) => {
    const rows = await requestResult(tx.objectStore(storeName).getAll());
    return rows.filter((row) => row.ownerId === ownerId);
  });
}

async function setVersion(ownerId, type, id, version) {
  return transaction([ENTITY_VERSIONS], "readwrite", (tx) => requestResult(tx.objectStore(ENTITY_VERSIONS).put({
    key: entityKey(ownerId, type, id),
    ownerId,
    type,
    id,
    version,
  })));
}

async function getCursor(ownerId) {
  return transaction([METADATA], "readonly", async (tx) => {
    const row = await requestResult(tx.objectStore(METADATA).get(ownerId));
    return row?.cursor ?? 0;
  });
}

async function setCursor(ownerId, cursor) {
  return transaction([METADATA], "readwrite", (tx) => requestResult(tx.objectStore(METADATA).put({
    key: ownerId,
    ownerId,
    cursor,
  })));
}

async function acknowledgeChange(ownerId, change, version) {
  return transaction([OUTBOX, ENTITY_VERSIONS], "readwrite", (tx) => {
    tx.objectStore(OUTBOX).delete(change.operationId);
    return requestResult(tx.objectStore(ENTITY_VERSIONS).put({
      key: entityKey(ownerId, change.type, change.id),
      ownerId,
      type: change.type,
      id: change.id,
      version,
    }));
  });
}

export async function synchronizeAccount(ownerId, request, applyChange, getCurrentOwner = () => ownerId) {
  if (!ownerId) throw new Error("Entre na conta ASC Music para sincronizar.");
  const assertOwner = () => {
    if (getCurrentOwner() !== ownerId) {
      throw new Error("A conta mudou durante a sincronização; nenhuma alteração adicional foi aplicada.");
    }
  };
  assertOwner();
  const pending = (await ownerRows(OUTBOX, ownerId)).sort((left, right) => left.createdAt - right.createdAt);
  for (let offset = 0; offset < pending.length; offset += 100) {
    assertOwner();
    const batch = pending.slice(offset, offset + 100);
    const response = await request("/sync", {
      method: "POST",
      body: { changes: batch.map(({ operationId, type, id, baseVersion, payload, deleted }) => ({
        operationId,
        type,
        id,
        baseVersion,
        payload,
        deleted,
      })) },
    });
    if (!Array.isArray(response.results) || response.results.length !== batch.length) {
      throw new Error("O Worker não confirmou todas as alterações enfileiradas.");
    }
    for (const result of response.results) {
      const change = batch.find((item) => item.operationId === result.operationId);
      if (!change) throw new Error("O Worker retornou uma operação desconhecida.");
      if (result.status === "conflict") {
        const error = new Error(`Conflito de sincronização em ${change.type} ${change.id}; os dados locais foram preservados.`);
        error.conflict = result.current;
        error.change = change;
        throw error;
      }
      if (result.status !== "applied" || !Number.isSafeInteger(result.version)) {
        throw new Error("O Worker retornou um resultado de sincronização inválido.");
      }
      assertOwner();
      await acknowledgeChange(ownerId, change, result.version);
    }
  }

  let cursor = await getCursor(ownerId);
  let pageCount = 0;
  while (true) {
    assertOwner();
    if (++pageCount > 100) throw new Error("A sincronização excedeu o limite de páginas; tente novamente.");
    const page = await request(`/sync?cursor=${cursor}&limit=200`);
    if (!Array.isArray(page.changes) || !Number.isSafeInteger(page.cursor) || page.cursor < cursor || typeof page.hasMore !== "boolean") {
      throw new Error("O Worker retornou uma página de sincronização inválida.");
    }
    const appliedVersions = [];
    for (const change of page.changes) {
      if (!change.id || !change.type || !Number.isSafeInteger(change.version) || change.version < 1) {
        throw new Error("O Worker retornou uma alteração de sincronização inválida.");
      }
      assertOwner();
      await applyChange(change);
      appliedVersions.push(change);
    }
    assertOwner();
    await transaction([ENTITY_VERSIONS, METADATA], "readwrite", async (tx) => {
      for (const change of appliedVersions) {
        await requestResult(tx.objectStore(ENTITY_VERSIONS).put({
          key: entityKey(ownerId, change.type, change.id),
          ownerId,
          type: change.type,
          id: change.id,
          version: change.version,
        }));
      }
      await requestResult(tx.objectStore(METADATA).put({
        key: ownerId,
        ownerId,
        cursor: page.cursor,
      }));
    });
    cursor = page.cursor;
    if (!page.hasMore) break;
  }
  return { pending: (await ownerRows(OUTBOX, ownerId)).length, cursor };
}

export async function resolveSyncConflict(ownerId, change, current, strategy, applyChange, getCurrentOwner = () => ownerId) {
  if (!ownerId || !change || !current || current.type !== change.type || current.id !== change.id) {
    throw new Error("O conflito de sincronização não possui uma identidade válida.");
  }
  if (getCurrentOwner() !== ownerId) throw new Error("A conta mudou durante a resolução do conflito.");
  if (strategy === "remote") {
    await applyChange(current);
    if (getCurrentOwner() !== ownerId) throw new Error("A conta mudou durante a resolução do conflito.");
    await acknowledgeChange(ownerId, change, current.version);
    return;
  }
  if (strategy !== "local" || !Number.isSafeInteger(current.version)) {
    throw new Error("A estratégia de resolução de conflito não é suportada.");
  }
  return transaction([OUTBOX], "readwrite", (tx) => requestResult(tx.objectStore(OUTBOX).put({
    ...change,
    operationId: crypto.randomUUID(),
    baseVersion: current.version,
    createdAt: Date.now(),
  })));
}
