//------------------------------------------------------------------------------
// Copyright 2025-2026 Norconex Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//------------------------------------------------------------------------------
//
// Parallel Sonar orchestrator for the nx-crawler monorepo.
//
// Invoked once from the root via:
//   mvn -N groovy:execute -Dsource=assembly/sonar/run-sonar-analysis.groovy \
//       -Dsonar.gate.mode=changed \
//       -Dsonar.base.sha=<base-git-ref>
//
// System properties:
//   sonar.gate.mode           = changed (default) | all | none
//   sonar.base.sha            = git ref to diff against when mode=changed
//                               (default: HEAD~1)
//   sonar.qualitygate.timeout = seconds to wait per gate poll (default: 300)
//
// PR decoration (optional, for SonarCloud inline PR comments):
//   sonar.pullrequest.key     = pull request number
//   sonar.pullrequest.branch  = PR head branch name
//   sonar.pullrequest.base    = PR base branch name
//
// Environment:
//   SONAR_TOKEN   required — SonarCloud authentication token

import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import java.util.concurrent.*

// ── Guard ─────────────────────────────────────────────────────────────────────

def sonarToken = System.getenv("SONAR_TOKEN")
if (!sonarToken) {
    println "[Sonar] SONAR_TOKEN not set — skipping all analysis"
    return
}

// ── Configuration ─────────────────────────────────────────────────────────────

def gateMode       = System.getProperty("sonar.gate.mode", "changed")
def baseSha        = System.getProperty("sonar.base.sha", "HEAD~1")
def timeoutSeconds = Long.parseLong(System.getProperty("sonar.qualitygate.timeout", "300"))
def prKey          = System.getProperty("sonar.pullrequest.key", "")
def prBranch       = System.getProperty("sonar.pullrequest.branch", "")
def prBase         = System.getProperty("sonar.pullrequest.base", "")

def mvnExec = System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn"
def rootDir = project.basedir.canonicalFile

// ── Module definitions ────────────────────────────────────────────────────────
// dir: path from repo root (matches <module> entries in root pom.xml)
// key: sonar.projectKey (groupId:artifactId as declared in each module's POM)

def modules = [
    [dir: "importer",                       key: "com.norconex.crawler:nx-importer"],
    [dir: "crawler/core",                   key: "com.norconex.crawler:nx-crawler-core"],
    [dir: "crawler/fs",                     key: "com.norconex.crawler:nx-crawler-fs"],
    [dir: "crawler/web",                    key: "com.norconex.crawler:nx-crawler-web"],
    [dir: "committer/core",                 key: "com.norconex.crawler:nx-committer-core"],
    [dir: "committer/apachekafka",          key: "com.norconex.crawler:nx-committer-apachekafka"],
    [dir: "committer/azurecognitivesearch", key: "com.norconex.crawler:nx-committer-azurecognitivesearch"],
    [dir: "committer/elasticsearch",        key: "com.norconex.crawler:nx-committer-elasticsearch"],
    [dir: "committer/idol",                 key: "com.norconex.crawler:nx-committer-idol"],
    [dir: "committer/neo4j",               key: "com.norconex.crawler:nx-committer-neo4j"],
    [dir: "committer/solr",                key: "com.norconex.crawler:nx-committer-solr"],
    [dir: "committer/sql",                 key: "com.norconex.crawler:nx-committer-sql"],
]

println "[Sonar] Skipping committer/amazoncloudsearch from Sonar orchestration: deprecated module; Amazon CloudSearch is legacy/existing-customers-only."

// ── Downstream dependency graph ───────────────────────────────────────────────
// When a module dir is changed, gate-waiting also covers its downstream modules.
// Only intra-monorepo compile-time dependencies are listed here.

def allCommitters = [
    "committer/core", "committer/apachekafka",
    "committer/azurecognitivesearch", "committer/elasticsearch", "committer/idol",
    "committer/neo4j", "committer/solr", "committer/sql"
]
def downstreamOf = [
    "importer":      ["crawler/core", "crawler/fs", "crawler/web"] + allCommitters,
    "crawler/core":  ["crawler/fs", "crawler/web"] + allCommitters,
    "committer/core": allCommitters - ["committer/core"],
]

// ── Determine which modules need gate-waiting ─────────────────────────────────

def gatedDirs = [] as Set

