package io.github.houseelves.desk.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vertx.core.net.HostAndPort;

class SameOriginTest {

    @Test
    void theOriginMustBeTheDeskItself() {
        HostAndPort desk = HostAndPort.create("elf-desk.localhost", 7777);
        assertTrue(SameOrigin.sameAuthority("http://elf-desk.localhost:7777", desk));
        assertTrue(SameOrigin.sameAuthority("http://ELF-DESK.localhost:7777", desk));
        assertTrue(SameOrigin.sameAuthority("http://localhost", HostAndPort.create("localhost", -1)));
        assertFalse(SameOrigin.sameAuthority("http://localhost:8080", HostAndPort.create("localhost", 7777)));
        assertFalse(SameOrigin.sameAuthority("http://elf-desk.localhost:8080", desk));
        assertFalse(SameOrigin.sameAuthority("https://elf-desk.localhost:7777", desk));
        assertFalse(SameOrigin.sameAuthority("https://evil.example", desk));
        assertFalse(SameOrigin.sameAuthority("null", desk));
        assertFalse(SameOrigin.sameAuthority("http://elf-desk.localhost:7777", null));
    }
}
