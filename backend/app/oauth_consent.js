import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";

const root = document.querySelector("#app");
const loginForm = document.querySelector("#login");
const consent = document.querySelector("#consent");
const errorBox = document.querySelector("#error");
const loginButton = document.querySelector("#login-button");
const approveButton = document.querySelector("#approve");
const denyButton = document.querySelector("#deny");
const authorizationId = new URLSearchParams(window.location.search).get("authorization_id");
const supabase = createClient(root.dataset.supabaseUrl, root.dataset.publishableKey, {
  auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
});

function safeRedirect(value) {
  const url = new URL(value);
  if (url.href !== "com.grm.rotagrm:/oauth2redirect" &&
      !(url.protocol === "com.grm.rotagrm:" && url.pathname === "/oauth2redirect" && !url.host)) {
    throw new Error("Redirecionamento não autorizado.");
  }
  return url.href;
}

function showError(message) {
  errorBox.textContent = message || "Não foi possível concluir a autorização.";
  errorBox.style.display = "block";
}

async function loadAuthorization() {
  if (!authorizationId) {
    showError("Solicitação de autorização ausente ou inválida.");
    return;
  }

  const { data, error } = await supabase.auth.oauth.getAuthorizationDetails(authorizationId);
  if (error || !data) {
    showError(error?.message);
    return;
  }

  if (!("authorization_id" in data)) {
    const redirect = safeRedirect(data.redirect_url);
    await supabase.auth.signOut({ scope: "local" });
    window.location.replace(redirect);
    return;
  }

  document.querySelector("#client-name").textContent = data.client?.name || "Status Operacional";
  document.querySelector("#redirect-uri").textContent = data.redirect_uri || "";
  const scopes = String(data.scope || "").split(/\s+/).filter(Boolean);
  const scopeList = document.querySelector("#scopes");
  scopeList.replaceChildren();
  for (const scope of scopes) {
    const item = document.createElement("li");
    item.textContent = scope;
    scopeList.append(item);
  }

  loginForm.style.display = "none";
  consent.style.display = "block";
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  errorBox.style.display = "none";
  loginButton.disabled = true;
  const form = new FormData(loginForm);
  try {
  const { error } = await supabase.auth.signInWithPassword({
    email: String(form.get("email") || "").trim(),
    password: String(form.get("password") || ""),
  });
  loginForm.reset();
  loginButton.disabled = false;
  if (error) {
    showError("Login inválido ou conta não autorizada.");
    return;
  }
  await loadAuthorization();
  } catch {
    showError("Não foi possível concluir o login. Tente novamente.");
  } finally {
    loginForm.reset();
    loginButton.disabled = false;
  }
});

async function decide(approved) {
  approveButton.disabled = true;
  denyButton.disabled = true;
  errorBox.style.display = "none";
  const method = approved ? "approveAuthorization" : "denyAuthorization";
  const { data, error } = await supabase.auth.oauth[method](authorizationId);
  if (error || !data?.redirect_url) {
    approveButton.disabled = false;
    denyButton.disabled = false;
    showError(error?.message);
    return;
  }
  const redirect = safeRedirect(data.redirect_url);
  // End the browser login session; the approved mobile OAuth session is distinct.
  await supabase.auth.signOut({ scope: "local" });
  window.location.assign(redirect);
}

function decisionFailed() {
  approveButton.disabled = false;
  denyButton.disabled = false;
  showError("Não foi possível concluir a autorização. Tente novamente.");
}
approveButton.addEventListener("click", () => decide(true).catch(decisionFailed));
denyButton.addEventListener("click", () => decide(false).catch(decisionFailed));

const { data: sessionData, error: sessionError } = await supabase.auth.getSession();
if (sessionError) showError(sessionError.message);
else if (sessionData.session) await loadAuthorization();
else loginForm.style.display = "block";
