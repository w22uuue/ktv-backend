package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import static com.ruoyi.web.controller.ktv.KtvService.*;

/** Secrets remain on the server. Payment/notification cryptography is provided by the official SDK. */
@Component
public class KtvWechat {
    @Value("${ktv.wechat.app-id:}") private String appId;
    @Value("${ktv.wechat.app-secret:}") private String appSecret;
    @Value("${ktv.wechat.merchant-id:}") private String merchant;
    @Value("${ktv.wechat.private-key-path:}") private String privateKey;
    @Value("${ktv.wechat.public-key-path:}") private String publicKey;
    @Value("${ktv.wechat.public-key-id:}") private String publicKeyId;
    @Value("${ktv.wechat.serial-number:}") private String serial;
    @Value("${ktv.wechat.api-v3-key:}") private String apiKey;
    @Value("${ktv.public-api-url:}") private String apiUrl;
    @Value("${ktv.wechat.code-page:pages/index/index}") private String codePage;
    @Value("${ktv.wechat.env-version:release}") private String envVersion;
    private JsapiServiceExtension payments;
    private RefundService refunds;
    private NotificationParser parser;
    private final RestTemplate http;
    private String accessToken;
    private long tokenExpires;
    public KtvWechat() { SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(4000);factory.setReadTimeout(6000);http=new RestTemplate(factory); }
    @PostConstruct public void initialize() {
        if(merchant.isEmpty()) return;
        require(!appId.isEmpty() && apiUrl.startsWith("https://") && !appSecret.isEmpty(),503,"微信支付配置不完整，须提供小程序及 HTTPS 回调域名");
        RSAPublicKeyConfig config=new RSAPublicKeyConfig.Builder().merchantId(merchant).privateKeyFromPath(privateKey).merchantSerialNumber(serial).publicKeyFromPath(publicKey).publicKeyId(publicKeyId).apiV3Key(apiKey).build();
        com.wechat.pay.java.core.http.HttpClient client=new DefaultHttpClientBuilder().config(config).connectTimeoutMs(3000).readTimeoutMs(6000).writeTimeoutMs(6000).disableRetryOnConnectionFailure().build();
        payments=new JsapiServiceExtension.Builder().config(config).httpClient(client).build();
        refunds=new RefundService.Builder().httpClient(client).build();parser=new NotificationParser(config);
    }
    public boolean loginReady() { return !appId.isEmpty() && !appSecret.isEmpty(); }
    public boolean paymentReady() { return payments!=null; }
    public JSONObject readiness() {return object("wechatLogin",loginReady(),"wechatPayment",paymentReady(),"miniCode",loginReady(),"miniProgramEnv",envVersion);}
    public String login(String code) {
        require(loginReady(),503,"小程序登录尚未配置");require(code!=null && code.length()<=256,400,"登录凭证无效");
        // Do not log this URL or response: they contain the app secret/session key.
        String body=http.getForObject("https://api.weixin.qq.com/sns/jscode2session?appid={app}&secret={secret}&js_code={code}&grant_type=authorization_code",String.class,appId,appSecret,code);
        JSONObject result=JSON.parseObject(body);require(result!=null && result.getIntValue("errcode")==0 && result.getString("openid")!=null,401,"微信登录失败，请重试");return result.getString("openid");
    }
    private synchronized String accessToken() {
        require(loginReady(),503,"小程序码尚未配置");
        if(accessToken!=null && System.currentTimeMillis()<tokenExpires) return accessToken;
        JSONObject data=object("grant_type","client_credential","appid",appId,"secret",appSecret,"force_refresh",false);
        JSONObject result=JSON.parseObject(http.postForObject("https://api.weixin.qq.com/cgi-bin/stable_token",data,String.class));
        require(result!=null && result.getString("access_token")!=null,502,"微信凭证获取失败，请检查服务端配置");
        accessToken=result.getString("access_token");tokenExpires=System.currentTimeMillis()+Math.max(60,result.getIntValue("expires_in")-120)*1000L;return accessToken;
    }
    public byte[] miniCode(String scene) {
        // The project is uploaded to the trial environment before release.  A
        // true check_path asks WeChat to validate the page in the released
        // version and rejects a valid trial-only page, so leave path checking
        // to the selected env_version during QR generation.
        byte[] bytes=http.postForObject("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token={token}",object("scene",scene,"page",codePage,"check_path",false,"env_version",envVersion,"width",430),byte[].class,accessToken());
        require(bytes!=null && bytes.length>8 && ((bytes[0]==(byte)0x89 && bytes[1]==0x50) || (bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8)),502,"小程序码生成失败，请核对发布页面和微信配置");return bytes;
    }
    public JSONObject prepay(JSONObject order) {
        require(paymentReady(),503,"微信支付尚未配置");
        PrepayRequest request=new PrepayRequest();request.setAppid(appId);request.setMchid(merchant);request.setOutTradeNo(order.getString("tradeNo"));request.setDescription("KTV 包厢餐食");request.setNotifyUrl(apiUrl+"/ktv-api/wechat/payment-notify");
        com.wechat.pay.java.service.payments.jsapi.model.Amount amount=new com.wechat.pay.java.service.payments.jsapi.model.Amount();amount.setTotal(order.getIntValue("total"));amount.setCurrency("CNY");request.setAmount(amount);
        Payer payer=new Payer();payer.setOpenid(order.getString("openid"));request.setPayer(payer);
        PrepayWithRequestPaymentResponse result=payments.prepayWithRequestPayment(request);
        return object("timeStamp",result.getTimeStamp(),"nonceStr",result.getNonceStr(),"package",result.getPackageVal(),"signType",result.getSignType(),"paySign",result.getPaySign());
    }
    public Transaction query(String trade) {
        require(paymentReady(),503,"微信支付尚未配置");QueryOrderByOutTradeNoRequest request=new QueryOrderByOutTradeNoRequest();request.setMchid(merchant);request.setOutTradeNo(trade);return payments.queryOrderByOutTradeNo(request);
    }
    public void close(String trade) {
        if(trade==null) return;require(paymentReady(),503,"微信支付尚未配置");
        CloseOrderRequest request=new CloseOrderRequest();request.setMchid(merchant);request.setOutTradeNo(trade);
        try { payments.closeOrder(request); }
        catch(com.wechat.pay.java.core.exception.ServiceException e) {
            if(!"ORDER_NOT_EXIST".equals(e.getErrorCode()) && !"ORDER_NOT_EXISTS".equals(e.getErrorCode()) && !"ORDERCLOSED".equals(e.getErrorCode())) throw e;
        }
    }
    private RequestParam notification(HttpServletRequest request,String body) {
        require(paymentReady(),503,"微信支付尚未配置");require(body.length()<=65536,400,"通知过大");
        String timestamp=request.getHeader("Wechatpay-Timestamp");
        require(timestamp!=null && timestamp.matches("[0-9]{10,12}") && Math.abs(Instant.now().getEpochSecond()-Long.parseLong(timestamp))<=300,401,"通知时间无效");
        return new RequestParam.Builder().serialNumber(request.getHeader("Wechatpay-Serial")).nonce(request.getHeader("Wechatpay-Nonce")).signature(request.getHeader("Wechatpay-Signature")).timestamp(timestamp).body(body).build();
    }
    public Transaction paymentNotice(HttpServletRequest request,String body) {return parserOrFail().parse(notification(request,body),Transaction.class);}
    private NotificationParser parserOrFail() {require(paymentReady(),503,"微信支付尚未配置");return parser;}
    public JSONObject refundNotice(HttpServletRequest request,String body) {
        Map<?,?> decoded=parserOrFail().parse(notification(request,body),Map.class);JSONObject value=JSON.parseObject(JSON.toJSONString(decoded));
        require(merchant.equals(value.getString("mchid")),401,"退款商户不匹配");return value;
    }
    public void acceptPayment(KtvService service,Transaction value) {
        require(appId.equals(value.getAppid()) && merchant.equals(value.getMchid()),401,"支付商户不匹配");
        if(value.getTradeState()!=Transaction.TradeStateEnum.SUCCESS) return;
        require(value.getAmount()!=null && "CNY".equals(value.getAmount().getCurrency()) && value.getPayer()!=null,400,"支付通知字段不完整");
        service.confirmPayment(value.getOutTradeNo(),value.getTransactionId(),value.getAmount().getTotal(),value.getSuccessTime(),value.getPayer().getOpenid());
    }
    public Refund refund(JSONObject order) {
        require(paymentReady(),503,"微信支付尚未配置");CreateRequest request=new CreateRequest();request.setOutTradeNo(order.getString("tradeNo"));request.setOutRefundNo(order.getString("refundNo"));request.setReason(order.getString("reason"));request.setNotifyUrl(apiUrl+"/ktv-api/wechat/refund-notify");
        AmountReq amount=new AmountReq();amount.setTotal(order.getLong("total"));amount.setRefund(order.getLong("total"));amount.setCurrency("CNY");request.setAmount(amount);return refunds.create(request);
    }
    public Refund queryRefund(String number) {require(paymentReady(),503,"微信支付尚未配置");QueryByOutRefundNoRequest request=new QueryByOutRefundNoRequest();request.setOutRefundNo(number);return refunds.queryByOutRefundNo(request);}
}
