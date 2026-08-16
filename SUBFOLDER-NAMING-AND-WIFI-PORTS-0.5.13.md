# 0.5.13 — Status suffix folders and independent Wi-Fi port

## Output subfolder naming

Status is now a suffix instead of a prefix. For a date such as `09-08-2026`, media folders are:

- `09-08-2026/09-08-2026_GOOD/`
- `09-08-2026/09-08-2026_FAILED/`
- `09-08-2026/09-08-2026_ERROR/`

Daily monitoring reports remain `monitoring/09-08-2026/GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT`.

## Independent Wi-Fi ports

The built-in Wi-Fi file server still defaults to TCP port `1100`, but its port can now be changed under **ADVANCED** to any value from 1024 through 65535. The selected port is stored in app preferences and is used consistently for the server socket, on-screen URL, and foreground notification.

This permits another app on the same camera to listen on a different TCP port, for example `1200`, at the same time. Different TCP ports do not conflict. If this app cannot bind because its selected port is already occupied, it reports a clear port-in-use error instead of silently interfering with the other service.
