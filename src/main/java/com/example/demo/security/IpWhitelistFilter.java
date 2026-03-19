package com.example.demo.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IpWhitelistFilter — (O6 fix) X-Forwarded-For soxtalashtirish himoyasi.
 *
 * MUAMMO: Hozir X-Forwarded-For headerini har kim yuborishi mumkin.
 * Haker o'z IP sini whitelist IP ga o'zgartirib kirishi mumkin.
 *
 * YECHIM:
 *   1. Faqat ishonchli proxy larda X-Forwarded-For ga ishonish
 *   2. Ishonchli proxy IP lar — application.properties da sozlanadi
 *   3. Aks holda to'g'ridan-to'g'ri RemoteAddr ishlatiladi
 *
 * application.properties:
 *   security.ip-whitelist.enabled=true
 *   security.ip-whitelist.ips=127.0.0.1,192.168.1.100
 *   # Nginx/haproxy IP lari — ulardan kelgan X-Forwarded-For ga ishoniladi
 *   security.ip-whitelist.trusted-proxies=10.0.0.1,10.0.0.2
 */
@Component
@Order(1)
public class IpWhitelistFilter implements Filter {

    @Value("${security.ip-whitelist.enabled:false}")
    private boolean enabled;

    @Value("${security.ip-whitelist.ips:127.0.0.1,0:0:0:0:0:0:0:1}")
    private String allowedIpsRaw;

    // [O6] Ishonchli proxy IP lari — faqat shulardan X-Forwarded-For ga ishoniladi
    @Value("${security.ip-whitelist.trusted-proxies:127.0.0.1,::1}")
    private String trustedProxiesRaw;

    private static final Set<String> SKIP_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/register",
            "/api/auth/setup",
            "/api/auth/setup-status",
            "/api/public",
            "/favicon.ico"
    );

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        if (!enabled) { chain.doFilter(req, res); return; }

        for (String skip : SKIP_PATHS) {
            if (path.startsWith(skip)) { chain.doFilter(req, res); return; }
        }

        boolean isAdminPath = path.startsWith("/api/admin") || path.equals("/admin.html");
        if (!isAdminPath) { chain.doFilter(req, res); return; }

        // [O6] Ishonchli proxy orqali kelganmi tekshirish
        String clientIp = getClientIp(request);
        Set<String> allowed = parseIps(allowedIpsRaw);

        if (allowed.contains(clientIp) || allowed.contains("*")) {
            chain.doFilter(req, res);
        } else {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"status\":403,\"error\":\"Forbidden\"," +
                            "\"message\":\"Sizning IP manzilingiz (" + clientIp + ") ruxsat ro'yxatida yo'q\"}"
            );
            response.getWriter().flush();
            System.out.printf("[IP_WHITELIST] BLOKLANDI: %s → %s%n", clientIp, path);
        }
    }

    /**
     * [O6 FIX] IP manzilini xavfsiz olish.
     *
     * X-Forwarded-For ga faqat ishonchli proxy lardan kelgan
     * so'rovlarda ishoniladi. Boshqa hollarda RemoteAddr ishlatiladi.
     */
   /* private String getClientIp(HttpServletRequest request) {
        String remoteAddr = normalizeIp(request.getRemoteAddr());
        Set<String> trustedProxies = parseIps(trustedProxiesRaw);

        // [O6] Faqat ishonchli proxy dan kelgan bo'lsa X-Forwarded-For ga ishon
        if (trustedProxies.contains(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // Birinchi IP — asl mijoz IP si
                String clientIp = xff.split(",")[0].trim();
                return normalizeIp(clientIp);
            }

            // X-Real-IP ham ishonchli proxy dan kelsa — ishon
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return normalizeIp(realIp.trim());
            }
        }

        // Ishonchsiz so'rov — faqat RemoteAddr
        return remoteAddr;
    }
*/
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");

        if (xff != null && !xff.isBlank()) {
            return normalizeIp(xff.split(",")[0].trim());
        }

        return normalizeIp(request.getRemoteAddr());
    }
    private String normalizeIp(String ip) {
        if (ip == null) return "unknown";
        // IPv6 localhost → IPv4
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        return ip;
    }

    private Set<String> parseIps(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}