# Mihon extension-lib 1.4 fixture

`mihon-extension-lib-1.4-fixture.jar` was compiled with Kotlin 1.7.10 against
the unmodified API/model sources from the official TachiyomiX `1.4.4` tag:

- commit: `8240b5cfecbd281bc737ac159ea7d4e5825ed3df`
- source: <https://github.com/mihonapp/tachiyomix/tree/1.4.4>
- JAR SHA-256:
  `3b638a947d30bc4349da3e7f0ef8a0eb5add1a1d15a7d3ce00f623d2615c9e0e`

The fixture deliberately implements only the 1.4 Rx catalogue methods. The
host test calls the 1.6 suspend `getPopularManga` entry point and verifies that
the Mihon-compatible default implementation falls back to
`fetchPopularManga`.
