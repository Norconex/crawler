# Norconex Crawlers

Norconex web and file system crawlers are full-featured crawlers (or spider) that can manipulate and store collected data in a repository of your choice (e.g., a search engine). They are very flexible, powerful, easy to extend, and portable. They can be used command-line with file-based configuration on any OS or embedded into Java applications using well-documented APIs.

Visit the website for binary downloads and documentation:
https://opensource.norconex.com/crawlers/

## Are you on the right branch?

This branch holds version 4 code, which is still in development.

**For the latest stable release of Norconex Web Crawler, use the [version 3 branch](https://github.com/Norconex/crawlers/tree/3.x-branch).**

# UPCOMING: Crawler V4 Stack

The default `main` branch holds code for the upcoming version 4 crawler stack. It is now a mono-repo containing all Norconex crawler-related projects previously maintained in separate repos. All projects in this mono report will now be released simultaneously and share the same version number.

Until v4 is officially released, this branch should not be considered stable.

# Contributing

Contributions are welcome — whether it's a bug report, a fix, a new feature, or improved documentation. Before submitting a pull request, please read [CONTRIBUTING.md](CONTRIBUTING.md) for the sign-off and commit signing requirements that all contributions must meet.

# Projects

[![Java CI with Maven](https://github.com/Norconex/crawlers/actions/workflows/maven-ci-cd.yaml/badge.svg)](https://github.com/Norconex/crawlers/actions/workflows/maven-ci-cd.yaml)

| Folder                          | Artifact Id                       | Build                                                                                                                                                                                                                                                               |
| ------------------------------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| crawler/core/                   | nx-crawler-core test              | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-crawler-core&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-crawler-core)                                     |
| crawler/fs/                     | nx-crawler-fs                     | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-crawler-fs&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-crawler-fs)                                         |
| crawler/web/                    | nx-crawler-web                    | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-crawler-web&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-crawler-web)                                       |
| importer/                       | nx-importer                       | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-importer&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-importer)                                             |
| committer/apachekafka/          | nx-committer-apachekafka          | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-apachekafka&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-apachekafka)                   |
| committer/azurecognitivesearch/ | nx-committer-azurecognitivesearch | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-azurecognitivesearch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-azurecognitivesearch) |
| committer/core/                 | nx-committer-core                 | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-core&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-core)                                 |
| committer/idol/                 | nx-committer-idol                 | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-idol&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-idol)                                 |
| committer/elasticsearch/        | nx-committer-elasticsearch        | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-elasticsearch&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-elasticsearch)               |
| committer/neo4j/                | nx-committer-neo4j                | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-neo4j&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-neo4j)                               |
| committer/solr/                 | nx-committer-solr                 | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-solr&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-solr)                                 |
| committer/sql/                  | nx-committer-sql                  | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=com.norconex.crawler%3Anx-committer-sql&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=com.norconex.crawler%3Anx-committer-sql)                                   |
| 🪦 committer/amazoncloudsearch/ | nx-committer-amazoncloudsearch    | Deprecated                                                                                                                                                                                                                                                          |

All projects in this repository share the same Maven group id:

    com.norconex.crawler
