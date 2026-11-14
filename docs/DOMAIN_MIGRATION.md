# Migration to fesnguyen.com

This migration changes public routing, not the Windows self-hosted GitHub Actions runner or Docker services.

## Public hostnames

| Hostname | Tunnel origin | Purpose |
| --- | --- | --- |
| `api.fesnguyen.com` | `http://localhost:8000` | FastAPI production backend |
| `butler.fesnguyen.com` | `http://localhost:8002` | Public showcase |

Keep the existing `hello-butler.ycompany-backends.win` route until the replacement is tested. Do not publish the admin port (`localhost:3001`) without a separate access-control decision.

## Cloudflare configuration (manual)

1. Confirm `fesnguyen.com` is active in Cloudflare.
2. Open **Zero Trust → Networks → Tunnels**, select the existing named tunnel and configure the two public hostnames above. The dashboard navigation may vary.
3. Confirm Cloudflare creates or already has the appropriate tunnel DNS records. Do not replace them with records pointing at your home IP.
4. Test `https://api.fesnguyen.com/docs` and `https://butler.fesnguyen.com/api/applications`. A valid HTTP response confirms routing; inspect application health separately.
5. Leave the old hostname online during the Android client transition.

## Windows runner configuration (manual)

The CD workflow copies local configuration files that are **not stored in Git**:

- `C:\hello-butler-configs\gradle.properties` → `client/gradle.properties`
- `C:\hello-butler-configs\.env.production` → `.env.production`

Update the external Gradle file to include:

```properties
BACKEND_BASE_URL=https://api.fesnguyen.com/
```

The release workflow validates this value before building the APK. If it is not updated, Android deployment intentionally fails rather than shipping the old endpoint.

Review the production environment file for any absolute public URLs, CORS allowlists, OAuth callback URLs, or other domain-specific settings. Change only entries that exist; do not publish credentials. The repository's current backend settings do not define a hard-coded old hostname.

## Verification and rollout

1. Confirm the new Cloudflare routes work **before** merging this branch.
2. Update the runner's external Gradle properties and any actual production environment URL settings.
3. Merge only after review; CD deploys from `main`, not this feature branch.
4. Verify CI, then release an Android APK through the existing CD workflow and test login, requests, and audio against the new API.
5. Test showcase and APK download on the new hostname.
6. Keep the old route until previously installed Android clients have migrated or their support window has ended.

## Rollback

Keep the old Cloudflare hostname routing intact. If the new API route fails, restore the external `BACKEND_BASE_URL` to the old endpoint and redeploy an Android build; previously distributed APKs retain their compiled URL. No database or container-volume migration is required.
