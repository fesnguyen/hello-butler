
import AdminJS from "adminjs";
import AdminJSExpress from "@adminjs/express";
import express from "express";

import { initializeDatabases } from "./database.js";

const databases = await initializeDatabases();

const admin = new AdminJS({
  rootPath: "/admin",
  databases,
});

const app = express();
const router = AdminJSExpress.buildRouter(admin);

app.use(admin.options.rootPath, router);

app.get("/", (_req, res) => {
  res.redirect("/admin");
});

const port = Number(process.env.PORT ?? 3000);

app.listen(port, "127.0.0.1", () => {
  console.log(`AdminJS running at http://localhost:${port}/admin`);
});
