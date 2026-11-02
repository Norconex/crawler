# Rules for working in this repository

This is the open-source Norconex crawler. It must contain **no code, configuration, documentation or tests that exist
only to serve one particular hosted platform, launcher or consumer**, not even behind a system property or as an inert
hook. Such code belongs in the consumer's own extension, built against this project's public API, for example in a
launcher wrapper that builds a driver from a `DriverFactory`, adjusts it and calls `CliCrawlerLauncher.launch` (see
`WebCrawler` and `FsCrawler`).

Generic capabilities that a consumer benefits from are welcome here, when they make sense for any user or integrator
of the crawler and are described that way. The line is thin sometimes: **when in doubt, ask the maintainer before
editing**. Real bug fixes and features useful to this project's own users are always welcome, described on their own
merits.
