# Norconex Web Crawler (Playwright)

The [Norconex Web Crawler](https://hub.docker.com/r/norconex/crawler-web) with
**Playwright and Chromium bundled in the image**, for crawling
JavaScript-rendered websites without providing your own browser.

Part of the [Norconex Crawler](https://github.com/Norconex/crawler) project.

## Which image do I want?

`norconex/crawler-web` already supports JavaScript-rendered pages through
**WebDriver (Selenium)**. This variant exists as an *alternative* for people who
prefer Playwright, or who want the browser shipped inside the image rather than
run separately.

The Playwright libraries and a Chromium build are excluded from the standard
distribution precisely because of their size, which is why this is a separate
image — and why it is considerably larger. Use `crawler-web` unless you
specifically want Playwright.

## Tags

- `{{VERSION}}` — the release this page was last updated for.
- `latest`, `4`, `4.0` — published for **stable** releases only. A pre-release
  (`-beta`, `-rc`) publishes its exact version tag and nothing else, so the
  moving tags are never pointed at pre-release code.

## Quick start

Put your `crawler-config.xml` in a local `configs` directory, then:

    docker run --rm \
      -v "$PWD/configs:/opt/norconex/crawler/configs" \
      -v "$PWD/logs:/opt/norconex/crawler/logs" \
      norconex/crawler-web-playwright:{{VERSION}}

Configure a Playwright-based fetcher in your crawler configuration to use the
bundled browser.

## Configuration

| | |
|---|---|
| Config directory | `/opt/norconex/crawler/configs` (volume) |
| Log directory | `/opt/norconex/crawler/logs` (volume) |
| Config file | `crawler-config.xml`, override with `-e COLLECTOR_CONFIG_FILE=...` |
| Browsers | `/opt/norconex/browsers` (`PLAYWRIGHT_BROWSERS_PATH`) |

## Notes

- Based on `eclipse-temurin:21-jre-alpine`, with Chromium installed.
- All Norconex committers are bundled.

## Links

- Source and issues: https://github.com/Norconex/crawler
- Releases and release notes: https://github.com/Norconex/crawler/releases
- License: Apache-2.0
