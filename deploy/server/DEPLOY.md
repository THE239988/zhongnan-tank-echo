# Tank Trouble 3.0 ECS deployment

The server runs from a versioned release directory:

```text
/opt/tank-trouble/
  releases/
    2.0.0/
    3.0.2/
  current -> releases/3.0.2
```

Create `/etc/tank-trouble.env` once, owned by root and mode `600`:

```bash
TANKTROUBLE_PASSWORD=replace-with-the-room-password
TANKTROUBLE_PORT=7777
```

Install a release:

```bash
./install-release.sh /tmp/tanktrouble-server-3.0.2 3.0.2
```

Verify TCP 7777 and the service log:

```bash
systemctl status tank-trouble.service --no-pager
ss -ltnp | grep 7777
journalctl -u tank-trouble.service -n 50 --no-pager
```

Rollback:

```bash
ln -sfn /opt/tank-trouble/releases/3.0.1 /opt/tank-trouble/current
systemctl restart tank-trouble.service
```
