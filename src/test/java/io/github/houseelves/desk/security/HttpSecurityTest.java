package io.github.houseelves.desk.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HttpSecurityTest {

    static final Path DATA = Path.of("target/house-elves");

    static String code() throws Exception {
        return Files.readString(DATA.resolve("open-code")).strip();
    }

    @Test
    void withoutTheKeyAPageGoesToTheWelcomePage() {
        given().redirects().follow(false).when().get("/")
                .then().statusCode(302).header("Location", endsWith("/welcome"));
        given().when().get("/welcome").then().statusCode(200).body(containsString("house-elves open"));
    }

    @Test
    void aOneTimeCodeGivesTheKeyCookieOnce() throws Exception {
        String code = code();
        String cookie = given().redirects().follow(false).when().get("/open?code=" + code)
                .then().statusCode(302).header("Location", endsWith("/"))
                .extract().cookie(DeskKeyAuth.COOKIE);
        assertNotNull(cookie);
        assertEquals(Files.readString(DATA.resolve("desk.key")).strip(), cookie);

        // Used up: the same link again signs nobody in, and there is a new code for next time.
        given().redirects().follow(false).when().get("/open?code=" + code)
                .then().statusCode(302).header("Location", endsWith("/welcome?expired"));
        assertFalse(code.equals(code()));

        given().redirects().follow(false).cookie(DeskKeyAuth.COOKIE, cookie).when().get("/").then().statusCode(200);
        given().redirects().follow(false).cookie(DeskKeyAuth.COOKIE, "not-the-key").when().get("/")
                .then().statusCode(302).header("Location", endsWith("/welcome"));
    }

    @Test
    void onlyLocalNamesAreAnswered() {
        given().header("Host", "evil.example:7777").when().get("/welcome").then().statusCode(403);
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
                io.restassured.RestAssured.baseURI + ":" + io.restassured.RestAssured.port + "/q/health/live")).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(200, r.statusCode());
    }

    @Test
    void healthIsOpen() {
        given().when().get("/q/health/live").then().statusCode(200);
    }
}
