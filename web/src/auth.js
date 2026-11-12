import { createClient } from "@supabase/supabase-js";

const supabaseUrl = (import.meta.env.VITE_SUPABASE_URL || "").replace(/\/$/, "");
const supabasePublishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY
  || import.meta.env.VITE_SUPABASE_ANON_KEY
  || "";
const validUrl = (() => {
  try {
    const url = new URL(supabaseUrl);
    return url.protocol === "https:" || (url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname));
  } catch {
    return false;
  }
})();

export const authConfigured = Boolean(validUrl && supabasePublishableKey);
export const authConfigurationError = supabaseUrl && !validUrl
  ? "A URL do Supabase deve usar HTTPS (HTTP só é permitido em localhost)."
  : "";

export const supabase = authConfigured
  ? createClient(supabaseUrl, supabasePublishableKey, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
      flowType: "pkce",
    },
  })
  : null;

export async function registerAccount(email, password) {
  if (!supabase) throw new Error(authConfigurationError || "Configure VITE_SUPABASE_URL e VITE_SUPABASE_PUBLISHABLE_KEY no build.");
  const { data, error } = await supabase.auth.signUp({
    email,
    password,
    options: { emailRedirectTo: safeAuthRedirect() },
  });
  if (error) throw error;
  return data;
}

export async function signIn(email, password) {
  if (!supabase) throw new Error(authConfigurationError || "Configure VITE_SUPABASE_URL e VITE_SUPABASE_PUBLISHABLE_KEY no build.");
  const { data, error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) throw error;
  return data;
}

export async function signOut() {
  if (!supabase) throw new Error("A autenticação compartilhada não está configurada.");
  const { error } = await supabase.auth.signOut();
  if (error) throw error;
}

export async function recoverAccount(email) {
  if (!supabase) throw new Error(authConfigurationError || "Configure VITE_SUPABASE_URL e VITE_SUPABASE_PUBLISHABLE_KEY no build.");
  const { error } = await supabase.auth.resetPasswordForEmail(email, { redirectTo: safeAuthRedirect() });
  if (error) throw error;
}

export async function completePasswordRecovery(password) {
  if (!supabase) throw new Error("A autenticação compartilhada não está configurada.");
  const { error } = await supabase.auth.updateUser({ password });
  if (error) throw error;
}

export async function currentSession() {
  if (!supabase) return null;
  const { data, error } = await supabase.auth.getSession();
  if (error) throw error;
  return data.session;
}

function safeAuthRedirect() {
  const url = new URL("/", window.location.origin);
  if (url.protocol !== "https:" && !["localhost", "127.0.0.1"].includes(url.hostname)) {
    throw new Error("Os redirecionamentos de autenticação exigem HTTPS.");
  }
  return url.toString();
}
