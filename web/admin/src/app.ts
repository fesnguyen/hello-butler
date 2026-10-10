import AdminJS, { type CurrentAdmin } from "adminjs";
import AdminJSExpress from "@adminjs/express";
import express, { type ErrorRequestHandler } from "express";
import session from "express-session";
import { rateLimit } from "express-rate-limit";
import { createAuthenticator } from "./auth.js";
import { COOKIE_NAME, COOKIE_PATH, SESSION_MAX_AGE, type AdminConfig } from "./config.js";

declare module "express-session" {
  interface SessionData { authenticatedAt?: number; adminUser?: CurrentAdmin }
}

export function createAdminApp(admin: AdminJS, config: AdminConfig, store: session.Store) {
  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", config.trustProxy);
  const router = express.Router();
  router.use((_req, res, next) => {
    res.set("Cache-Control", "no-store");
    res.set("X-Content-Type-Options", "nosniff");
    next();
  });
  router.post("/login", rateLimit({
    windowMs: 15 * 60 * 1000, limit: 10,
    standardHeaders: "draft-8", legacyHeaders: false,
    message: "Too many login attempts. Try again later.",
  }), (req, res, next) => {
    // Browser login forms send URL-encoded bodies with a known length. Reject
    // before AdminJS's multipart parser can allocate memory or write uploads.
    const length = req.get("content-length");
    if (!req.is("application/x-www-form-urlencoded") || req.get("transfer-encoding") ||
        !length || !/^\d+$/.test(length)) {
      res.status(400).send("Invalid login request."); return;
    }
    if (Number(length) > 4096) { res.status(413).send("Login request too large."); return; }
    next();
  });
  const sessionOptions: session.SessionOptions = {
    name: COOKIE_NAME, secret: config.sessionSecret, store,
    resave: false, saveUninitialized: false,
    cookie: { httpOnly: true, sameSite: "lax", secure: config.secureCookies,
      path: COOKIE_PATH, maxAge: SESSION_MAX_AGE },
  };
  // Load sessions before our expiry/logout handlers. The AdminJS session
  // middleware reuses req.session, so it does not create a second session.
  router.use(session(sessionOptions));
  router.use((req, res, next) => {
    if (req.session.adminUser && (!req.session.authenticatedAt ||
        Date.now() - req.session.authenticatedAt >= SESSION_MAX_AGE)) {
      req.session.destroy((error) => {
        res.clearCookie(COOKIE_NAME, { path: COOKIE_PATH, httpOnly: true, sameSite: "lax", secure: config.secureCookies });
        if (error) next(error); else res.redirect(admin.options.loginPath);
      });
      return;
    }
    next();
  });
  router.get("/logout", (req, res, next) => {
    req.session.destroy((error) => {
      res.clearCookie(COOKIE_NAME, { path: COOKIE_PATH, httpOnly: true, sameSite: "lax", secure: config.secureCookies });
      if (error) next(error); else res.redirect(admin.options.loginPath);
    });
  });
  const authenticate = createAuthenticator(config);
  AdminJSExpress.buildAuthenticatedRouter(admin, {
    cookieName: COOKIE_NAME, cookiePassword: config.sessionSecret,
    authenticate: async (email, password, context) => {
      const identity = await authenticate(email, password);
      if (identity && context) {
        // Rotate the session ID at login and erase any pre-login session state.
        await new Promise<void>((resolve, reject) => {
          context.req.session.regenerate((error) => error ? reject(new Error("invalidCredentials")) : resolve());
        });
        context.req.session.authenticatedAt = Date.now();
      }
      return identity;
    },
  }, router, sessionOptions);
  app.use(admin.options.rootPath, router);
  app.get("/", (_req, res) => res.redirect(admin.options.rootPath));
  const onError: ErrorRequestHandler = (_error, _req, res, _next) => {
    console.error("Admin request failed.");
    res.status(500).send("Unable to complete the Admin request.");
  };
  app.use(onError);
  return app;
}
