package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wechat.pay.java.service.refund.model.Refund;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.*;
import static com.ruoyi.web.controller.ktv.KtvService.object;

/** Provider calls are outside database transactions. Stable payment/refund numbers make retries safe. */
@Configuration
@EnableScheduling
public class KtvMaintenance {
    private static final Logger log=LoggerFactory.getLogger(KtvMaintenance.class);
    private final KtvService service;private final KtvWechat wechat;private final JdbcTemplate jdbc;
    @Value("${ktv.unpaid-timeout-minutes:15}") private int timeout;
    public KtvMaintenance(KtvService service,KtvWechat wechat,JdbcTemplate jdbc){this.service=service;this.wechat=wechat;this.jdbc=jdbc;}
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void reconcile() {
        JSONObject system=object("role","admin","subject","reconciliation");
        List<String> unpaid;
        try {unpaid=jdbc.queryForList("SELECT payload FROM ktv_order WHERE status='unpaid' ORDER BY id LIMIT 50",String.class);}
        catch(Exception e){log.warn("KTV reconciliation database unavailable");return;}
        for(String payload:unpaid) {
            JSONObject order=JSON.parseObject(payload);String id=order.getString("id"),trade=service.paymentTrade(id);
            try {
                if(trade!=null) {
                    if(!wechat.paymentReady()) continue;
                    try {wechat.acceptPayment(service,wechat.query(trade));}
                    catch(com.wechat.pay.java.core.exception.ServiceException e) {if(!Arrays.asList("ORDER_NOT_EXIST","ORDER_NOT_EXISTS").contains(e.getErrorCode()))throw e;}
                    if(service.order(system,id).getBooleanValue("paid")) continue;
                }
                if(timeout>0 && LocalDateTime.parse(order.getString("createdAt")).plusMinutes(timeout).isBefore(LocalDateTime.now())) {
                    wechat.close(trade);service.cancel(system,id);
                }
            } catch(Exception e){log.warn("KTV payment reconciliation pending for order {} ({})",id,e.getClass().getSimpleName());}
        }
        if(!wechat.paymentReady())return;
        for(Map<String,Object> row:jdbc.queryForList("SELECT p.refund_no,p.trade_no,o.payload FROM ktv_payment p JOIN ktv_order o ON o.id=p.order_id WHERE p.refund_status IN ('REQUESTED','PROCESSING') ORDER BY p.created_at LIMIT 30")) {
            String number=(String)row.get("refund_no");
            try {
                Refund result;
                try {result=wechat.queryRefund(number);}
                catch(com.wechat.pay.java.core.exception.ServiceException e) {
                    if(!"RESOURCE_NOT_EXISTS".equals(e.getErrorCode()))throw e;
                    JSONObject order=JSON.parseObject((String)row.get("payload"));result=wechat.refund(object("tradeNo",row.get("trade_no"),"refundNo",number,"total",order.getLongValue("totalCents"),"reason","管理员已申请退款，系统恢复处理"));
                }
                service.confirmRefund(result.getOutRefundNo(),result.getStatus().name(),result.getAmount().getTotal(),result.getAmount().getRefund());
            }catch(Exception e){log.warn("KTV refund reconciliation pending ({})",e.getClass().getSimpleName());}
        }
    }
}
