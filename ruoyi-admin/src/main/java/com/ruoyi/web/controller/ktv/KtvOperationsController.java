package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.wechat.pay.java.service.refund.model.Refund;
import static com.ruoyi.web.controller.ktv.KtvService.*;

@Anonymous
@RestController
@RequestMapping("/ktv-api")
public class KtvOperationsController {
    private final KtvService service;private final KtvWechat wechat;private final KtvAccess access;
    @Value("${ktv.customer-url:}") private String customerUrl;
    public KtvOperationsController(KtvService service,KtvWechat wechat,KtvAccess access) {this.service=service;this.wechat=wechat;this.access=access;}
    @ModelAttribute public void headers(HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}
    @PostMapping("/sessions/wechat") public Object login(@RequestBody JSONObject body,HttpServletRequest request) {service.limit("wx:"+request.getRemoteAddr(),30,60);return service.wechatSession(wechat.login(text(body,"code",256)),body);}
    @PostMapping("/sessions/recovery") public Object recovery(HttpServletRequest request) {JSONObject who=access.who(request);service.limit("recovery:"+who.getString("subject"),5,600);return service.customerRecovery(who);}
    @GetMapping("/readiness") public Object readiness(HttpServletRequest request) {JSONObject who=access.who(request);require("admin".equals(who.getString("role")),403,"仅管理员可查看配置状态");return wechat.readiness();}
    @GetMapping("/orders") public Object orders(@RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) String room,@RequestParam(required=false) String supplierId,@RequestParam(required=false) Boolean paid,
            @RequestParam(required=false) String paymentMode,@RequestParam(required=false) String refundStatus,@RequestParam(required=false) Long minTotalCents,
            @RequestParam(required=false) Long maxTotalCents,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest request) {
        return service.adminOrders(access.who(request),keyword,status,room,supplierId,paid,paymentMode,refundStatus,minTotalCents,maxTotalCents,startTime,endTime,page,pageSize);
    }
    @GetMapping("/orders/export") public ResponseEntity<byte[]> export(@RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) String room,@RequestParam(required=false) String supplierId,@RequestParam(required=false) Boolean paid,
            @RequestParam(required=false) String paymentMode,@RequestParam(required=false) String refundStatus,@RequestParam(required=false) Long minTotalCents,
            @RequestParam(required=false) Long maxTotalCents,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest request) {
        List<JSONObject> rows=service.adminOrderRows(access.who(request),keyword,status,room,supplierId,paid,paymentMode,refundStatus,minTotalCents,maxTotalCents,startTime,endTime);
        StringBuilder csv=new StringBuilder("\uFEFF订单号,包厢,接单账号,顾客标识,订单状态,支付状态,支付方式,退款状态,金额(分),创建时间\n");
        for(JSONObject row:rows) csv.append(csv(row.get("id"))).append(',').append(csv(row.get("room"))).append(',').append(csv(row.get("supplierId"))).append(',')
            .append(csv(row.get("customerId"))).append(',').append(csv(row.get("status"))).append(',').append(csv(row.getBooleanValue("paid")?"已付款":"待付款")).append(',')
            .append(csv(row.get("paymentMode"))).append(',').append(csv(row.get("refundStatus"))).append(',').append(csv(row.get("totalCents"))).append(',').append(csv(row.get("createdAt"))).append('\n');
        byte[] bytes=csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ktv-orders.csv").body(bytes);
    }
    private String csv(Object value) { String s=value==null?"":String.valueOf(value); return "\""+s.replace("\"","\"\"")+"\""; }
    @PostMapping("/orders/batch-action") public Object batch(@RequestBody JSONObject body,HttpServletRequest request) {
        return service.batchOrderAction(access.who(request),body.getJSONArray("ids"),text(body,"action",40),body.getString("reason"));
    }
    @GetMapping("/orders/{id}") public Object order(@PathVariable String id,HttpServletRequest request) {JSONObject who=access.who(request);JSONObject result=service.order(who,id);return "admin".equals(who.getString("role"))?object("order",result):result;}
    @PostMapping("/orders/{id}/cancel") public Object cancel(@PathVariable String id,HttpServletRequest request) {JSONObject who=access.who(request);JSONObject order=service.order(who,id);require(!"supplier".equals(who.getString("role")),403,"食堂无权取消订单");require(Arrays.asList("unpaid","cancelled").contains(order.getString("status")),409,"订单已支付，请联系管理员处理退款");wechat.close(service.paymentTrade(id));return service.cancel(who,id);}
    @PostMapping("/orders/{id}/reassign") public Object reassign(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) {return service.reassign(access.who(request),id,body);}
    @PostMapping("/admin/password") public Object password(@RequestBody JSONObject body,HttpServletRequest request) {JSONObject who=access.who(request);service.limit("rotate:"+who.getString("subject"),5,600);return service.rotatePassword(who,body);}
    @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="1") int page,HttpServletRequest request) {return service.audits(access.who(request),page);}
    @PostMapping("/rooms/{id}/qr") public Object qr(@PathVariable String id,@RequestParam(defaultValue="false") boolean rotate,@RequestParam(defaultValue="h5") String type,HttpServletRequest request) throws Exception {
        JSONObject code=service.roomCode(access.who(request),id,rotate);String scene=code.getString("scene");byte[] bytes;
        if("mini".equals(type)) bytes=wechat.miniCode(scene);
        else {
            require("h5".equals(type),400,"二维码类型无效");
            require(customerUrl.startsWith("https://") || customerUrl.startsWith("http://127.0.0.1:"),503,"请配置顾客端访问网址");
            String url=customerUrl+(customerUrl.contains("?")?"&":"?")+"scene="+scene;code.put("url",url);
            ByteArrayOutputStream output=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,430,430),"PNG",output);bytes=output.toByteArray();
        }
        String image="data:image/"+(bytes[0]==(byte)0xff?"jpeg":"png")+";base64,"+java.util.Base64.getEncoder().encodeToString(bytes);
        code.put("image",image);code.put("qrcodeUrl",image);return code;
    }
    /** Alias retained for the PC console and older app builds. */
    @PostMapping("/rooms/{id}/qrcode") public Object qrCompat(@PathVariable String id,@RequestParam(defaultValue="false") boolean rotate,@RequestParam(defaultValue="h5") String type,HttpServletRequest request) throws Exception {return qr(id,rotate,type,request);}
    @GetMapping("/scan/{scene}") public Object scan(@PathVariable String scene,HttpServletRequest request) {service.limit("scan:"+request.getRemoteAddr(),120,60);return service.resolveRoom(scene);}
    @PostMapping("/orders/{id}/prepay") public Object prepay(@PathVariable String id,HttpServletRequest request) {JSONObject who=access.who(request);service.limit("pay:"+who.getString("subject"),10,60);require(wechat.paymentReady(),503,"微信支付尚未配置");return wechat.prepay(service.paymentIntent(who,id));}
    @PostMapping("/orders/{id}/pay") public Object prepayCompat(@PathVariable String id,HttpServletRequest request) {return prepay(id,request);}
    @PostMapping("/orders/{id}/payment-query") public Object query(@PathVariable String id,HttpServletRequest request) {JSONObject who=access.who(request);JSONObject order=service.order(who,id);require(!"supplier".equals(who.getString("role")),403,"无权查询支付");service.limit("query:"+who.getString("subject"),60,60);String trade=service.paymentTrade(id);if(trade!=null) wechat.acceptPayment(service,wechat.query(trade));return service.order(who,id);}
    @GetMapping("/orders/{id}/payment") public Object queryCompat(@PathVariable String id,HttpServletRequest request) {return query(id,request);}
    @PostMapping("/wechat/payment-notify") public ResponseEntity<Void> paymentNotify(@RequestBody String body,HttpServletRequest request) {wechat.acceptPayment(service,wechat.paymentNotice(request,body));return ResponseEntity.noContent().build();}
    @PostMapping("/orders/{id}/refund-request") public Object refundRequest(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) {return service.requestRefund(access.who(request),id,body);}
    @PostMapping("/orders/{id}/refund-review") public Object refundReview(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) {
        JSONObject result=service.reviewRefund(access.who(request),id,body);
        if(result.getBooleanValue("requiresProvider")) { Refund paid=wechat.refund(result); service.confirmRefund(paid.getOutRefundNo(),paid.getStatus().name(),paid.getAmount().getTotal(),paid.getAmount().getRefund()); result.put("refundStatus",paid.getStatus().name()); }
        return result;
    }
    @PostMapping("/orders/{id}/refund") public Object refund(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) {
        require(wechat.paymentReady(),503,"微信支付尚未配置");JSONObject intent=service.refundIntent(access.who(request),id,body);Refund result=wechat.refund(intent);service.confirmRefund(result.getOutRefundNo(),result.getStatus().name(),result.getAmount().getTotal(),result.getAmount().getRefund());return object("id",id,"refundStatus",result.getStatus().name());
    }
    @PostMapping("/wechat/refund-notify") public ResponseEntity<Void> refundNotify(@RequestBody String body,HttpServletRequest request) {JSONObject notice=wechat.refundNotice(request,body),amount=notice.getJSONObject("amount");require(amount!=null,400,"退款金额缺失");service.confirmRefund(notice.getString("out_refund_no"),notice.getString("refund_status"),amount.getLongValue("total"),amount.getLongValue("refund"));return ResponseEntity.noContent().build();}
}
