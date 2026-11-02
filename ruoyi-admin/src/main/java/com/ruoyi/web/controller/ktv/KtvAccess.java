package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import javax.servlet.http.HttpServletRequest;

@Component
public class KtvAccess {
    private final KtvService service;
    public KtvAccess(KtvService service) {this.service=service;}
    public JSONObject who(HttpServletRequest request) {
        if(request.getHeader("X-KTV-Token")!=null) return service.identity(request.getHeader("X-KTV-Token"));
        Authentication auth=SecurityUtils.getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof LoginUser) {
            LoginUser user=(LoginUser)auth.getPrincipal();
            KtvService.require(user.getUser().isAdmin() || user.getPermissions().contains("ktv:manage"),403,"当前后台账号没有 KTV 运营权限");
            return KtvService.object("role","admin","subject","pc-"+user.getUserId());
        }
        return service.identity(null);
    }
}
