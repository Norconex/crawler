Solr Committer
==============

Solr implementation of Norconex Committer.  Also works with Solr-based products, such as LucidWorks Fusion.

Supports standalone Solr and SolrCloud (nodes are discovered over HTTP, no ZooKeeper access needed),
with basic authentication, bearer tokens and custom HTTP headers. It sends JSON updates using
the JDK HTTP client and does not depend on SolrJ.

This committer is bundled with the web and file system crawler distributions.

Website: https://crawler.norconex.com/docs/reference/committers/SolrCommitter
