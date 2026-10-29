package dev.clearflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Component
public class JwtService {

    private final byte[] secret;
    private final long expireHours;

    public JwtService(@Value("${clearflow.jwt.secret}") String secret,
                      @Value("${clearflow.jwt.expire-hours:24}") long expireHours) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expireHours = expireHours;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret);
    }

    public String generate(MemberPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(principal.id()))
                .claim("name", principal.name())
                .claim("perm", principal.perm())
                .claim("adm", principal.admin())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, TimeUnit.HOURS.toChronoUnit())))
                .signWith(key())
                .compact();
    }

    public MemberPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload();
        return new MemberPrincipal(
                Long.valueOf(claims.getSubject()),
                claims.get("name", String.class),
                claims.get("perm", String.class),
                Boolean.TRUE.equals(claims.get("adm", Boolean.class)));
    }
}
