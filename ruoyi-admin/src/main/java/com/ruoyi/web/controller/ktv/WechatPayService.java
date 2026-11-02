package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/** 微信支付 v3 的服务端签名、JSAPI 下单和回调解密。 */
@Service
public class WechatPayService {
    private static final String API = "https://api.mch.weixin.qq.com";

    @Value("${ktv.wechat.app-id:}")
    private String appId;
    @Value("${ktv.wechat.mch-id:}")
    private String mchId;
    @Value("${ktv.wechat.api-v3-key:}")
    private String apiV3Key;
    @Value("${ktv.wechat.cert-serial:}")
    private String certSerial;
    @Value("${ktv.wechat.private-key-path:}")
    private String privateKeyPath;
    @Value("${ktv.wechat.platform-cert-path:}")
    private String platformCertPath;
    @Value("${ktv.wechat.notify-url:}")
    private String notifyUrl;

    private volatile PrivateKey privateKey;
    private volatile X509Certificate platformCertificate;

    public JSONObject createJsapiPayment(String outTradeNo, String description, int totalCents, String openid, String prepayId) {
        requireConfigured(false);
        if (prepayId == null || prepayId.isEmpty()) {
            JSONObject body = KtvService.object(
                    "appid", appId,
                    "mchid", mchId,
                    "description", description,
                    "out_trade_no", outTradeNo,
                    "notify_url", notifyUrl,
                    "amount", KtvService.object("total", totalCents, "currency", "CNY"),
                    "payer", KtvService.object("openid", openid));
            JSONObject response = requestJson("POST", "/v3/pay/transactions/jsapi", body.toJSONString());
            String returned = response.getString("prepay_id");
            KtvService.require(returned != null && !returned.isEmpty(), 502, "微信支付未返回预支付信息");
            prepayId = returned;
        }
        JSONObject result = signJsapiPayment(prepayId);
        result.put("prepayId", prepayId);
        return result;
    }

    public JSONObject signJsapiPayment(String prepayId) {
        requireConfigured(false);
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonceStr = nonce();
        String packageValue = "prepay_id=" + prepayId;
        String message = appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
        return KtvService.object(
                "appId", appId,
                "timeStamp", timeStamp,
                "nonceStr", nonceStr,
                "package", packageValue,
                "signType", "RSA",
                "paySign", sign(message));
    }

    public JSONObject queryOrder(String outTradeNo) {
        requireConfigured(false);
        try {
            return requestJson("GET", "/v3/pay/transactions/out-trade-no/"
                    + URLEncoder.encode(outTradeNo, "UTF-8") + "?mchid="
                    + URLEncoder.encode(mchId, "UTF-8"), "");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信支付查询失败");
        }
    }

    public JSONObject createRefund(String outTradeNo, String outRefundNo, String reason, int totalCents) {
        requireConfigured(false);
        JSONObject body = KtvService.object(
                "out_trade_no", outTradeNo,
                "out_refund_no", outRefundNo,
                "reason", reason,
                "amount", KtvService.object("refund", totalCents, "total", totalCents, "currency", "CNY"));
        return requestJson("POST", "/v3/refund/domestic/refunds", body.toJSONString());
    }

    public JSONObject decryptNotify(String timestamp, String nonce, String signature, String body) {
        requireConfigured(true);
        KtvService.require(timestamp != null && nonce != null && signature != null && body != null, 400, "支付回调数据不完整");
        try {
            long received = Long.parseLong(timestamp);
            KtvService.require(Math.abs(System.currentTimeMillis() / 1000L - received) <= 300, 401, "支付回调已过期");
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "支付回调时间无效");
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(getPlatformCertificate().getPublicKey());
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            KtvService.require(verifier.verify(Base64.getDecoder().decode(signature)), 401, "支付回调签名无效");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付回调验签失败");
        }
        try {
            JSONObject root = JSON.parseObject(body);
            JSONObject resource = root.getJSONObject("resource");
            KtvService.require(resource != null, 400, "支付回调资源缺失");
            byte[] cipherText = Base64.getDecoder().decode(resource.getString("ciphertext"));
            byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
            KtvService.require(key.length == 32, 503, "微信支付 API v3 密钥必须为 32 位");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, resource.getString("nonce").getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD(resource.getString("associated_data").getBytes(StandardCharsets.UTF_8));
            return JSON.parseObject(new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8));
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支付回调解密失败");
        }
    }

    private JSONObject requestJson(String method, String path, String body) {
        HttpURLConnection connection = null;
        try {
            String nonce = nonce();
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
            String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
            connection = (HttpURLConnection) new URL(API + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", authorization(nonce, timestamp, sign(message)));
            if (!"GET".equals(method)) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String content = new String(readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream()), StandardCharsets.UTF_8);
            JSONObject result = JSON.parseObject(content);
            if (status < 200 || status >= 300) {
                String detail = result == null ? null : result.getString("message");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信支付请求失败" + (detail == null ? "" : "：" + detail));
            }
            return result;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信支付服务连接失败");
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信支付响应无效");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String authorization(String nonce, String timestamp, String signature) {
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchId + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + certSerial + "\"";
    }

    private String sign(String value) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(getPrivateKey());
            signer.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付商户私钥不可用");
        }
    }

    private PrivateKey getPrivateKey() {
        if (privateKey != null) return privateKey;
        synchronized (this) {
            if (privateKey != null) return privateKey;
            try {
                String pem = new String(Files.readAllBytes(Paths.get(privateKeyPath)), StandardCharsets.UTF_8)
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
                return privateKey;
            } catch (Exception error) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付商户私钥不可用");
            }
        }
    }

    private X509Certificate getPlatformCertificate() {
        if (platformCertificate != null) return platformCertificate;
        synchronized (this) {
            if (platformCertificate != null) return platformCertificate;
            try (InputStream stream = Files.newInputStream(Paths.get(platformCertPath))) {
                platformCertificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
                return platformCertificate;
            } catch (Exception error) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付平台证书不可用");
            }
        }
    }

    private void requireConfigured(boolean callback) {
        boolean complete = value(appId) && value(mchId) && value(apiV3Key) && value(certSerial)
                && value(privateKeyPath) && value(notifyUrl);
        if (callback) complete = complete && value(platformCertPath);
        if (!complete) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "微信支付尚未配置完整，请在服务端设置商户号、API v3 密钥、证书和回调地址");
        }
    }

    private static boolean value(String value) { return value != null && !value.trim().isEmpty(); }
    private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }

    private static byte[] readAll(InputStream stream) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
