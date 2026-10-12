package io.github.houseelves.desk.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HttpSecurityTest {

    @Test
    void pageRedirectsToGitHubSignIn() {
        given().redirects().follow(false)
                .when().get("/")
                .then().statusCode(302)
                .header("Location", startsWith("https://github.com/login/oauth/authorize"));
    }

    @Test
    void healthIsOpen() {
        given().when().get("/q/health/live").then().statusCode(200);
    }
}
