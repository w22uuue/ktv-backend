package com.ruoyi.common.utils;

import java.nio.charset.StandardCharsets;
import com.ruoyi.common.exception.ServiceException;

public final class PasswordPolicy {
    private PasswordPolicy() { }
    public static boolean valid(String password) {
        return password != null && password.length() >= 12 && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    public static void require(String password) {
        if (!valid(password)) throw new ServiceException("密码至少 12 个字符且最多 72 个 UTF-8 字节", 400);
    }
}
