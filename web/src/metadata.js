import { parseBlob } from "music-metadata";

function stripExtension(name) {
  return String(name).replace(/\.[^.]+$/, "");
}

async function imageDataUri(picture) {
  if (!picture?.data || picture.data.byteLength > 1024 * 1024) return "";
  const blob = new Blob([picture.data], { type: picture.format || "image/jpeg" });
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("Could not read album artwork."));
    reader.readAsDataURL(blob);
  });
}

export async function readAudioMetadata(file, id = crypto.randomUUID()) {
  try {
    const metadata = await parseBlob(file, { duration: true });
    const common = metadata.common;
    const picture = common.picture?.[0];
    return {
      id,
      source: "local",
      name: file.name,
      title: common.title || stripExtension(file.name),
      artist: common.artist || common.artists?.[0] || "",
      artists: common.artists || (common.artist ? [common.artist] : []),
      album: common.album || "",
      durationSeconds: metadata.format.duration || 0,
      format: metadata.format.container || file.type || file.name.split(".").at(-1)?.toLowerCase() || "",
      sizeBytes: file.size,
      revision: `${file.size}:${file.lastModified}`,
      isrc: common.isrc || "",
      coverArt: await imageDataUri(picture),
      metadataError: "",
      modifiedTime: new Date(file.lastModified).toISOString(),
    };
  } catch (error) {
    return {
      id,
      source: "local",
      name: file.name,
      title: stripExtension(file.name),
      artist: "",
      artists: [],
      album: "",
      durationSeconds: 0,
      format: file.type || file.name.split(".").at(-1)?.toLowerCase() || "",
      sizeBytes: file.size,
      revision: `${file.size}:${file.lastModified}`,
      isrc: "",
      coverArt: "",
      metadataError: error instanceof Error ? error.message : "Audio metadata could not be read.",
      modifiedTime: new Date(file.lastModified).toISOString(),
    };
  }
}
