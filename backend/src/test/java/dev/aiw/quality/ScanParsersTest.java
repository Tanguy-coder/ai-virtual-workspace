package dev.aiw.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The scanners' real reports (recorded 2026-11-21 on a planted vulnerable app) become findings with proof (docs/18). */
class ScanParsersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode report(String name) throws Exception {
        return JSON.readTree(new String(ScanParsersTest.class.getResourceAsStream("/scans/" + name).readAllBytes()));
    }

    /** The snapshot the reports were made on; the token is built here so no credential is committed. */
    private static Path snapshot() throws Exception {
        Path dir = Files.createTempDirectory("snap");
        Files.createDirectories(dir.resolve("src"));
        String token = "ghp_" + "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789";
        Files.writeString(dir.resolve("src/app.js"), String.join("\n",
                "const express = require('express');",
                "const app = express();",
                "const AWS_KEY = \"AKIAIOSFODNN7EXAMPLE\";",
                "const ghToken = \"" + token + "\";",
                "app.get('/calc', (req, res) => {",
                "  res.send(String(eval(req.query.expr)));",
                "});",
                "app.get('/hello', (req, res) => { res.send('<h1>' + req.query.name + '</h1>'); });",
                "app.listen(3000);", ""));
        return dir;
    }

    @Test
    void gitleaksProvesWhereTheSecretIsWithoutShowingIt() throws Exception {
        List<ScanParsers.Draft> f = ScanParsers.gitleaks(report("gitleaks.json"), snapshot(), "/src/");
        assertThat(f).hasSize(1);
        ScanParsers.Draft d = f.getFirst();
        assertThat(d.severity()).isEqualTo("CRITICAL");
        assertThat(d.file()).isEqualTo("src/app.js");
        assertThat(d.line()).isEqualTo(4);
        assertThat(d.evidence()).startsWith("const ghToken = \"****").doesNotContain("aBcDeF");
        assertThat(d.recommendation()).contains("revoke");
    }

    @Test
    void semgrepGivesTheLineTheCweAndSeverity() throws Exception {
        List<ScanParsers.Draft> f = ScanParsers.semgrep(report("semgrep.json"), snapshot(), "/src/");
        assertThat(f).extracting(ScanParsers.Draft::severity).contains("HIGH", "MEDIUM", "LOW");
        ScanParsers.Draft eval = f.stream().filter(d -> d.rule().endsWith("code-string-concat")).findFirst().orElseThrow();
        assertThat(eval.severity()).isEqualTo("HIGH");
        assertThat(eval.line()).isEqualTo(6);
        assertThat(eval.evidence()).isEqualTo("res.send(String(eval(req.query.expr)));");
        assertThat(eval.reference()).startsWith("CWE-95");
        // The same rule on the same code is one finding; fingerprints do not depend on line numbers.
        assertThat(f.stream().map(ScanParsers.Draft::fingerprint).distinct().count()).isEqualTo(f.size());
    }

    @Test
    void osvGivesOneFindingPerPackageWithTheVersionThatFixesAll() throws Exception {
        List<ScanParsers.Draft> f = ScanParsers.osv(report("osv.json"));
        assertThat(f).hasSize(1);
        ScanParsers.Draft d = f.getFirst();
        assertThat(d.title()).startsWith("Vulnerable dependency lodash@4.17.15");
        assertThat(d.severity()).isIn("HIGH", "CRITICAL");
        assertThat(d.evidence()).contains("CVE-2020-28500");
        assertThat(d.recommendation()).contains("Upgrade lodash from 4.17.15 to 4.18.0").contains("npm install lodash@^4.18.0");
        assertThat(d.location()).isEqualTo("npm:lodash@4.17.15");
    }

    @Test
    void zapAlertsBecomeFindingsWithTheRequestAsProof() throws Exception {
        List<ScanParsers.Draft> f = ScanParsers.zap(report("zap.json"));
        assertThat(f).extracting(ScanParsers.Draft::title).contains("Content Security Policy (CSP) Header Not Set")
                .doesNotContain("ZAP is Out of Date");
        ScanParsers.Draft powered = f.stream().filter(d -> d.rule().equals("10037")).findFirst().orElseThrow();
        assertThat(powered.severity()).isEqualTo("LOW");
        assertThat(powered.evidence()).contains("GET http://aiw-sample:3000").contains("X-Powered-By: Express");
        assertThat(powered.recommendation()).doesNotContain("<p>");
    }

    @Test
    void k6MetricsAreCheckedAgainstTheBudgets() throws Exception {
        ScanParsers.Load l = ScanParsers.k6(report("k6.json"));
        assertThat(l.p95Ms()).isLessThan(100);
        assertThat(l.errorRate()).isEqualTo(0.0);
        assertThat(l.rps()).isGreaterThan(10);
        assertThat(ScanParsers.loadFindings(l, 10, 20, "http://app")).isEmpty();
        List<ScanParsers.Draft> slow = ScanParsers.loadFindings(new ScanParsers.Load(2500.0, 0.2, 5.0, 100.0), 10, 20, "http://app");
        assertThat(slow).extracting(ScanParsers.Draft::severity).containsExactly("HIGH", "HIGH");
        assertThat(slow.getFirst().evidence()).contains("p95 2500 ms");
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(ScanParsers.compareVersions("4.17.21", "4.17.9")).isPositive();
        assertThat(ScanParsers.compareVersions("1.0.0", "1.0.0-beta")).isZero();
        assertThat(ScanParsers.severityOfScore(9.8)).isEqualTo("CRITICAL");
        assertThat(ScanParsers.severityOfScore(5.3)).isEqualTo("MEDIUM");
    }
}
