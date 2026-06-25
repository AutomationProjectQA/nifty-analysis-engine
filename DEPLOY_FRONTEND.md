# Deploy Frontend to VM (manual zip flow)

Build the frontend on your laptop, upload a zip, and extract it into the nginx web
root on the VM. **The VM builds nothing and needs no git repo.**

Web root on VM: `/var/www/nifty-portal/dist` (see [nginx.conf](nginx.conf))

---

## 1. Build + zip (on your laptop)

```bash
cd frontend
npm run build          # produces frontend/dist/

cd dist
zip -r ../../dist.zip .   # zip the CONTENTS of dist (note the trailing dot)
cd ../..
```

> Zip from *inside* `dist` (the `.`) so `index.html` and `assets/` sit at the top
> level of the zip — not nested under a `dist/` folder.

## 2. Upload to the VM

```bash
export NIFTY_VM=meet@YOUR_VM_IP      # your VM user@ip or ssh alias
scp dist.zip "$NIFTY_VM:/tmp/dist.zip"
```

If SSH needs a key: `scp -i ~/.ssh/yourkey dist.zip "$NIFTY_VM:/tmp/dist.zip"`

## 3. Extract into the web root (on the VM)

```bash
ssh -t "$NIFTY_VM" 'sudo bash -c "
  rm -rf /var/www/nifty-portal/dist/* &&
  unzip -o /tmp/dist.zip -d /var/www/nifty-portal/dist &&
  systemctl reload nginx
"'
```

## 4. Verify

Hard-refresh <https://niftyintel.com> (Cmd/Ctrl+Shift+R) to bypass browser cache.

---

## Notes
- `rm -rf .../dist/*` first clears old hashed JS/CSS so stale assets don't pile up.
- Needs `unzip` on the VM: `sudo apt install unzip` if missing.
- This deploys **frontend only** — it does not touch the backend. To deploy both
  jar + frontend in one shot, use [push-deploy.sh](push-deploy.sh) instead.
