package io.github.houseelves.desk.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@TestProfile(HttpSecurityTest.DeskName.class)
class HttpSecurityTest {

    /** The desk at its own name, as installed (the other tests use plain localhost). */
    public static class DeskName implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("elfdesk.host", "elf-desk.localhost");
        }
    }

    static final Path DATA = Path.of("target/house-elves");

    static String code() throws Exception {
        return Files.readString(DATA.resolve("open-code")).strip();
    }

    /** A request addressed to the desk's own name (the JVM can't resolve it, so it goes to localhost). */
    static RequestSpecification desk() {
        return given().redirects().follow(false).header("Host", "elf-desk.localhost:" + RestAssured.port);
    }

    static String cookie() {
        return "elfdesk-session-" + RestAssured.port;
    }

    record SignedIn(String session, String pageKey) {
    }

    static SignedIn signIn() throws Exception {
        var r = desk().when().get("/open?code=" + code()).then().statusCode(200).extract();
        return new SignedIn(r.cookie(cookie()), pageKey(r.body().asString()));
    }

    static String pageKey(String openPage) {
        var m = java.util.regex.Pattern.compile("setItem\\('elfdesk-page', '([A-Za-z0-9_-]+)'\\)").matcher(openPage);
        assertTrue(m.find(), openPage);
        return m.group(1);
    }

    @Test
    void withoutASessionAPageGoesToTheWelcomePage() {
        desk().when().get("/").then().statusCode(302).header("Location", endsWith("/welcome"));
        desk().when().get("/welcome").then().statusCode(200).body(containsString("house-elves open"));
    }

    @Test
    void aOneTimeCodeGivesASessionOnce() throws Exception {
        String code = code();
        var opened = desk().when().get("/open?code=" + code)
                .then().statusCode(200).header("Cache-Control", "no-store")
                .header("Set-Cookie", containsStringIgnoringCase("httponly"))
                .header("Set-Cookie", containsString("SameSite=Strict"))
                .body(containsString("location.replace('/')"))
                .extract();
        String session = opened.cookie(cookie());
        String pageKey = pageKey(opened.body().asString());
        assertNotNull(session);
        // Only their hashes are on disk, and the old desk key (the cookie before sessions) is gone.
        String sessions = Files.readString(DATA.resolve("sessions"));
        assertFalse(sessions.contains(session));
        assertFalse(sessions.contains(pageKey));
        assertTrue(sessions.contains(DeskSessions.hash(session) + " " + DeskSessions.hash(pageKey)));
        assertFalse(Files.exists(DATA.resolve("desk.key")));

        // Used up: the same link again signs nobody in, and there is a new code for next time.
        desk().when().get("/open?code=" + code).then().statusCode(302).header("Location", endsWith("/welcome?expired"));
        assertFalse(code.equals(code()));

        desk().cookie(cookie(), session).when().get("/").then().statusCode(200);
        desk().cookie(cookie(), "not-a-session").when().get("/").then().statusCode(302)
                .header("Location", endsWith("/welcome"));
        // Another port's cookie name isn't this desk's.
        desk().cookie("elfdesk-session-1", session).when().get("/").then().statusCode(302);
    }

    @Test
    void dataNeedsThePageKeyAsWell() throws Exception {
        // The cookie reaches any port of this name; the page key only this origin's pages.
        SignedIn in = signIn();
        desk().cookie(cookie(), in.session()).when().get("/files/1").then().statusCode(302)
                .header("Location", endsWith("/welcome"));
        desk().cookie(cookie(), in.session()).when().get("/files/1?k=not-the-key").then().statusCode(302);
        desk().cookie(cookie(), in.session()).when().get("/files/1?k=" + in.pageKey()).then().statusCode(404);
        desk().cookie(cookie(), in.session()).header("Upgrade", "websocket").header("Connection", "Upgrade")
                .when().get("/json-rpc").then().statusCode(401);
        // Another session's page key isn't this one's.
        SignedIn other = signIn();
        desk().cookie(cookie(), in.session()).when().get("/files/1?k=" + other.pageKey()).then().statusCode(302);
    }

    @Test
    void deletingTheSessionsSignsEveryoneOut() throws Exception {
        String session = signIn().session();
        desk().cookie(cookie(), session).when().get("/").then().statusCode(200);
        Files.delete(DATA.resolve("sessions"));
        desk().cookie(cookie(), session).when().get("/").then().statusCode(302);
    }

    @Test
    void anExpiredSessionIsRefused() throws Exception {
        String session = signIn().session();
        Files.writeString(DATA.resolve("sessions"), DeskSessions.hash(session) + " " + DeskSessions.hash("page") + " "
                + Instant.now().minusSeconds(60).getEpochSecond() + "\n");
        desk().cookie(cookie(), session).when().get("/").then().statusCode(302);
    }

    @Test
    void otherLocalNamesAreSentToTheDesksName() throws Exception {
        String code = code();
        String desk = "http://elf-desk.localhost:" + RestAssured.port;
        given().redirects().follow(false).when().get("/").then().statusCode(302).header("Location", desk + "/");
        given().redirects().follow(false).header("Host", "127.0.0.1:" + RestAssured.port).when()
                .get("/open?code=" + code).then().statusCode(302).header("Location", desk + "/open?code=" + code);
        assertEquals(code, code(), "a redirected link isn't used up");
        given().when().post("/json-rpc").then().statusCode(403);
        // Programs on this machine may use any local name.
        given().when().get("/q/health/live").then().statusCode(200);
    }

    @Test
    void onlyTheDesksOwnPagesMaySendItAnything() throws Exception {
        SignedIn in = signIn();
        desk().cookie(cookie(), in.session()).header("Origin", "http://localhost:8080").when()
                .post("/json-rpc?k=" + in.pageKey()).then().statusCode(403);
        desk().cookie(cookie(), in.session()).header("Origin", "http://localhost:8080").header("Upgrade", "websocket")
                .header("Connection", "Upgrade").when().get("/json-rpc?k=" + in.pageKey()).then().statusCode(403);
    }

    @Test
    void noOtherSiteMayFrameTheDesk() {
        desk().when().get("/welcome").then().header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"));
    }

    @Test
    void onlyLocalNamesAreAnswered() {
        given().header("Host", "evil.example:7777").when().get("/welcome").then().statusCode(403);
        given().header("Host", "elf-desk.localhost.evil.example").when().get("/welcome").then().statusCode(403);
        List<String> names = List.of("localhost", "127.0.0.1", "[::1]");
        assertTrue(LocalOnly.allowed("localhost", names));
        assertTrue(LocalOnly.allowed("127.0.0.1", names));
        assertTrue(LocalOnly.allowed("[::1]", names));
        assertTrue(LocalOnly.allowed("::1", names));
        assertFalse(LocalOnly.allowed("localhost.evil.example", names));
        assertFalse(LocalOnly.allowed("192.0.2.10", names));
        assertFalse(LocalOnly.allowed(null, names));
    }

    @Test
    void http2RequestsAreCheckedByTheirAuthority() throws Exception {
        // HTTP/2 (h2c) has no Host header: :authority names the host instead.
        var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_2).build();
        var r = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                RestAssured.baseURI + ":" + RestAssured.port + "/q/health/live")).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(200, r.statusCode());
    }

    @Test
    void healthIsOpen() {
        given().when().get("/q/health/live").then().statusCode(200);
    }

    @Test
    void anElfsFramePageNeedsTheCookieAndIsSandboxed() throws Exception {
        desk().when().get("/elf-frame/daily-triage/email").then().statusCode(302).header("Location", endsWith("/welcome"));
        SignedIn s = signIn();
        // No runner in tests, so no such tab; the page is sandboxed and only framed by the desk all the same.
        var r = desk().cookie(cookie(), s.session()).when().get("/elf-frame/daily-triage/email").then().statusCode(404).extract();
        String csp = r.header("Content-Security-Policy");
        assertTrue(csp.contains("sandbox allow-scripts allow-popups"), csp);
        assertFalse(csp.contains("allow-same-origin"), csp);
        assertTrue(csp.contains("connect-src 'none'"), csp);
        assertTrue(csp.contains("frame-ancestors http://elf-desk.localhost:" + RestAssured.port), csp);
        assertEquals(null, r.header("X-Frame-Options"));
        desk().cookie(cookie(), s.session()).when().get("/elf-frame/..%2F..%2Fetc/email").then().statusCode(404);
    }

    @Test
    void whatAFrameLoadsNeedsNoCookieButOnlyTheRightKeyOpensAnElfsFiles() {
        desk().when().get("/elf-kit/1/kit.js").then().statusCode(200)
                .header("Access-Control-Allow-Origin", "*")
                .body(containsString("export * from '/static/bundle/kit"));
        desk().when().get("/elf-files/0123456789abcdef0123456789abcdef/ui/email.js").then().statusCode(404);
        desk().when().get("/elf-files/nope/ui/email.js").then().statusCode(404);
        // The rest of the desk still needs the session.
        desk().when().get("/files/1").then().statusCode(302);
    }
}
