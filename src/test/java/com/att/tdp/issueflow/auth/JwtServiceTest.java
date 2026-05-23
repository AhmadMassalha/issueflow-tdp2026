package com.att.tdp.issueflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.att.tdp.issueflow.auth.jwt.JwtProperties;
import com.att.tdp.issueflow.auth.jwt.JwtService;
import com.att.tdp.issueflow.common.enums.Role;
import com.att.tdp.issueflow.users.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link JwtService}. Uses fixed clocks so expiry
 * branches can be exercised without sleeping.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-32bytes!";
    private static final long EXPIRES_IN = 3600;
    private static final String ISSUER = "issueflow-test";

    private static User alice() {
        User u = new User();
        u.setUsername("alice");
        u.setRole(Role.DEVELOPER);
        // Hack: BaseEntity.id has no public setter from a record-friendly path,
        // but the setter exists via Lombok @Setter on the superclass.
        u.setId(7L);
        return u;
    }

    private static JwtService svc(Clock clock) {
        return new JwtService(new JwtProperties(SECRET, EXPIRES_IN, ISSUER), clock);
    }

    @Test
    @DisplayName("generate → parse round-trip preserves sub / username / role / jti")
    void should_roundtrip_claims() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        JwtService svc = svc(fixed);

        JwtService.IssuedToken issued = svc.generate(alice());
        Claims c = svc.parse(issued.token());

        assertThat(c.getSubject()).isEqualTo("7");
        assertThat(c.get("username", String.class)).isEqualTo("alice");
        assertThat(c.get("role", String.class)).isEqualTo("DEVELOPER");
        assertThat(c.getId()).isEqualTo(issued.jti());
        assertThat(c.getIssuer()).isEqualTo(ISSUER);
        assertThat(c.getExpiration().toInstant()).isEqualTo(issued.expiresAt());
        assertThat(issued.expiresInSeconds()).isEqualTo(EXPIRES_IN);
    }

    @Test
    @DisplayName("parse — throws ExpiredJwtException once exp has passed")
    void should_throwExpired_whenTokenIsPastExp() {
        Clock issueAt = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        Clock muchLater = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC);

        String token = svc(issueAt).generate(alice()).token();
        JwtService laterSvc = svc(muchLater);

        assertThatThrownBy(() -> laterSvc.parse(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("parse — rejects tokens signed with a different key")
    void should_throw_whenSignatureIsWrong() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        String foreignToken = new JwtService(
                new JwtProperties("OTHER-secret-OTHER-secret-OTHER-secret-OTHER-32!", EXPIRES_IN, ISSUER),
                fixed).generate(alice()).token();

        JwtService ours = svc(fixed);
        assertThatThrownBy(() -> ours.parse(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("parse — rejects tokens with a different issuer")
    void should_throw_whenIssuerMismatches() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        String wrongIssuerToken = new JwtService(
                new JwtProperties(SECRET, EXPIRES_IN, "other-issuer"), fixed).generate(alice()).token();

        JwtService ours = svc(fixed);
        assertThatThrownBy(() -> ours.parse(wrongIssuerToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("getJti — returns the jti even for already-expired tokens")
    void should_returnJti_evenWhenExpired() {
        Clock issueAt = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        Clock muchLater = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC);

        JwtService.IssuedToken issued = svc(issueAt).generate(alice());

        assertThat(svc(muchLater).getJti(issued.token())).isEqualTo(issued.jti());
    }

    @Test
    @DisplayName("constructor — fails fast when secret is blank")
    void should_failFast_whenSecretBlank() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> new JwtService(new JwtProperties("", EXPIRES_IN, ISSUER), fixed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    @DisplayName("constructor — fails fast when secret is shorter than 32 bytes")
    void should_failFast_whenSecretTooShort() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-23T10:00:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> new JwtService(new JwtProperties("short", EXPIRES_IN, ISSUER), fixed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
