# Auto-publish APK to GitHub Releases

The GitHub App used from PromptQL **cannot** edit files under `.github/workflows/`
(`workflows` OAuth scope) and **cannot** upload release assets to `uploads.github.com`.

## One-time setup (you, as repo owner)

1. Open [android.yml](https://github.com/SustemFox/OfflineLLM_V1/blob/main/ci/android-with-releases.yml)
2. Copy its full contents
3. Replace [`.github/workflows/android.yml`](https://github.com/SustemFox/OfflineLLM_V1/blob/main/.github/workflows/android.yml) with that content (web UI or local git push)
4. Commit to `main`

Or from a machine with `gh` / git:

```bash
git clone https://github.com/SustemFox/OfflineLLM_V1.git
cd OfflineLLM_V1
cp ci/android-with-releases.yml .github/workflows/android.yml
git add .github/workflows/android.yml
git commit -m "ci: publish APK to GitHub Releases"
git push
```

## What happens after that

On every **push to `main`** (and **workflow_dispatch**):

1. Build `assembleRelease`
2. Upload Actions artifact (as before)
3. Create/update GitHub Release tag `v{versionName}` (from `app/build.gradle`)
4. Attach `OfflineLLM-{versionName}.apk`

PRs still build + artifact only (no Release).

## Manual release for current build (optional)

If the workflow is not updated yet, attach the APK from the latest Actions run:

```bash
gh run download 30067530847 -R SustemFox/OfflineLLM_V1 -n OfflineLLM-v1.0
gh release upload v1.5.12-bugfix app-release.apk \
  --repo SustemFox/OfflineLLM_V1 \
  --clobber \
  -n OfflineLLM-1.5.12-bugfix.apk
```

Release page (tag already created):  
https://github.com/SustemFox/OfflineLLM_V1/releases/tag/v1.5.12-bugfix
