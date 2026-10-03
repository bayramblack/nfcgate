# Direct Android updates

Public download: https://relay.undernet.work/downloads/UnderNet-latest.apk

Update metadata: https://relay.undernet.work/downloads/latest.json

The app checks on resume, at most once per six hours after a successful check.
The drawer action checks immediately. A newer version offers a download; Android
asks the user to approve installation. Postponing suppresses automatic prompts
for that version for 24 hours. No silent-install or device-owner access is used.
Downloads are HTTPS-only, restricted to this host, and checked for exact size,
SHA-256, package ID, increasing version code, and the installed signing certificate.
Update errors do not block session use. Keep the app open during download.

## Publish the next version

1. Increase `versionCode` and `versionName` in `app/build.gradle`.
2. Build `:app:assembleDistribution :app:lintDistribution` with Java 17.
3. Run `python distribution/prepare_release.py`.
4. Upload the generated versioned APK from `build/distribution` to
   `/var/www/undernet-downloads/downloads` on the relay server. Verify its hash.
5. Atomically replace the `UnderNet-latest.apk` symlink with the versioned APK.
6. Upload `latest.json` to a temporary filename and rename it to `latest.json`
   **last**, so the app never sees metadata for an incomplete file.
7. Check both public URLs and the APK checksum over HTTPS.

The `distribution` variant disables debugging and uses the existing local Android
debug keystore **only to preserve signing continuity with the already distributed
builds**. Preserve that keystore securely; replacing it breaks upgrades of those
installations. This is a direct-install channel, not a Play Store release.
Never upload the keystore, server credentials, or the private room database.

Nginx serves only port 443 using the existing relay certificate. Port 80 remains
free for the existing certbot standalone renewal. The deploy hook
`/etc/letsencrypt/renewal-hooks/deploy/undernet-downloads.sh` reloads nginx after
renewal. The relay service and its port 5566 are independent.
