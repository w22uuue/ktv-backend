package com.ruoyi.web.controller.ktv;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.UUID;

@Component
public class KtvRequestTrace extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(request.getRequestURI().startsWith(request.getContextPath()+"/ktv-api/")) {String id=UUID.randomUUID().toString();request.setAttribute("ktvRequestId",id);response.setHeader("X-Request-Id",id);}
        chain.doFilter(request,response);
    }
}
