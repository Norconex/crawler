---
title: Docker
---

# Run Norconex Crawler with Docker

This page covers the recommended Docker workflow for Norconex Crawler v4.

## Image locations

Official images are published to both registries:

- Docker Hub: `norconex/crawler-web`, `norconex/crawler-fs`, `norconex/crawler-web-playwright`
- GitHub Container Registry (GHCR): `ghcr.io/norconex/crawler-web`, `ghcr.io/norconex/crawler-fs`, `ghcr.io/norconex/crawler-web-playwright`

Registry pages:

- https://hub.docker.com/u/norconex
- https://github.com/orgs/Norconex/packages

## Tags and release channels

Stable releases are published with these tags:

- `latest`
- major version (example: `4`)
- minor version (example: `4.1`)
- full version (example: `4.1.2`)

An `edge` tag is also published from the `main` branch to GHCR only:

- `ghcr.io/norconex/crawler-web:edge`
- `ghcr.io/norconex/crawler-fs:edge`
- `ghcr.io/norconex/crawler-web-playwright:edge`

Use stable tags for production, and reserve `edge` for testing.

## Quick run (docker run)

### Web crawler

```bash
docker run --rm \
  -v "${PWD}:/opt/norconex/crawler/configs" \
  -v "${PWD}/logs:/opt/norconex/crawler/logs" \
  -e COLLECTOR_CONFIG_FILE=my-web-crawl.yaml \
  norconex/crawler-web:latest
```

### File system crawler

```bash
docker run --rm \
  -v "${PWD}:/opt/norconex/crawler/configs" \
  -v "${PWD}/logs:/opt/norconex/crawler/logs" \
  -e COLLECTOR_CONFIG_FILE=my-fs-crawl.yaml \
  norconex/crawler-fs:latest
```

## Docker Compose examples

### Compose: web crawler

Create `docker-compose.web.yml`:

```yaml
services:
  crawler-web:
    image: norconex/crawler-web:latest
    environment:
      COLLECTOR_CONFIG_FILE: my-web-crawl.yaml
    volumes:
      - ./configs:/opt/norconex/crawler/configs
      - ./logs:/opt/norconex/crawler/logs
```

Run:

```bash
docker compose -f docker-compose.web.yml up
```

### Compose: file system crawler

Create `docker-compose.fs.yml`:

```yaml
services:
  crawler-fs:
    image: norconex/crawler-fs:latest
    environment:
      COLLECTOR_CONFIG_FILE: my-fs-crawl.yaml
    volumes:
      - ./configs:/opt/norconex/crawler/configs
      - ./logs:/opt/norconex/crawler/logs
      - ./data:/data
```

Use paths under `/data` in your crawler config, for example:

```yaml
startReferences:
  - /data
```

Run:

```bash
docker compose -f docker-compose.fs.yml up
```

## Playwright image and remote browser

Use `crawler-web-playwright` when your web crawl uses Playwright-based rendering.
A remote Selenium Grid or compatible remote browser is typically used alongside it.

Example pull:

```bash
docker pull norconex/crawler-web-playwright:latest
```

### Compose: crawler-web-playwright + Selenium browser

Create `docker-compose.web-playwright-selenium.yml`:

```yaml
services:
  selenium:
    image: selenium/standalone-chromium:latest
    shm_size: 2gb
    ports:
      - "4444:4444"

  crawler-web-playwright:
    image: norconex/crawler-web-playwright:latest
    depends_on:
      - selenium
    environment:
      COLLECTOR_CONFIG_FILE: my-web-crawl.yaml
    volumes:
      - ./configs:/opt/norconex/crawler/configs
      - ./logs:/opt/norconex/crawler/logs
```

In `configs/my-web-crawl.yaml`, use `WebDriverFetcher` and point
`remoteURL` to the Selenium service:

```yaml
fetchers:
  - class: WebDriverFetcher
    browser: CHROME
    remoteURL: http://selenium:4444/wd/hub
```

Run:

```bash
docker compose -f docker-compose.web-playwright-selenium.yml up
```

Notes:

- The `crawler-web-playwright` image includes Playwright and browser-automation dependencies.
- The `selenium` service name is resolvable from the crawler container on the same compose network.

### Compose: crawler-web-playwright + Selenium Grid (hub + node)

Create `docker-compose.web-playwright-grid.yml`:

```yaml
services:
  selenium-hub:
    image: selenium/hub:latest
    ports:
      - "4444:4444"

  selenium-node-chromium:
    image: selenium/node-chromium:latest
    shm_size: 2gb
    depends_on:
      - selenium-hub
    environment:
      SE_EVENT_BUS_HOST: selenium-hub
      SE_EVENT_BUS_PUBLISH_PORT: 4442
      SE_EVENT_BUS_SUBSCRIBE_PORT: 4443

  crawler-web-playwright:
    image: norconex/crawler-web-playwright:latest
    depends_on:
      - selenium-hub
      - selenium-node-chromium
    environment:
      COLLECTOR_CONFIG_FILE: my-web-crawl.yaml
    volumes:
      - ./configs:/opt/norconex/crawler/configs
      - ./logs:/opt/norconex/crawler/logs
```

In `configs/my-web-crawl.yaml`, point `remoteURL` to the hub service:

```yaml
fetchers:
  - class: WebDriverFetcher
    browser: CHROME
    remoteURL: http://selenium-hub:4444/wd/hub
```

Run:

```bash
docker compose -f docker-compose.web-playwright-grid.yml up
```

## Committers in Docker vs ZIP

Docker images already include external committer JARs, so no additional committer
installation is required.

For ZIP-based installs, external committers must be downloaded separately
(`nx-committer-<name>-<version>.zip`) and copied to the crawler `lib/` directory.

## Tips

- Keep config files in a dedicated `configs/` folder and mount it read-only when possible.
- Persist logs by mounting `/opt/norconex/crawler/logs`.
- Pin a version tag for repeatable production deployments.
