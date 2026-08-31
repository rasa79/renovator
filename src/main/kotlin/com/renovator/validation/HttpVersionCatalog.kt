package com.renovator.validation

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Real catalog: HTTP HEAD against repo1.maven.org (PLAN §7 L3 — the URL is the
 * artifact POM path, 200 = exists). Connect/read timeouts are bounded; there are
 * NO retries — a transient failure is a `false` and the planner's replan is the
 * retry (that is the failure model: fail cheap, replan).
 */
class HttpVersionCatalog : VersionCatalog {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun exists(
        groupId: String,
        artifactId: String,
        version: String,
    ): Boolean {
        // groupId dots become path separators; artifactId/version are used verbatim.
        val path = "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("https://repo1.maven.org/maven2/$path"))
                .timeout(Duration.ofSeconds(15))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        } catch (e: Exception) {
            false // no retries: fail cheap, let the planner replan
        }
    }
}
