package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 微信小程序服务端能力：登录换取 openid，以及生成包厢专属小程序码。
 * AppSecret 只从服务端环境变量读取，绝不返回给前端。
 */
@Service
public class WechatMiniProgramService {
    private static final String API = "https://api.weixin.qq.com";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Value("${ktv.wechat.app-id:}")
    private String appId;
    @Value("${ktv.wechat.app-secret:}")
    private String appSecret;
    @Value("${ktv.wechat.code-page:pages/index/index}")
    private String codePage;
    @Value("${ktv.wechat.env-version:release}")
    private String envVersion;
    @Value("${ktv.upload-path:./data/ktv-uploads}")
    private String uploadPath;

    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    public JSONObject exchangeCode(String code) {
        requireConfigured("微信小程序登录");
        KtvService.require(code != null && !code.trim().isEmpty() && code.length() <= 512, 400, "微信登录凭证无效");
        String query;
        try {
            query = "appid=" + enc(appId) + "&secret=" + enc(appSecret)
                    + "&js_code=" + enc(code.trim()) + "&grant_type=authorization_code";
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录参数生成失败");
        }
        JSONObject result = parseObject(get(API + "/sns/jscode2session?" + query), "微信登录服务异常");
        checkWechatError(result, "微信登录失败");
        String openid = result.getString("openid");
        KtvService.require(openid != null && !openid.trim().isEmpty(), 502, "微信未返回用户身份");
        JSONObject answer = new JSONObject();
        answer.put("openid", openid);
        if (result.getString("unionid") != null) answer.put("unionid", result.getString("unionid"));
        return answer;
    }

    public JSONObject generateRoomCode(String roomId) {
        requireConfigured("小程序码生成");
        KtvService.require(roomId != null && roomId.matches("[A-Z0-9\\u4e00-\\u9fa5-]{1,12}"), 400, "包厢编号无效");
        JSONObject body = new JSONObject();
        // Production resolves the scene through ktv_qr and only accepts a
        // 32-character hexadecimal value. Keep this legacy service aligned
        // with KtvService instead of emitting the old room=A08 format.
        String scene = UUID.randomUUID().toString().replace("-", "");
        body.put("scene", scene);
        body.put("page", codePage);
        body.put("check_path", false);
        body.put("env_version", envVersion);
        body.put("width", 430);
        body.put("auto_color", false);
        body.put("is_hyaline", false);

        String encodedToken;
        try {
            encodedToken = enc(getAccessToken());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信接口参数生成失败");
        }
        HttpResult response = postJson(API + "/wxa/getwxacodeunlimit?access_token=" + encodedToken, body.toJSONString());
        if (response.status < 200 || response.status >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信小程序码服务暂不可用");
        }
        if (looksLikeJson(response.body)) {
            JSONObject error = parseObject(new String(response.body, StandardCharsets.UTF_8), "微信小程序码生成失败");
            checkWechatError(error, "微信小程序码生成失败");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信小程序码生成失败");
        }
        try {
            Path directory = Paths.get(uploadPath).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String filename = UUID.randomUUID().toString() + ".png";
            Files.write(directory.resolve(filename), response.body);
            return KtvService.object(
                    "scene", scene,
                    "qrcodeUrl", "/ktv-api/assets/" + filename,
                    "generatedAt", LocalDateTime.now(BEIJING).format(TIME));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存小程序码失败");
        }
    }

    private String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessTokenExpiresAt) return accessToken;
        synchronized (this) {
            now = System.currentTimeMillis();
            if (accessToken != null && now < accessTokenExpiresAt) return accessToken;
            String query;
            try {
                query = "grant_type=client_credential&appid=" + enc(appId) + "&secret=" + enc(appSecret);
            } catch (Exception error) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信接口参数生成失败");
            }
            JSONObject result = parseObject(get(API + "/cgi-bin/token?" + query), "微信接口调用失败");
            checkWechatError(result, "微信接口调用失败");
            String token = result.getString("access_token");
            Integer expires = result.getInteger("expires_in");
            KtvService.require(token != null && !token.isEmpty(), 502, "微信未返回接口令牌");
            accessToken = token;
            accessTokenExpiresAt = System.currentTimeMillis() + Math.max(60, expires == null ? 7200 : expires) * 1000L;
            return accessToken;
        }
    }

    private void requireConfigured(String action) {
        if (appId == null || appId.trim().isEmpty() || appSecret == null || appSecret.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    action + "未配置微信小程序 AppSecret，请在服务端设置 WECHAT_APP_SECRET");
        }
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static JSONObject parseObject(String value, String message) {
        try {
            return JSON.parseObject(value);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        }
    }

    private static void checkWechatError(JSONObject result, String prefix) {
        Integer code = result.getInteger("errcode");
        if (code != null && code != 0) {
            String detail = result.getString("errmsg");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    prefix + (detail == null || detail.isEmpty() ? "" : "：" + detail));
        }
    }

    private static boolean looksLikeJson(byte[] body) {
        for (byte value : body) {
            if (!Character.isWhitespace((char) value)) return value == '{' || value == '[';
        }
        return true;
    }

    private static String get(String url) {
        HttpResult result = request("GET", url, null, null);
        if (result.status < 200 || result.status >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信接口暂不可用");
        }
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static HttpResult postJson(String url, String body) {
        return request("POST", url, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8");
    }

    private static HttpResult request(String method, String url, byte[] body, String contentType) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
            if (body != null) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResult(status, readAll(stream));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信接口连接失败，请检查服务器网络");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static final class HttpResult {
        private final int status;
        private final byte[] body;

        private HttpResult(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }
}
