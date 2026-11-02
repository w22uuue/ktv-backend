package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.web.controller.ktv.KtvService.object;

class KtvWechatTest {
    @TempDir Path directory;
    KtvWechat gateway;KeyPair keys;String body;MockHttpServletRequest request;
    final String apiKey="0123456789abcdef0123456789abcdef";
    @BeforeEach void setup() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        Path privatePath=directory.resolve("test-private.pem"),publicPath=directory.resolve("test-public.pem");
        Files.write(privatePath,pem("PRIVATE KEY",keys.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));Files.write(publicPath,pem("PUBLIC KEY",keys.getPublic().getEncoded()).getBytes(StandardCharsets.US_ASCII));
        gateway=new KtvWechat();set("appId","wx-test");set("appSecret","test-secret");set("merchant","test-merchant");set("privateKey",privatePath.toString());set("publicKey",publicPath.toString());set("publicKeyId","PUB_KEY_ID_TEST");set("serial","1234");set("apiKey",apiKey);set("apiUrl","https://example.invalid");gateway.initialize();
        JSONObject transaction=object("appid","wx-test","mchid","test-merchant","out_trade_no","test-trade","transaction_id","test-transaction","trade_state","SUCCESS","payer",object("openid","test-payer"),"amount",object("total",6400,"currency","CNY"),"success_time","2026-10-17T12:00:00+08:00");
        String nonce="0123456789ab",associated="transaction";Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
        body=object("id","test-notice","event_type","TRANSACTION.SUCCESS","resource_type","encrypt-resource","resource",object("algorithm","AEAD_AES_256_GCM","ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(transaction.toJSONString().getBytes(StandardCharsets.UTF_8))),"nonce",nonce,"associated_data",associated)).toJSONString();
        request=signed(body,String.valueOf(Instant.now().getEpochSecond()));
    }
    void set(String field,String value){ReflectionTestUtils.setField(gateway,field,value);}
    String pem(String kind,byte[] bytes){return "-----BEGIN "+kind+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+kind+"-----\n";}
    MockHttpServletRequest signed(String data,String time) throws Exception {String nonce="test-signature-nonce";Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(keys.getPrivate());signature.update((time+"\n"+nonce+"\n"+data+"\n").getBytes(StandardCharsets.UTF_8));MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("Wechatpay-Timestamp",time);r.addHeader("Wechatpay-Nonce",nonce);r.addHeader("Wechatpay-Serial","PUB_KEY_ID_TEST");r.addHeader("Wechatpay-Signature",Base64.getEncoder().encodeToString(signature.sign()));return r;}
    @Test void verifiesSignatureAndDecryptsOriginalBody(){assertEquals("test-transaction",gateway.paymentNotice(request,body).getTransactionId());}
    @Test void rejectsTamperedBody(){assertThrows(Exception.class,()->gateway.paymentNotice(request,body+" "));}
    @Test void rejectsWrongSignature(){request.removeHeader("Wechatpay-Signature");request.addHeader("Wechatpay-Signature",Base64.getEncoder().encodeToString(new byte[256]));assertThrows(Exception.class,()->gateway.paymentNotice(request,body));}
    @Test void rejectsExpiredNotification() throws Exception {MockHttpServletRequest expired=signed(body,String.valueOf(Instant.now().minusSeconds(601).getEpochSecond()));assertThrows(Exception.class,()->gateway.paymentNotice(expired,body));}
    @Test void rejectsDifferentMerchant(){set("merchant","another-merchant");assertThrows(Exception.class,()->gateway.acceptPayment(null,gateway.paymentNotice(request,body)));}
}
