import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const root = document.querySelector("#app");
const loginForm = document.querySelector("#login");
const consent = document.querySelector("#consent");
const errorBox = document.querySelector("#error");
const loginButton = document.querySelector("#login-button");
const approveButton = document.querySelector("#approve");
const denyButton = document.querySelector("#deny");
const authorizationId = new URLSearchParams(window.location.search).get("authorization_id");
const supabase = createClient(root.dataset.supabaseUrl, root.dataset.publishableKey);

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
    window.location.replace(data.redirect_url);
    return;
  }

  document.querySelector("#client-name").textContent = data.client?.name || "ROTA GRM";
  document.querySelector("#redirect-uri").textContent = data.redirect_uri || "";
  const scopes = String(data.scope || "").split(/\s+/).filter(Boolean);
  const scopeList = document.querySelector("#scopes");
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
  const { error } = await supabase.auth.signInWithPassword({
    email: String(form.get("email") || "").trim(),
    password: String(form.get("password") || ""),
  });
  loginButton.disabled = false;
  if (error) {
    showError("Login inválido ou conta não autorizada.");
    return;
  }
  await loadAuthorization();
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
  window.location.assign(data.redirect_url);
}

approveButton.addEventListener("click", () => decide(true));
denyButton.addEventListener("click", () => decide(false));

const { data: sessionData, error: sessionError } = await supabase.auth.getSession();
if (sessionError) showError(sessionError.message);
else if (sessionData.session) await loadAuthorization();
else loginForm.style.display = "block";
