import AdminJS, { type CurrentAdmin } from "adminjs";
import AdminJSExpress from "@adminjs/express";
import express, { type ErrorRequestHandler, type Request, type RequestHandler, type Response } from "express";
import session from "express-session";
import { rateLimit } from "express-rate-limit";
import { randomBytes } from "node:crypto";
import { createAuthenticator } from "./auth.js";
import { COOKIE_NAME, COOKIE_PATH, PENDING_MAX_AGE, SESSION_MAX_AGE, type AdminConfig } from "./config.js";
import { digest, equalToken } from "./totp.js";
import { AuthenticationLocked, type TwoFactorStore } from "./two-factor.js";
import {
  enrollmentPage,
  loginPage,
  logoutPage,
  page,
  recoveryPage,
  securityPage,
  setupPage,
  verifyPage,
} from "./auth-pages.js";

declare module "express-session" {
  interface SessionData {
    authenticatedAt?: number;
    adminUser?: CurrentAdmin;
    factorVerifiedAt?: number;
    authVersion?: number;
    credentialFingerprint?: string;
    csrf?: string;
    pending?: {
      id: string;
      kind: "setup" | "enroll" | "login";
      expiresAt: number;
    };
    replacement?: { id: string; expiresAt: number };
  }
}

export function createAdminApp(admin: AdminJS, config: AdminConfig, store: session.Store, factors: TwoFactorStore) {
  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", config.trustProxy);
  const router = express.Router();
  const root = admin.options.rootPath;
  const fingerprint = digest(`${config.email.toLowerCase()}:${config.passwordHash}`);
  const authenticate = createAuthenticator(config);
  const now = factors.now;
  const wrap =
    (handler: (req: Request, res: Response) => Promise<void>): RequestHandler =>
    (req, res, next) => {
      void handler(req, res).catch(next);
    };
  const save = (req: Request) =>
    new Promise<void>((resolve, reject) => req.session.save((error) => (error ? reject(error) : resolve())));
  const rotate = (req: Request) =>
    new Promise<void>((resolve, reject) => req.session.regenerate((error) => (error ? reject(error) : resolve())));
  const csrf = (req: Request) => (req.session.csrf ??= randomBytes(32).toString("hex"));

  router.use((_req, res, next) => {
    res.set({
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "X-Frame-Options": "DENY",
    });
    next();
  });
  const loginLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    limit: 10,
    standardHeaders: "draft-8",
    legacyHeaders: false,
    message: "Unable to verify authentication. Try again later.",
  });
  const factorLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    limit: 30,
    standardHeaders: "draft-8",
    legacyHeaders: false,
    message: "Unable to verify authentication. Try again later.",
  });
  router.post("/login", loginLimiter);
  router.post(["/verify", "/setup", "/enroll", "/replace", "/security"], factorLimiter);
  const sessionOptions: session.SessionOptions = {
    name: COOKIE_NAME,
    secret: config.sessionSecret,
    store,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      sameSite: "lax",
      secure: config.secureCookies,
      path: COOKIE_PATH,
      maxAge: SESSION_MAX_AGE,
    },
  };
  router.use(session(sessionOptions));

  async function destroy(req: Request, res: Response) {
    if (req.session.pending) await factors.cancel(req.session.pending.id);
    if (req.session.replacement) await factors.cancel(req.session.replacement.id);
    await new Promise<void>((resolve, reject) => req.session.destroy((error) => (error ? reject(error) : resolve())));
    res.clearCookie(COOKIE_NAME, {
      path: COOKIE_PATH,
      httpOnly: true,
      sameSite: "lax",
      secure: config.secureCookies,
    });
  }
  router.use((req, res, next) => {
    void (async () => {
      if (req.session.pending && req.session.pending.expiresAt <= now()) {
        await destroy(req, res);
        res.redirect(admin.options.loginPath);
        return;
      }
      if (
        req.session.adminUser &&
        (!req.session.factorVerifiedAt ||
          !req.session.authenticatedAt ||
          now() - req.session.authenticatedAt >= SESSION_MAX_AGE ||
          req.session.credentialFingerprint !== fingerprint)
      ) {
        await destroy(req, res);
        res.redirect(admin.options.loginPath);
        return;
      }
      next();
    })().catch(next);
  });

  const html = (res: Response, content: string, status = 200) => {
    res.set(
      "Content-Security-Policy",
      "default-src 'none'; img-src data:; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    );
    res.status(status).type("html").send(content);
  };
  const form: RequestHandler[] = [
    (req, res, next) => {
      const length = req.get("content-length");
      if (
        !req.is("application/x-www-form-urlencoded") ||
        req.get("transfer-encoding") ||
        !length ||
        !/^\d+$/.test(length)
      ) {
        res.status(400).send("Invalid authentication request.");
        return;
      }
      if (Number(length) > 4096) {
        res.status(413).send("Authentication request too large.");
        return;
      }
      next();
    },
    express.urlencoded({ extended: false, limit: 4096, parameterLimit: 10 }),
    (req, res, next) => {
      if (!req.session.csrf || !equalToken(req.body.csrf, req.session.csrf)) {
        html(res, page("Unable to continue", "<p>Reload the sign-in page and try again.</p>"), 403);
        return;
      }
      next();
    },
  ];
  async function complete(req: Request, version: number) {
    await rotate(req);
    req.session.adminUser = { email: config.email };
    req.session.authenticatedAt = now();
    req.session.factorVerifiedAt = now();
    req.session.authVersion = version;
    req.session.credentialFingerprint = fingerprint;
    req.session.cookie.maxAge = SESSION_MAX_AGE;
    csrf(req);
    await save(req);
  }
  const requireAdmin: RequestHandler = (req, res, next) => {
    void (async () => {
      if (
        !req.session.adminUser ||
        req.session.adminUser.email !== config.email ||
        !req.session.factorVerifiedAt ||
        req.session.authVersion === undefined ||
        req.session.credentialFingerprint !== fingerprint
      ) {
        res.redirect(admin.options.loginPath);
        return;
      }
      const state = await factors.status();
      if (!state.active || state.version !== req.session.authVersion) {
        await destroy(req, res);
        res.redirect(admin.options.loginPath);
        return;
      }
      next();
    })().catch(next);
  };
  router.get("/login", (req, res) => html(res, loginPage(csrf(req), root)));
  router.post(
    "/login",
    form,
    wrap(async (req, res) => {
      await factors.passwordAllowed();
      const identity = await authenticate(req.body.email, req.body.password);
      await factors.passwordResult(Boolean(identity));
      if (!identity) {
        html(res, loginPage(csrf(req), root, true), 401);
        return;
      }
      if (req.session.pending) await factors.cancel(req.session.pending.id);
      if (req.session.replacement) await factors.cancel(req.session.replacement.id);
      await rotate(req);
      const pending = await factors.beginLogin();
      req.session.pending = {
        id: pending.id,
        kind: pending.kind === "setup" ? "setup" : "login",
        expiresAt: Number(pending.expires_at),
      };
      req.session.credentialFingerprint = fingerprint;
      req.session.cookie.maxAge = PENDING_MAX_AGE;
      csrf(req);
      await save(req);
      res.redirect(`${root}/${pending.kind === "setup" ? "setup" : "verify"}`);
    }),
  );
  router.get("/verify", (req, res) => {
    if (req.session.pending?.kind !== "login") {
      res.redirect(admin.options.loginPath);
      return;
    }
    html(res, verifyPage(csrf(req), root));
  });
  router.post(
    "/verify",
    form,
    wrap(async (req, res) => {
      const pending = req.session.pending;
      if (
        pending?.kind !== "login" ||
        req.session.credentialFingerprint !== fingerprint ||
        !["totp", "recovery"].includes(req.body.kind)
      ) {
        html(res, verifyPage(csrf(req), root, true), 401);
        return;
      }
      const version = await factors.verifyLogin(pending.id, req.body.code, req.body.kind);
      if (version === null) {
        html(res, verifyPage(csrf(req), root, true), 401);
        return;
      }
      await complete(req, version);
      res.redirect(root);
    }),
  );
  router.get("/setup", (req, res) => {
    if (req.session.pending?.kind !== "setup") {
      res.redirect(admin.options.loginPath);
      return;
    }
    html(res, setupPage(csrf(req), root));
  });
  router.post(
    "/setup",
    form,
    wrap(async (req, res) => {
      const pending = req.session.pending;
      if (
        pending?.kind !== "setup" ||
        req.session.credentialFingerprint !== fingerprint ||
        !(await factors.authorizeSetup(pending.id, req.body.token))
      ) {
        html(res, setupPage(csrf(req), root, true), 401);
        return;
      }
      pending.kind = "enroll";
      await save(req);
      res.redirect(`${root}/enroll`);
    }),
  );
  router.get(
    "/enroll",
    wrap(async (req, res) => {
      const pending = req.session.pending;
      const uri = pending?.kind === "enroll" ? await factors.preview(pending.id) : null;
      if (!uri || req.session.credentialFingerprint !== fingerprint) {
        res.redirect(admin.options.loginPath);
        return;
      }
      html(res, await enrollmentPage(csrf(req), root, uri, false));
    }),
  );
  router.post(
    "/enroll",
    form,
    wrap(async (req, res) => {
      const pending = req.session.pending;
      const result =
        pending?.kind === "enroll" && req.session.credentialFingerprint === fingerprint
          ? await factors.confirmEnrollment(pending.id, req.body.code)
          : null;
      if (!result) {
        const uri = pending?.kind === "enroll" ? await factors.preview(pending.id) : null;
        html(
          res,
          uri
            ? await enrollmentPage(csrf(req), root, uri, false, true)
            : page("Unable to verify", `<p><a href="${root}/login">Start again</a></p>`),
          401,
        );
        return;
      }
      await complete(req, result.version);
      html(res, recoveryPage(root, result.codes));
    }),
  );
  router.get("/logout", (req, res) => html(res, logoutPage(csrf(req), root)));
  router.post(
    "/logout",
    form,
    wrap(async (req, res) => {
      await destroy(req, res);
      res.redirect(admin.options.loginPath);
    }),
  );

  // Everything below, including AdminJS's own login handler, assets and APIs, requires both factors.
  router.use(requireAdmin);
  router.get("/security", (req, res) => html(res, securityPage(csrf(req), root)));
  router.post(
    "/security",
    form,
    wrap(async (req, res) => {
      await factors.passwordAllowed();
      const identity = await authenticate(config.email, req.body.password);
      await factors.passwordResult(Boolean(identity));
      if (
        !identity ||
        !["totp", "recovery"].includes(req.body.kind) ||
        !["replace", "recovery"].includes(req.body.operation)
      ) {
        html(res, securityPage(csrf(req), root, true), 401);
        return;
      }
      const result = await factors.manage(req.session.authVersion!, req.body.code, req.body.kind, req.body.operation);
      if (!result) {
        html(res, securityPage(csrf(req), root, true), 401);
        return;
      }
      if ("challenge" in result && result.challenge) {
        if (req.session.replacement) await factors.cancel(req.session.replacement.id);
        req.session.replacement = {
          id: result.challenge.id,
          expiresAt: Number(result.challenge.expires_at),
        };
        await save(req);
        res.redirect(`${root}/replace`);
      } else if (result.version && result.codes) {
        await complete(req, result.version);
        html(res, recoveryPage(root, result.codes));
      }
    }),
  );
  router.get(
    "/replace",
    wrap(async (req, res) => {
      const replacement = req.session.replacement;
      const uri = replacement && replacement.expiresAt > now() ? await factors.preview(replacement.id) : null;
      if (!uri) {
        res.redirect(`${root}/security`);
        return;
      }
      html(res, await enrollmentPage(csrf(req), root, uri, true));
    }),
  );
  router.post(
    "/replace",
    form,
    wrap(async (req, res) => {
      const replacement = req.session.replacement;
      const result =
        replacement && replacement.expiresAt > now()
          ? await factors.confirmEnrollment(replacement.id, req.body.code)
          : null;
      if (!result) {
        const uri = replacement ? await factors.preview(replacement.id) : null;
        html(
          res,
          uri
            ? await enrollmentPage(csrf(req), root, uri, true, true)
            : page("Unable to verify", `<p><a href="${root}/security">Start again</a></p>`),
          401,
        );
        return;
      }
      await complete(req, result.version);
      html(res, recoveryPage(root, result.codes));
    }),
  );
  // express-session short-circuits when req.session already exists: this is the same store/session.
  // The built-in callback is inert; only complete() above can establish adminUser.
  AdminJSExpress.buildAuthenticatedRouter(
    admin,
    {
      cookieName: COOKIE_NAME,
      cookiePassword: config.sessionSecret,
      authenticate: async () => null,
    },
    router,
    sessionOptions,
  );
  app.use(root, router);
  app.get("/", (_req, res) => res.redirect(root));
  const onError: ErrorRequestHandler = (error, _req, res, _next) => {
    if (error instanceof AuthenticationLocked) {
      res.set("Retry-After", "900");
      res.status(429).send("Unable to verify authentication. Try again later.");
      return;
    }
    console.error("Admin request failed.");
    res.status(500).send("Unable to complete the Admin request.");
  };
  app.use(onError);
  return app;
}
