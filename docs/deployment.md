# Woodpecker + Coolify

CI: https://ci.sharphurt.ru — Woodpecker 3.20.1, Caddy TLS, GitHub OAuth.
Application: `7pbn703jgbxinrllz3pyqlgf` in Coolify, public source repository
`sharphurt/coffie-diary`, branch `master`, Docker Compose build pack.
Target binding: `10.0.0.1:8185` (VPN only).

`.woodpecker.yml` runs frontend install/build and backend verification, then
requests a Coolify deployment. Only push/manual events on master are enabled.
Pull requests do not receive deployment credentials. Coolify builds the latest
master when the request arrives; it does not pin the build to the CI commit.

Server configuration: `/opt/woodpecker/compose.yml`, secrets in root-only `.env`.
Persistent named volumes store Woodpecker server/agent state. Registration is
closed; GitHub user `sharphurt` is the administrator. Only public repositories
owned by sharphurt are exposed through the configured GitHub integration.

Required setup still pending: GitHub OAuth Client ID/Secret in the server `.env`,
first login, repository activation and `coolify_deploy_token` repository secret
(deploy permission only), then an end-to-end push verification.
Pipeline containers need the existing `coolify` Docker network to reach its API.
No periodic polling or GitHub Actions runner is installed.

The app database password and initial owner password were generated on the
server and set as Coolify application environment variables. They are not in Git.
The deployment does not turn the current application into a completed V1;
remaining product requirements are listed in acceptance.md.
