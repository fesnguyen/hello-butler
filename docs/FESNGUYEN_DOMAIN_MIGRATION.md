# fesnguyen.com domain migration

This is a **staged** migration. Keep the old public hostname available until the new endpoints and a freshly built Android APK are verified.

## Public hostnames

| Service | Public hostname | Tunnel origin |
| --- | --- | --- |
| Backend | `api.fesnguyen.com` | `http://localhost:8000` |
| Showcase | `butler.fesnguyen.com` | `http://localhost:8002` |
| Admin | Not publicly exposed | `http://localhost:3001` (local only) |

The Windows runner, Docker Compose ports, images, and GitHub Actions deployment workflow do not require a hostname change. Do not rename Docker volumes or recreate the database.

## 1. Cloudflare dashboard (manual)

In Cloudflare Zero Trust, open the **existing named tunnel** for the Windows host and add two published application routes/hostnames:

- `api.fesnguyen.com` → `http://localhost:8000`
- `butler.fesnguyen.com` → `http://localhost:8002`

Ensure DNS records are created/associated with the existing tunnel. Do not remove the old hostname yet. Verify `https://api.fesnguyen.com/docs` and `https://butler.fesnguyen.com/api/applications` before switching clients. If the tunnel is locally configured instead of dashboard-managed, update its ingress and DNS routes on the host rather than configuring a conflicting dashboard route.

Do **not** publish the admin service without a separate access-control review.

## 2. Windows production Android configuration (manual)

The CD workflow copies `C:\hello-butler-configs\gradle.properties` over `client/gradle.properties` before assembling the release APK. Therefore the tracked repository default is **not enough**.

Change only this key in the external production file:

```properties
BACKEND_BASE_URL=https://api.fesnguyen.com/
```

Preserve signing credentials and other existing settings. Never commit the external file.

Run a release build through the existing CD workflow **only after the new API hostname works**. On a branch, normal CI does not deploy: production CD runs on `main` or manual dispatch. Merging later will be a separate decision.

## 3. Check production application configuration

Inspect `C:\hello-butler-configs\.env.production` on the Windows host for public URL, CORS, callback, and Showcase-related environment variables. Update only those that actually reference the old hostname; preserve database and authentication secrets. The Showcase frontend uses `VITE_API_BASE_URL` if provided at build time; confirm whether it is configured in your deployment, and verify requests from the new hostname.

## 4. Verify before retiring old hostnames

- `https://api.fesnguyen.com/docs` loads
- Backend authenticated request succeeds from a newly built Android APK
- `https://butler.fesnguyen.com/api/applications` returns HTTP 200
- Showcase page loads and its APK download works
- Existing old-domain clients continue working during rollout
- GitHub Actions CI passes; production CD is tested only after explicit cutover

**Rollback:** restore the external Android `BACKEND_BASE_URL` and rebuild if needed; keep the old Cloudflare route active. Avoid changing production databases or deleting the old DNS route during staging.
