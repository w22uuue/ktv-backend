package com.ruoyi.framework.security.filter;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.Locale;

/** Also protects files uploaded before the extension allow-list was hardened. */
@Component
public class UploadContentFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (path.startsWith(request.getContextPath() + "/profile/")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'; script-src 'none'; object-src 'none'");
            if (path.matches(".*\\.(html?|svg|xml|js|mjs|swf)(?:;.*)?$")) {
                response.sendError(404); return;
            }
            if (!path.matches(".*\\.(png|jpe?g|gif|bmp|webp)$")) response.setHeader("Content-Disposition", "attachment");
        }
        chain.doFilter(request, response);
    }
}
