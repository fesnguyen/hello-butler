import QRCode from "qrcode";

function escape(value: string) {
  return value.replace(
    /[&<>"']/g,
    (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]!,
  );
}
export function page(title: string, content: string, error = false) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} — Hello Butler Admin</title>
<style> *{box-sizing:border-box} body{margin:0;background:#f5f7fb;color:#1d2939;font:16px/1.5 system-ui,sans-serif} main{max-width:480px;margin:8vh auto;padding:32px;background:white;border:1px solid #d9e1ed;border-radius:12px} h1{font-size:26px;line-height:1.25} .brand{color:#536579;font-size:13px} label{display:block;margin:18px 0 6px} input,select,button{font:inherit;width:100%;min-height:44px;border:1px solid #acb9c9;border-radius:6px;padding:10px} button{margin-top:22px;background:#3040c4;color:white;cursor:pointer;border:0} a{color:#3040c4} :focus-visible{outline:3px solid #4059da;outline-offset:3px} .error{padding:12px;background:#fff1f0;color:#8b1b19;border-radius:6px} .qr{display:block;width:min(100%,280px);height:auto;margin:20px auto} .codes{padding-left:0;list-style:none;font:13px/2 monospace;overflow-wrap:anywhere} .actions{display:flex;flex-wrap:wrap;gap:16px;margin-top:24px} @media(max-width:520px){main{margin:20px 16px;padding:24px}}
</style></head><body><main><p class="brand">HELLO BUTLER · ADMIN</p><h1>${escape(title)}</h1>${error ? '<p class="error" role="alert">Unable to verify authentication. Please try again.</p>' : ""}${content}</main></body></html>`;
}
export function csrfField(token: string) {
  return `<input type="hidden" name="csrf" value="${escape(token)}">`;
}
export function loginPage(csrf: string, root: string, error = false) {
  return page(
    "Sign in",
    `<p>Enter your administrator credentials to continue.</p><form method="post" action="${root}/login">${csrfField(csrf)}<label for="email">Email</label><input id="email" type="email" name="email" autocomplete="username" maxlength="254" required><label for="password">Password</label><input id="password" type="password" name="password" autocomplete="current-password" required><button>Continue</button></form><p><a href="${root}/security">Manage two-step verification</a></p>`,
    error,
  );
}
export function factorFields() {
  return `<label for="kind">Verification method</label><select id="kind" name="kind"><option value="totp">Authenticator code</option><option value="recovery">Recovery code</option></select><label for="code">Verification code</label><input id="code" name="code" autocomplete="one-time-code" maxlength="35" required>`;
}
export function verifyPage(csrf: string, root: string, error = false) {
  return page(
    "Verify your sign-in",
    `<p>Enter the six-digit code from your authenticator, or select Recovery code.</p><form method="post" action="${root}/verify">${csrfField(csrf)}${factorFields()}<button>Verify and sign in</button></form><p><a href="${root}/logout">Cancel sign-in</a></p>`,
    error,
  );
}
export function setupPage(csrf: string, root: string, error = false) {
  return page(
    "Authorize setup",
    `<p>First-time registration requires a single-use setup token from the local operator command. No dashboard access is granted yet.</p><form method="post" action="${root}/setup">${csrfField(csrf)}<label for="token">Setup authorization token</label><input id="token" type="password" name="token" autocomplete="off" maxlength="64" required><button>Register authenticator</button></form><p><a href="${root}/logout">Cancel</a></p>`,
    error,
  );
}
export async function enrollmentPage(csrf: string, root: string, uri: string, replacement: boolean, error = false) {
  const image = await QRCode.toDataURL(uri, {
    errorCorrectionLevel: "M",
    width: 280,
    margin: 4,
  });
  const path = replacement ? "replace" : "enroll";
  return page(
    replacement ? "Replace authenticator" : "Register authenticator",
    `<p>In Google Authenticator or another TOTP app, choose Add account → Scan a QR code. Keep this QR code private.</p><img class="qr" src="${image}" alt="Scan to register Hello Butler Admin"><p>Enter the new authenticator’s current six-digit code to activate it.</p><form method="post" action="${root}/${path}">${csrfField(csrf)}<label for="code">Six-digit code</label><input id="code" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required><button>Confirm authenticator</button></form><p><a href="${root}/logout">Cancel</a></p>`,
    error,
  );
}
export function recoveryPage(root: string, codes: string[]) {
  return page(
    "Save your recovery codes",
    `<p>These codes are shown once. Store them securely outside this browser. Each code works once, together with your password. If this page is lost, regenerate codes using a fresh authenticator code.</p><ul class="codes">${codes.map((code) => `<li>${escape(code)}</li>`).join("")}</ul><p><a href="${root}">Continue to dashboard</a></p><p><a href="${root}/security">Manage two-step verification</a></p>`,
  );
}
export function securityPage(csrf: string, root: string, error = false) {
  return page(
    "Two-step verification",
    `<p>Each change requires your password and an existing authenticator or recovery code. Wait for a new authenticator code if you just used one.</p><form method="post" action="${root}/security"><label for="operation">Action</label><select id="operation" name="operation"><option value="replace">Replace authenticator</option><option value="recovery">Regenerate recovery codes</option></select>${csrfField(csrf)}<label for="password">Password</label><input id="password" type="password" name="password" autocomplete="current-password" required>${factorFields()}<button>Verify and continue</button></form><p><a href="${root}">Back to dashboard</a></p>`,
    error,
  );
}
export function logoutPage(csrf: string, root: string) {
  return page(
    "Sign out",
    `<form method="post" action="${root}/logout">${csrfField(csrf)}<button>Sign out and cancel pending verification</button></form>`,
  );
}
