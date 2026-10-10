
import AdminJS from "adminjs";
import AdminJSExpress from "@adminjs/express";
import express from "express";

import { authenticateAdmin } from "./auth.js";
import { initializeDatabases } from "./database.js";

const sessionSecret = process.env.ADMIN_SESSION_SECRET;

if (!sessionSecret || sessionSecret.length < 32) {
  throw new Error(
    "ADMIN_SESSION_SECRET must contain at least 32 characters.",
  );
}

if (!process.env.ADMIN_EMAIL || !process.env.ADMIN_PASSWORD_HASH) {
  throw new Error("Admin credentials are not configured.");
}

const databases = await initializeDatabases().catch(() => {
  console.error("Failed to initialize Admin databases.");
  process.exit(1);
});

const admin = new AdminJS({
  rootPath: "/admin",
  databases,
});

const app = express();

const router = AdminJSExpress.buildAuthenticatedRouter(
  admin,
  {
    authenticate: authenticateAdmin,
    cookieName: "hello-butler-admin",
    cookiePassword: sessionSecret,
  },
  null,
  {
    secret: sessionSecret,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      sameSite: "lax",
      secure: false, // Local HTTP only; change for HTTPS deployment.
      maxAge: 8 * 60 * 60 * 1000,
    },
  },
);

app.use(admin.options.rootPath, router);

app.get("/", (_req, res) => {
  res.redirect("/admin");
});

const port = Number(process.env.PORT ?? 3000);

app.listen(port, "127.0.0.1", () => {
  console.log(`AdminJS running at http://localhost:${port}/admin`);
});
