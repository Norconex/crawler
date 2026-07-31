# Google Cloud Search Committer

Google Cloud Search implementation of Norconex Committer.

Website: https://opensource.norconex.com/committers/googlecloudsearch/

Additional Google-specific HTTP resilience options (in addition to inherited
committer-core queue retry/split options):

- httpConnectTimeoutMillis
- httpReadTimeoutMillis
- httpMaxRetries
- httpBackoffInitialIntervalMillis
- httpBackoffMaxIntervalMillis
- httpBackoffMaxElapsedTimeMillis

All six options default to -1 (keep Google client defaults). Setting any
backoff option enables Google HTTP exponential backoff handlers for IO errors
and 5xx responses.