if (gateMode == "all") {
    gatedDirs = modules*.dir as Set
    println "[Sonar] mode=all — gate-waiting all ${gatedDirs.size()} modules"

} else if (gateMode == "changed") {
    def gitResult = ["git", "diff", "--name-only", baseSha, "HEAD"].execute(null, rootDir)
    def stdout = new StringWriter()
    def stderr = new StringWriter()
    gitResult.consumeProcessOutput(stdout, stderr)
    gitResult.waitFor()
    if (gitResult.exitValue() != 0) {
        throw new IllegalStateException("[Sonar] git diff failed (baseSha=${baseSha}): ${stderr.toString().trim()}")
    }
    def changedFiles = stdout.toString().readLines()

    def directlyChanged = [] as Set
    changedFiles.each { file ->
        modules.each { mod ->
            if (file.startsWith(mod.dir + "/") || file.startsWith(mod.dir + "\\")) {
                directlyChanged << mod.dir
            }
        }
    }

    directlyChanged.each { dir ->
        gatedDirs << dir
        downstreamOf[dir]?.each { gatedDirs << it }
    }

    if (gatedDirs.isEmpty()) {
        println "[Sonar] mode=changed — no module source changes detected; scanning all for badge updates (no gate)"
    } else {
        def asyncDirs = (modules*.dir as Set) - gatedDirs
        println "[Sonar] mode=changed — gate-waiting (${gatedDirs.size()}): ${gatedDirs.sort().join(', ')}"
        if (asyncDirs) {
            println "[Sonar] mode=changed — badge-only async (${asyncDirs.size()}): ${asyncDirs.sort().join(', ')}"
        }
    }

} else {
    // mode=none: scan everything for badge updates, gate nothing
    println "[Sonar] mode=none — scanning all for badge updates (no gate)"
}

// ── Shared utilities ──────────────────────────────────────────────────────────

def jsonSlurper = new JsonSlurper()

def readJson = { String url ->
    def conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("Authorization",
        "Basic " + Base64.encoder.encodeToString("${sonarToken}:".getBytes(StandardCharsets.UTF_8)))
    conn.connectTimeout = 15_000
    conn.readTimeout    = 30_000
    def code   = conn.responseCode
    def stream = code >= 400 ? conn.errorStream : conn.inputStream
    def body   = stream?.getText(StandardCharsets.UTF_8.name()) ?: ""
    if (code >= 400) {
        throw new IllegalStateException("[Sonar] API ${code} for ${url}: ${body}")
    }
    jsonSlurper.parseText(body)
}

def formatConditions = { List conditions ->
    if (!conditions) return "  (no failing conditions returned)"
    conditions.collect { c ->
        "  - ${c.metricKey ?: 'unknown'}: ${c.status ?: 'UNKNOWN'} " +
        "(actual=${c.actualValue ?: 'n/a'} ${c.comparator ?: ''} threshold=${c.errorThreshold ?: 'n/a'})"
    }.join('\n')
}

def awaitQualityGate = { String moduleKey, String moduleDir, long timeout ->
    def reportFile = new File(rootDir, "${moduleDir}/target/sonar/report-task.txt")
    if (!reportFile.isFile()) {
        throw new IllegalStateException("[Sonar] Missing ${reportFile.absolutePath}")
    }
    def task = new Properties()
    reportFile.withInputStream { task.load(it) }

    def ceTaskUrl    = task.getProperty("ceTaskUrl")
    def dashboardUrl = task.getProperty("dashboardUrl") ?: ""
    def serverUrl    = task.getProperty("serverUrl")
    if (!ceTaskUrl || !serverUrl) {
        throw new IllegalStateException("[Sonar] Incomplete report-task.txt for ${moduleKey}")
    }

    def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)
    while (true) {
        def ceTask = readJson(ceTaskUrl).task ?: [:]
        def ceStatus = ceTask.status ?: "UNKNOWN"

        if (ceStatus == "SUCCESS") {
            def analysisId = ceTask.analysisId
            if (!analysisId) {
                throw new IllegalStateException(
                    "[Sonar] Missing analysisId for ${moduleKey}" +
                    (dashboardUrl ? " — ${dashboardUrl}" : ""))
            }
            def gateResp   = readJson(
                "${serverUrl}/api/qualitygates/project_status?analysisId=" +
                URLEncoder.encode(analysisId, StandardCharsets.UTF_8))
            def projectStatus = gateResp.projectStatus ?: [:]
            def status = projectStatus.status ?: "UNKNOWN"
            println "[Sonar] Gate ${moduleKey}: ${status}${dashboardUrl ? ' — ' + dashboardUrl : ''}"
            if (status == "NONE") return
            if (status != "OK") {
                throw new IllegalStateException(
                    "[Sonar] Gate FAILED for ${moduleKey}: ${status}\n" +
                    formatConditions(projectStatus.conditions as List) +
                    (dashboardUrl ? "\n  Dashboard: ${dashboardUrl}" : ""))
            }
            return
        }

        if (ceStatus in ["FAILED", "CANCELED"]) {
            throw new IllegalStateException(
                "[Sonar] Compute Engine task ${ceStatus} for ${moduleKey}" +
                (dashboardUrl ? " — ${dashboardUrl}" : ""))
        }
        if (System.nanoTime() >= deadline) {
            throw new TimeoutException("[Sonar] Timed out (${timeout}s) for ${moduleKey}")
        }
        Thread.sleep(5_000)
    }
}

