package com.ruoyi.framework.security.filter;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.TokenService;

/**
 * token过滤器 验证token有效性
 * 
 * @author ruoyi
 */
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter
{
    @Autowired
    private TokenService tokenService;

    @Autowired
    private com.ruoyi.system.service.ISysUserService userService;
    @Autowired
    private com.ruoyi.framework.web.service.SysPermissionService permissionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        try {
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (loginUser != null) {
            com.ruoyi.common.core.domain.entity.SysUser current = userService.selectUserById(loginUser.getUserId());
            if (current == null || !"0".equals(current.getStatus()) || !"0".equals(current.getDelFlag())
                    || !java.util.Objects.equals(SecurityUtils.credentialVersion(current.getPassword()), loginUser.getCredentialVersion())) {
                tokenService.delLoginUser(loginUser.getToken());
                loginUser = null;
            } else {
                loginUser.setUser(current);
                loginUser.setPermissions(permissionService.getMenuPermission(current));
            }
        }
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNull(SecurityUtils.getAuthentication()))
        {
            tokenService.verifyToken(loginUser);
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }
        chain.doFilter(request, response);
        } catch (org.springframework.dao.DataAccessException error) {
            response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"msg\":\"身份服务暂不可用，请稍后重试\"}");
        }
    }
}