// ── Parallel scan uploads ─────────────────────────────────────────────────────
// Cap concurrent Maven JVM processes to avoid OOM on CI runners (7 GB RAM).

def uploadPool    = Executors.newFixedThreadPool(5)
def uploadFutures = [:] as LinkedHashMap  // dir -> Future

modules.each { mod ->
    uploadFutures[mod.dir] = uploadPool.submit({
        println "[Sonar] Starting upload: ${mod.key}"

        def cmd = [mvnExec, "sonar:sonar",
                   "-Dsonar.login=${sonarToken}",
                   "-Dsonar.projectKey=${mod.key}",
                   "-Dsonar.qualitygate.wait=false",
                   "-q",
                   "-B"]
        if (prKey)    cmd << "-Dsonar.pullrequest.key=${prKey}"
        if (prBranch) cmd << "-Dsonar.pullrequest.branch=${prBranch}"
        if (prBase)   cmd << "-Dsonar.pullrequest.base=${prBase}"

        def proc = new ProcessBuilder(cmd.collect { it.toString() })
            .redirectErrorStream(true)
            .directory(new File(rootDir, mod.dir))
            .start()
        def output   = proc.inputStream.text
        def exitCode = proc.waitFor()
        if (exitCode != 0) {
            def tail = output.readLines().takeRight(50).join('\n')
            throw new IllegalStateException(
                "[Sonar] Upload failed for ${mod.key} (exit ${exitCode}):\n${tail}")
        }
        println "[Sonar] Upload done: ${mod.key}"
    } as Callable)
}

def uploadErrors = [:] as LinkedHashMap
uploadFutures.each { dir, future ->
    try { future.get() }
    catch (ExecutionException e) { uploadErrors[dir] = e.cause ?: e }
}
uploadPool.shutdown()

// ── Parallel gate-waiting ─────────────────────────────────────────────────────
// Gate-waiting is cheap (just HTTP polling), so no parallelism cap needed.

def gateErrors = [:] as LinkedHashMap

if (gatedDirs) {
    def gatePool    = Executors.newFixedThreadPool(gatedDirs.size())
    def gateFutures = [:] as LinkedHashMap

    gatedDirs.each { dir ->
        def mod = modules.find { it.dir == dir }
        if (!mod || uploadErrors.containsKey(dir)) return
        gateFutures[dir] = gatePool.submit({
            awaitQualityGate(mod.key, dir, timeoutSeconds)
        } as Callable)
    }

    gateFutures.each { dir, future ->
        try { future.get() }
        catch (ExecutionException e) { gateErrors[dir] = e.cause ?: e }
    }
    gatePool.shutdown()
}

// ── Result reporting ──────────────────────────────────────────────────────────

def warnOnlyAsyncUploads = gateMode == "changed" && !gatedDirs.isEmpty()

// Non-gated upload failures are warnings only — don't block CI.
if (warnOnlyAsyncUploads) {
    uploadErrors.findAll { dir, _ -> !gatedDirs.contains(dir) }.each { dir, err ->
        println "[Sonar] WARNING — async upload failed for ${dir}: ${err.message}"
    }
}

// Gated failures (upload or gate) block the build.
def buildFailures = (warnOnlyAsyncUploads
        ? uploadErrors.findAll { dir, _ -> gatedDirs.contains(dir) }
        : uploadErrors) + gateErrors
if (buildFailures) {
    def detail = buildFailures.collect { dir, err -> "  ${dir}: ${err.message}" }.join('\n')
    throw new IllegalStateException("[Sonar] Build failed — quality gate(s) did not pass:\n${detail}")
}

println "[Sonar] Done. ${modules.size()} modules scanned, ${gatedDirs.size()} gated."
