package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import com.ruoyi.common.utils.PasswordPolicy;

/** Small shared catalog plus indexed, individually persisted orders. Writes are transactional. */
@Service
public class KtvService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    @Value("${ktv.bootstrap-admin-password:}") private String adminPassword;
    @Value("${ktv.bootstrap-supplier-password:}") private String supplierPassword;
    @Value("${ktv.demo-payment-enabled:false}") private boolean demoPayment;
    @Value("${ktv.production:false}") private boolean production;
    @Value("${ktv.upload-path:./data/ktv-uploads}") private String uploadPath;

    public KtvService(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
    public static JSONObject object(Object... pairs) {
        JSONObject result=new JSONObject();
        for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    static void require(boolean condition, int status, String message) {
        if(!condition) throw new ResponseStatusException(HttpStatus.valueOf(status),message);
    }
    static String text(JSONObject value,String key,int max) {
        require(value!=null,400,"请求数据不能为空");
        String s=value.getString(key);
        require(s!=null && !s.trim().isEmpty() && s.length()<=max,400,"无效字段："+key);
        return s.trim();
    }
    static String hash(String value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String token() {
        byte[] data=new byte[32]; new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
    static JSONObject copy(JSONObject value) { return JSON.parseObject(value.toJSONString()); }
    static JSONObject find(JSONArray array,String id) {
        for(int i=0;i<array.size();i++) if(id.equals(array.getJSONObject(i).getString("id"))) return array.getJSONObject(i);
        return null;
    }
    /** Shared category registry. Older state payloads are upgraded lazily from product snapshots. */
    private JSONArray categories(JSONObject state) {
        JSONArray result=state.getJSONArray("categories");
        if(result==null) {
            result=new JSONArray(Arrays.asList("主食","小吃"));
            JSONArray products=state.getJSONArray("products");
            if(products!=null) for(Object raw:products) {
                String category=((JSONObject)raw).getString("category");
                if(category!=null && !category.trim().isEmpty() && !result.contains(category)) result.add(category);
            }
            state.put("categories",result);
        } else {
            if(!result.contains("主食")) result.add(0,"主食");
            if(!result.contains("小吃")) result.add(1,"小吃");
        }
        return result;
    }
    private static boolean validCategory(String value) {
        return value!=null && value.matches("[一-龥A-Za-z0-9 _-]{1,20}") && !value.trim().isEmpty();
    }
    private static long activeOrders(JdbcTemplate jdbc,String column,String value) {
        require(Arrays.asList("supplier_id","room").contains(column),400,"查询条件无效");
        return jdbc.queryForObject("SELECT COUNT(*) FROM ktv_order WHERE "+column+"=? AND status IN ('unpaid','pending','preparing','delivering','refunding')",Long.class,value);
    }
    private JSONObject read() { return JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1",String.class)); }
    private <T> T change(Function<JSONObject,T> action) {return change(null,action);}
    private <T> T change(String orderId,Function<JSONObject,T> action) {
        return tx.execute(status -> {
            JSONObject state=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1 FOR UPDATE",String.class));
            JSONArray orders=new JSONArray();
            if(orderId!=null) for(String payload:jdbc.queryForList("SELECT payload FROM ktv_order WHERE id=? FOR UPDATE",String.class,orderId)) orders.add(JSON.parseObject(payload));
            state.put("orders",orders);
            T result=action.apply(state);
            for(Object raw:state.getJSONArray("orders")) saveOrder((JSONObject)raw);
            state.remove("orders");
            state.put("revision",state.getLongValue("revision")+1);
            jdbc.update("UPDATE ktv_shared_state SET payload=? WHERE id=1",state.toJSONString());
            return result;
        });
    }
    private void saveOrder(JSONObject o) {
        // Orders written by the first JSON-only version did not always carry
        // a room field.  Keep those historical rows migratable instead of
        // failing the whole startup transaction on the NOT NULL index column.
        String room=o.getString("room");
        if(room==null) room="";
        jdbc.update("INSERT INTO ktv_order(id,customer_id,supplier_id,room,status,paid,request_key,payload) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE supplier_id=VALUES(supplier_id),room=VALUES(room),status=VALUES(status),paid=VALUES(paid),payload=VALUES(payload)",o.getString("id"),o.getString("customerId"),o.getString("supplierId"),room,o.getString("status"),o.getBooleanValue("paid"),o.getString("requestKey"),o.toJSONString());
    }
    private void migrateRoomIndex() {
        boolean exists=columnExists("ktv_order", "room");
        if(!exists) jdbc.execute("ALTER TABLE ktv_order ADD COLUMN room VARCHAR(12) NOT NULL DEFAULT ''");
        // Empty values also resume an interrupted backfill without replacing any business data.
        while(true) {
            List<String> pending=jdbc.queryForList("SELECT payload FROM ktv_order WHERE room='' ORDER BY id LIMIT 500",String.class);
            if(pending.isEmpty()) break;
            int changed=tx.execute(status -> {
                int updated=0;
                for(String raw:pending){
                    JSONObject order=JSON.parseObject(raw);
                    String room=order.getString("room");
                    // A legacy order can legitimately lack room metadata. It
                    // remains searchable by id while valid room metadata is
                    // backfilled in batches on subsequent restarts.
                    if(room==null || room.trim().isEmpty() || room.trim().length()>12) continue;
                    jdbc.update("UPDATE ktv_order SET room=? WHERE id=?",room.trim(),order.getString("id"));
                    updated++;
                }
                return updated;
            });
            if(changed==0) break;
        }
    }
    /**
     * Database metadata differs in case sensitivity between MySQL and H2.
     * Inspect the returned rows instead of passing a case-sensitive table or
     * column pattern so startup migrations remain idempotent in both engines.
     */
    private boolean columnExists(String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            java.sql.DatabaseMetaData metadata=connection.getMetaData();
            try(java.sql.ResultSet columns=metadata.getColumns(connection.getCatalog(),null,null,null)) {
                while(columns.next()) {
                    String table=columns.getString("TABLE_NAME");
                    String column=columns.getString("COLUMN_NAME");
                    if(tableName.equalsIgnoreCase(table) && columnName.equalsIgnoreCase(column)) return true;
                }
                return false;
            }
        }));
    }
    private void migrateSessionScope() {
        for(String definition:new String[]{"room VARCHAR(12) NULL","scene VARCHAR(32) NULL"}) {
            String column=definition.substring(0,definition.indexOf(' '));
            boolean exists=columnExists("ktv_session", column);
            if(!exists) jdbc.execute("ALTER TABLE ktv_session ADD COLUMN "+definition);
        }
    }
    private void migrateOrders() {
        tx.execute(status -> {
            JSONObject old=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1 FOR UPDATE",String.class));
            if(old.containsKey("orders")) {
                for(Object raw:old.getJSONArray("orders")) saveOrder((JSONObject)raw);
                old.remove("orders");old.put("schemaVersion",2);
                jdbc.update("UPDATE ktv_shared_state SET payload=? WHERE id=1",old.toJSONString());
            }
            return null;
        });
    }
    @PostConstruct
    public void initialize() {
        require(!production || !demoPayment,503,"生产环境禁止开启演示支付");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_shared_state (id INT PRIMARY KEY,payload LONGTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_session (token_hash VARCHAR(64) PRIMARY KEY,role VARCHAR(16) NOT NULL,subject VARCHAR(80) NOT NULL,room VARCHAR(12) NULL,scene VARCHAR(32) NULL,expires_at DATETIME NOT NULL,INDEX(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        migrateSessionScope();
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_identity (recovery_hash VARCHAR(64) PRIMARY KEY,subject VARCHAR(80) NOT NULL,created_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY,actor VARCHAR(100) NOT NULL,action VARCHAR(60) NOT NULL,target VARCHAR(80),detail TEXT,created_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_qr (scene VARCHAR(32) PRIMARY KEY,room VARCHAR(12) NOT NULL UNIQUE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_rate (bucket VARCHAR(64) PRIMARY KEY,hits INT NOT NULL,expires_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_payment (order_id VARCHAR(80) PRIMARY KEY,trade_no VARCHAR(32) UNIQUE NOT NULL,transaction_id VARCHAR(64) UNIQUE,refund_no VARCHAR(64) UNIQUE,refund_status VARCHAR(32),created_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ktv_order (id BIGINT PRIMARY KEY,customer_id VARCHAR(80) NOT NULL,supplier_id VARCHAR(40) NOT NULL,room VARCHAR(12) NOT NULL DEFAULT '',status VARCHAR(20) NOT NULL,paid BOOLEAN NOT NULL,request_key VARCHAR(80),payload LONGTEXT NOT NULL,UNIQUE(customer_id,request_key),INDEX(customer_id,id),INDEX(supplier_id,status,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        migrateRoomIndex();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ktv_shared_state",Integer.class)>0) {migrateOrders();return;}
        require(PasswordPolicy.validAdmin(adminPassword) && PasswordPolicy.valid(supplierPassword),503,"首次启动请配置有效的 KTV 管理员及食堂初始密码");
        JSONArray products=new JSONArray();
        String[][] rows={{"chicken","宫保鸡丁饭","主食","32","经典川味，香辣过瘾"},{"beef","黑椒牛柳饭","主食","38","黑椒浓郁，牛肉鲜嫩"},{"bites","香酥鸡米花","小吃","22","外酥里嫩，香气四溢"},{"noodle","番茄牛腩面","主食","36","浓郁番茄，软烂牛腩"}};
        for(String[] row:rows) products.add(object("id",row[0],"name",row[1],"category",row[2],"price",Integer.valueOf(row[3]),"image",row[0],"description",row[4],"onSale",true,"version",1));
        JSONArray rooms=new JSONArray();
        for(String id:Arrays.asList("A08","A09","B03","B05")) rooms.add(object("id",id,"bound",!id.equals("B05"),"version",1));
        JSONArray accounts=new JSONArray();
        accounts.add(object("id","A01","name","合作食堂 A","enabled",true,"process",true,"deliver",true,"passwordHash",passwords.encode(supplierPassword),"version",1));
        accounts.add(object("id","A02","name","备用账号","enabled",false,"process",true,"deliver",true,"passwordHash",passwords.encode(token()),"version",1));
        JSONObject state=object("revision",1,"nextOrder",1000,"categories",new JSONArray(Arrays.asList("主食","小吃")),"products",products,"rooms",rooms,"accounts",accounts,"orders",new JSONArray(),"adminHash",passwords.encode(adminPassword));
        jdbc.update("INSERT IGNORE INTO ktv_shared_state(id,payload) VALUES(1,?)",state.toJSONString());
        migrateOrders();
    }
    public JSONObject customerSession(JSONObject body) {
        String recovery=body==null?null:body.getString("recovery");
        String scene=body==null?null:body.getString("scene");
        String scopeRoom=null,scopeScene=null;
        if(production) {
            require(scene!=null && scene.matches("[a-fA-F0-9]{32}"),403,"生产环境必须扫描包厢二维码进入");
            JSONObject resolved=resolveRoom(scene);
            String requestedRoom=body==null?null:body.getString("room");
            require(requestedRoom==null || requestedRoom.equals(resolved.getString("room")),409,"二维码包厢信息已变化，请重新扫码");
            scopeRoom=resolved.getString("room");scopeScene=scene.toLowerCase(Locale.ROOT);
        } else if(scene!=null && !scene.trim().isEmpty()) {
            JSONObject resolved=resolveRoom(scene);
            String requestedRoom=body==null?null:body.getString("room");
            require(requestedRoom==null || requestedRoom.equals(resolved.getString("room")),409,"二维码包厢信息已变化，请重新扫码");
            scopeRoom=resolved.getString("room");scopeScene=scene.toLowerCase(Locale.ROOT);
        }
        if(recovery!=null) {
            require(recovery.matches("[A-Za-z0-9_-]{43}"),401,"顾客身份凭据无效");
            List<String> ids=jdbc.queryForList("SELECT subject FROM ktv_identity WHERE recovery_hash=?",String.class,hash(recovery));
            require(!ids.isEmpty(),401,"顾客身份凭据已失效，请联系管理员恢复订单");
            return session("customer",ids.get(0),scopeRoom,scopeScene);
        }
        recovery=token(); String subject="customer-"+UUID.randomUUID();
        jdbc.update("INSERT INTO ktv_identity(recovery_hash,subject,created_at) VALUES(?,?,NOW())",hash(recovery),subject);
        JSONObject result=session("customer",subject,scopeRoom,scopeScene); result.put("recovery",recovery);return result;
    }
    public JSONObject wechatSession(String openid) { return wechatSession(openid,null); }
    public JSONObject wechatSession(String openid,JSONObject body) {
        require(openid!=null && openid.matches("[A-Za-z0-9_-]{1,80}"),401,"微信身份无效");
        String scene=body==null?null:body.getString("scene"), room=body==null?null:body.getString("room");
        String scopeRoom=null,scopeScene=null;
        if(production) {
            require(scene!=null && scene.matches("[a-fA-F0-9]{32}"),403,"生产环境必须扫描包厢二维码进入");
            JSONObject resolved=resolveRoom(scene);
            require(room!=null && room.equals(resolved.getString("room")),409,"二维码包厢信息已变化，请重新扫码");
            scopeRoom=resolved.getString("room");scopeScene=scene.toLowerCase(Locale.ROOT);
        } else if(scene!=null && !scene.trim().isEmpty()) {
            JSONObject resolved=resolveRoom(scene);
            require(room==null || room.equals(resolved.getString("room")),409,"二维码包厢信息已变化，请重新扫码");
            scopeRoom=resolved.getString("room");scopeScene=scene.toLowerCase(Locale.ROOT);
        }
        return session("customer","wx-"+openid,scopeRoom,scopeScene);
    }
    public JSONObject customerRecovery(JSONObject who) {
        role(who,"customer");String recovery=token();
        jdbc.update("INSERT INTO ktv_identity(recovery_hash,subject,created_at) VALUES(?,?,NOW())",hash(recovery),who.getString("subject"));return object("recovery",recovery);
    }
    public JSONObject staffSession(JSONObject body,String remote) {
        String username=text(body,"username",64),password=body.getString("password");
        require(password!=null && password.getBytes(StandardCharsets.UTF_8).length<=72,400,"密码不能超过 72 个 UTF-8 字节");
        limit("login:"+remote+":"+username,10,600);
        JSONObject state=read(), account=find(state.getJSONArray("accounts"),username);
        boolean adminLogin="ktv-admin".equals(username) || "admin".equals(username); // admin alias keeps older console deployments working
        String encoded=adminLogin?state.getString("adminHash"):account==null?"":account.getString("passwordHash");
        if(encoded==null || !passwords.matches(password,encoded) || (account!=null && !account.getBooleanValue("enabled"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号、密码错误或账号已停用");
        }
        return session(adminLogin?"admin":"supplier",adminLogin?"admin":username);
    }
    public void limit(String key,int maximum,int seconds) {
        // Shared DB quota works across API instances; rejected attempts cannot grow the bucket table.
        int count=tx.execute(status -> {
            jdbc.update("DELETE FROM ktv_rate WHERE expires_at<NOW()");
            String bucket=hash(key);
            jdbc.update("INSERT IGNORE INTO ktv_rate(bucket,hits,expires_at) VALUES(?,0,?)",bucket,LocalDateTime.now().plusSeconds(seconds));
            jdbc.update("UPDATE ktv_rate SET hits=hits+1 WHERE bucket=?",bucket);
            return jdbc.queryForObject("SELECT hits FROM ktv_rate WHERE bucket=?",Integer.class,bucket);
        });
        require(count<=maximum,429,"操作过于频繁，请稍后重试");
    }
    private JSONObject session(String role,String subject) { return session(role,subject,null,null); }
    private JSONObject session(String role,String subject,String room,String scene) {
        String token=token();
        jdbc.update("DELETE FROM ktv_session WHERE expires_at<NOW()");
        jdbc.update("INSERT INTO ktv_session(token_hash,role,subject,room,scene,expires_at) VALUES(?,?,?,?,?,?)",hash(token),role,subject,room,scene,LocalDateTime.now().plusHours(role.equals("customer")?720:8));
        JSONObject result=object("token",token,"role",role,"subject",subject);
        if(room!=null) result.put("room",room); if(scene!=null) result.put("scene",scene);
        return result;
    }
    public JSONObject identity(String token) {
        require(token!=null && token.length()>=32,401,"请登录后继续");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT role,subject,room,scene FROM ktv_session WHERE token_hash=? AND expires_at>NOW()",hash(token));
        require(!rows.isEmpty(),401,"登录已过期，请重新登录");
        Map<String,Object> row=rows.get(0); JSONObject result=object("role",row.get("role"),"subject",row.get("subject"));
        require(!production || !"customer".equals(row.get("role")) || (row.get("room")!=null && row.get("scene")!=null),401,"请重新扫描包厢二维码登录");
        if(row.get("room")!=null) result.put("room",row.get("room")); if(row.get("scene")!=null) result.put("scene",row.get("scene"));
        return result;
    }
    public void logout(String token) { if(token!=null) jdbc.update("DELETE FROM ktv_session WHERE token_hash=?",hash(token)); }
    private void role(JSONObject who,String role) { require(role.equals(who.getString("role")),403,"当前账号无权执行此操作"); }
    private JSONObject supplier(JSONObject state,JSONObject who) {
        JSONObject account=find(state.getJSONArray("accounts"),who.getString("subject"));
        require(account!=null && account.getBooleanValue("enabled"),403,"食堂账号已停用，请联系管理员");
        return account;
    }
    public JSONObject snapshot(JSONObject who) {return snapshot(who,1,null);}
    public JSONObject snapshot(JSONObject who,int page,String selectedId) {return snapshot(who,page,selectedId,"","","","");}
    public JSONObject snapshot(JSONObject who,int page,String selectedId,String filter,String search,String room,String payment) {
        require(page>=1 && page<=10000,400,"页码无效");
        require(Arrays.asList("","all","active","unpaid","pending","preparing","delivering","completed","closed","cancelled","refunding","refunded").contains(filter),400,"订单状态筛选无效");
        require(search.length()<=80 && room.length()<=12 && Arrays.asList("","paid","unpaid").contains(payment),400,"订单筛选条件无效");
        JSONObject state=read(); String role=who.getString("role");
        JSONArray orders=new JSONArray(),products=new JSONArray(),rooms=new JSONArray(),accounts=new JSONArray();
        if(role.equals("supplier")) { JSONObject account=copy(supplier(state,who)); account.remove("passwordHash"); accounts.add(account); }
        else {
            for(Object raw:state.getJSONArray("products")) { JSONObject p=(JSONObject)raw; if(role.equals("admin") || p.getBooleanValue("onSale")) products.add(p); }
            String scopedRoom=who.getString("room");
            for(Object raw:state.getJSONArray("rooms")) { JSONObject item=(JSONObject)raw; if(role.equals("admin") || (item.getBooleanValue("bound") && (scopedRoom==null || scopedRoom.equals(item.getString("id"))))) rooms.add(item); }
        }
        if(role.equals("admin")) for(Object raw:state.getJSONArray("accounts")) { JSONObject a=copy((JSONObject)raw); a.remove("passwordHash"); accounts.add(a); }
        String scope=" WHERE 1=1";List<Object> params=new ArrayList<>();
        if(role.equals("customer")){scope+=" AND customer_id=?";params.add(who.getString("subject"));}
        else if(role.equals("supplier")){scope+=" AND supplier_id=? AND paid=TRUE";params.add(who.getString("subject"));}
        JSONObject counts=new JSONObject();
        for(Map<String,Object> row:jdbc.queryForList("SELECT status,COUNT(*) AS total FROM ktv_order"+scope+" GROUP BY status",params.toArray())) counts.put((String)row.get("status"),row.get("total"));
        JSONArray alerts=new JSONArray();
        if(role.equals("supplier")) for(Map<String,Object> row:jdbc.queryForList("SELECT id,room FROM ktv_order"+scope+" AND status='pending' ORDER BY id DESC LIMIT 20",params.toArray())) alerts.add(object("id",String.valueOf(row.get("id")),"room",row.get("room"),"status","pending"));
        String where=scope;
        if(filter.equals("active"))where+=" AND status IN ('pending','preparing','delivering')";
        else if(!filter.isEmpty() && !filter.equals("all")){where+=" AND status=?";params.add(filter);}
        if(!search.trim().isEmpty()){where+=" AND (LOCATE(?,CONCAT('',id))>0 OR LOCATE(?,LOWER(room))>0)";params.add(search.trim().toLowerCase(Locale.ROOT));params.add(search.trim().toLowerCase(Locale.ROOT));}
        if(!room.isEmpty()){where+=" AND room=?";params.add(room);}
        if(!payment.isEmpty()){where+=" AND paid=?";params.add(payment.equals("paid"));}
        params.add((page-1)*100);
        List<String> stored=jdbc.queryForList("SELECT payload FROM ktv_order"+where+" ORDER BY id DESC LIMIT 101 OFFSET ?",String.class,params.toArray());
        boolean hasMore=stored.size()>100;if(hasMore) stored=stored.subList(0,100);
        for(String payload:stored) {
            Object raw=JSON.parseObject(payload);
            JSONObject order=(JSONObject)raw;
            formatOrderTime(order);
            if(role.equals("admin") || (role.equals("customer") && who.getString("subject").equals(order.getString("customerId")))) {
                JSONObject safe=copy(order); safe.remove("requestKey"); safe.remove("requestHash"); orders.add(safe);
            } else if(role.equals("supplier") && order.getBooleanValue("paid") && who.getString("subject").equals(order.getString("supplierId"))) {
                JSONArray items=new JSONArray();
                for(Object itemRaw:order.getJSONArray("items")) { JSONObject item=(JSONObject)itemRaw; items.add(object("id",item.get("id"),"name",item.get("name"),"quantity",item.get("quantity"),"image",item.get("image"),"customImage",item.get("customImage"))); }
                orders.add(object("id",order.get("id"),"room",order.get("room"),"status",order.get("status"),"time",order.get("time"),"items",items,"events",order.get("events"),"version",order.get("version")));
            }
        }
        JSONObject detail=null;
        if(selectedId!=null && !selectedId.isEmpty()) {try{detail=order(who,selectedId);}catch(ResponseStatusException e){if(e.getRawStatusCode()!=404)throw e;}}
        JSONArray categories=categories(state);
        return object("revision",state.get("revision"),"role",role,"subject",who.get("subject"),"categories",categories,"products",products,"rooms",rooms,"accounts",accounts,"orders",orders,"orderDetail",detail,"orderCounts",counts,"orderAlerts",alerts,"page",page,"hasMore",hasMore,"demoPayment",demoPayment);
    }
    private void formatOrderTime(JSONObject order) {
        String created=order.getString("createdAt");
        if(created!=null && created.length()>=16) order.put("time",created.substring(0,10)+" "+created.substring(11,16));
    }

    /**
     * Administrator order search used by the PC console.  The customer/supplier
     * snapshot intentionally exposes only a small, role-scoped view; this method
     * is the explicit admin contract and keeps filtering in one place for both
     * JSON and CSV responses.
     */
    public JSONObject adminOrders(JSONObject who,String keyword,String status,String room,String supplierId,
                                  Boolean paid,String paymentMode,String refundStatus,Long minTotalCents,
                                  Long maxTotalCents,String startTime,String endTime,int page,int pageSize) {
        role(who,"admin");
        List<JSONObject> filtered=adminOrderList(keyword,status,room,supplierId,paid,paymentMode,refundStatus,
                minTotalCents,maxTotalCents,startTime,endTime);
        require(page>=1 && page<=100000,400,"页码无效");
        require(pageSize>=1 && pageSize<=500,400,"每页数量无效");
        int from=Math.min((page-1)*pageSize,filtered.size()), to=Math.min(from+pageSize,filtered.size());
        JSONArray items=new JSONArray();
        for(JSONObject order:filtered.subList(from,to)) items.add(order);
        return object("items",items,"total",filtered.size(),"page",page,"pageSize",pageSize,"startTime",startTime,"endTime",endTime);
    }

    /** Return the same filtered rows without pagination for export. */
    public List<JSONObject> adminOrderRows(JSONObject who,String keyword,String status,String room,String supplierId,
                                           Boolean paid,String paymentMode,String refundStatus,Long minTotalCents,
                                           Long maxTotalCents,String startTime,String endTime) {
        role(who,"admin");
        return adminOrderList(keyword,status,room,supplierId,paid,paymentMode,refundStatus,
                minTotalCents,maxTotalCents,startTime,endTime);
    }

    private List<JSONObject> adminOrderList(String keyword,String status,String room,String supplierId,Boolean paid,
                                            String paymentMode,String refundStatus,Long minTotalCents,Long maxTotalCents,
                                            String startTime,String endTime) {
        String key=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        String wantedStatus=status==null?"":status.trim().toLowerCase(Locale.ROOT);
        String wantedRoom=room==null?"":room.trim();
        String wantedSupplier=supplierId==null?"":supplierId.trim();
        String wantedPayment=paymentMode==null?"":paymentMode.trim().toLowerCase(Locale.ROOT);
        String wantedRefund=refundStatus==null?"":refundStatus.trim().toUpperCase(Locale.ROOT);
        require(key.length()<=80 && wantedRoom.length()<=12 && wantedSupplier.length()<=40,400,"订单筛选条件无效");
        require(wantedSupplier.isEmpty() || wantedSupplier.matches("[A-Za-z0-9_-]{1,40}"),400,"订单筛选条件无效");
        require(wantedStatus.isEmpty() || Arrays.asList("all","active","unpaid","pending","preparing","delivering","completed","cancelled","closed","refunding","refunded").contains(wantedStatus),400,"订单状态筛选无效");
        require(wantedPayment.isEmpty() || Arrays.asList("demo","wechat").contains(wantedPayment),400,"支付方式筛选无效");
        require(wantedRefund.isEmpty() || Arrays.asList("PENDING","PROCESSING","SUCCESS","REJECTED").contains(wantedRefund),400,"退款状态筛选无效");
        if(minTotalCents!=null) require(minTotalCents>=0,400,"最低金额无效");
        if(maxTotalCents!=null) require(maxTotalCents>=0 && (minTotalCents==null || maxTotalCents>=minTotalCents),400,"金额范围无效");
        LocalDateTime start=parseDateTime(startTime),end=parseDateTime(endTime);
        require(start==null || end==null || !end.isBefore(start),400,"时间范围无效");
        List<JSONObject> result=new ArrayList<>();
        for(String payload:jdbc.queryForList("SELECT payload FROM ktv_order ORDER BY id DESC",String.class)) {
            JSONObject order=JSON.parseObject(payload); formatOrderTime(order);
            String id=order.getString("id"), orderRoom=order.getString("room"), supplier=order.getString("supplierId");
            String orderStatus=order.getString("status");
            if(!key.isEmpty() && !(String.valueOf(id)+String.valueOf(orderRoom)+String.valueOf(supplier)+String.valueOf(order.getString("customerId"))).toLowerCase(Locale.ROOT).contains(key)) continue;
            if(!wantedStatus.isEmpty() && !wantedStatus.equals("all") && !(wantedStatus.equals("active") ? Arrays.asList("pending","preparing","delivering").contains(orderStatus) : wantedStatus.equals("closed") ? "cancelled".equals(orderStatus)||"closed".equals(orderStatus) : wantedStatus.equals(orderStatus))) continue;
            if(!wantedRoom.isEmpty() && !wantedRoom.equals(orderRoom)) continue;
            if(!wantedSupplier.isEmpty() && !wantedSupplier.equals(supplier)) continue;
            if(paid!=null && paid.booleanValue()!=order.getBooleanValue("paid")) continue;
            String actualPayment=order.getString("paymentMode");
            if(!wantedPayment.isEmpty() && !wantedPayment.equalsIgnoreCase(actualPayment==null?"":actualPayment)) continue;
            String actualRefund=order.getString("refundStatus");
            if(!wantedRefund.isEmpty() && !wantedRefund.equalsIgnoreCase(actualRefund==null?"":actualRefund)) continue;
            long total=order.getLongValue("totalCents");
            if(minTotalCents!=null && total<minTotalCents) continue;
            if(maxTotalCents!=null && total>maxTotalCents) continue;
            LocalDateTime created=parseDateTime(order.getString("createdAt"));
            if(start!=null && (created==null || created.isBefore(start))) continue;
            if(end!=null && (created==null || created.isAfter(end))) continue;
            JSONObject safe=copy(order);safe.remove("requestKey");safe.remove("requestHash");result.add(safe);
        }
        return result;
    }

    private LocalDateTime parseDateTime(String value) {
        if(value==null || value.trim().isEmpty()) return null;
        String text=value.trim();
        try { return LocalDateTime.parse(text,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
        catch(DateTimeParseException ignored) { try { return LocalDateTime.parse(text); } catch(DateTimeParseException ignoredAgain) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"时间格式无效"); } }
    }
    public JSONObject createOrder(JSONObject who,JSONObject body) {
        role(who,"customer");
        String room=text(body,"room",12),key=text(body,"requestKey",80);
        // Older H5 builds only send the resolved room after /scan.  Keep the
        // scene check when supplied, while accepting that established session
        // contract for compatibility with the current app build.
        String scene=body.getString("scene");
        String scopedRoom=who.getString("room"),scopedScene=who.getString("scene");
        require(scopedRoom==null || room.equals(scopedRoom),409,"当前会话已绑定其他包厢，请重新扫码");
        if(production) {
            require(scopedScene!=null && scopedScene.matches("[a-f0-9]{32}"),403,"生产环境必须扫描包厢二维码进入");
            require(room.equals(resolveRoom(scopedScene).getString("room")),409,"二维码包厢信息已变化，请重新扫码");
            require(scene==null || scopedScene.equals(scene.toLowerCase(Locale.ROOT)),409,"二维码包厢信息已变化，请重新扫码");
        }
        if(scene!=null && !scene.trim().isEmpty()) require(room.equals(resolveRoom(scene).getString("room")),409,"包厢信息已变化，请重新扫码");
        JSONArray requested=body.getJSONArray("items");
        require(requested!=null && requested.size()>0 && requested.size()<=50,400,"请选择餐品");
        String fingerprint=hash(room+requested.toJSONString());
        return change(state -> {
            for(String payload:jdbc.queryForList("SELECT payload FROM ktv_order WHERE customer_id=? AND request_key=?",String.class,who.getString("subject"),key)) { JSONObject existing=JSON.parseObject(payload);
                if(who.getString("subject").equals(existing.getString("customerId")) && key.equals(existing.getString("requestKey"))) {
                    require(fingerprint.equals(existing.getString("requestHash")),409,"请求编号已用于另一张订单"); return object("id",existing.getString("id"));
                }
            }
            long open=jdbc.queryForObject("SELECT COUNT(*) FROM ktv_order WHERE customer_id=? AND status='unpaid'",Long.class,who.getString("subject"));
            require(open<5,429,"待付款订单过多，请先支付或取消已有订单");
            JSONObject selectedRoom=find(state.getJSONArray("rooms"),room);
            require(selectedRoom!=null && selectedRoom.getBooleanValue("bound"),409,"当前包厢未启用，请重新扫码");
            JSONObject assigned=null;
            for(Object raw:state.getJSONArray("accounts")) { JSONObject a=(JSONObject)raw; if(a.getBooleanValue("enabled")&&a.getBooleanValue("process")&&a.getBooleanValue("deliver")){assigned=a;break;} }
            require(assigned!=null,409,"暂时没有可接单食堂，请联系 KTV");
            JSONArray items=new JSONArray(); Set<String> seen=new HashSet<>(); long cents=0;
            for(int i=0;i<requested.size();i++) {
                JSONObject line=requested.getJSONObject(i); String id=text(line,"id",64); JSONObject product=find(state.getJSONArray("products"),id);
                Number q=line.getObject("quantity",Number.class);
                require(q!=null && q.doubleValue()==q.intValue() && q.intValue()>0 && q.intValue()<=99 && seen.add(id),400,"餐品数量无效");
                require(product!=null && product.getBooleanValue("onSale"),409,"餐品已下架，请重新选择");
                JSONObject item=copy(product); item.put("quantity",q.intValue()); items.add(item);
                cents+=Math.round(product.getDoubleValue("price")*100)*q.intValue();
            }
            int number=state.getIntValue("nextOrder"); state.put("nextOrder",number+1);
            String id=String.valueOf(number);
            JSONObject order=object("id",id,"room",room,"customerId",who.get("subject"),"supplierId",assigned.get("id"),"status","unpaid","paid",false,"items",items,"totalCents",cents,"time","今天 "+LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),"createdAt",LocalDateTime.now().toString(),"requestKey",key,"requestHash",fingerprint,"version",1,"events",new JSONArray());
            event(order,"订单已创建，等待支付"); state.getJSONArray("orders").add(0,order);audit(who,"order.create",id,object("totalCents",cents,"room",room));
            return object("id",id);
        });
    }
    private void event(JSONObject order,String label) {
        LocalDateTime now=LocalDateTime.now();
        order.getJSONArray("events").add(object("time",now.format(DateTimeFormatter.ofPattern("HH:mm")),"at",now.toString(),"label",label));
    }
    public JSONObject advance(JSONObject who,String id,JSONObject body,boolean payment) {
        if(payment) { role(who,"customer"); require(demoPayment,403,"当前仅支持微信支付"); }
        else role(who,"supplier");
        return change(id,state -> {
            JSONObject order=find(state.getJSONArray("orders"),id); require(order!=null,404,"订单不存在");
            String next=payment?"pending":text(body,"status",20),current=order.getString("status");
            if(payment) require(who.getString("subject").equals(order.getString("customerId")),403,"无权支付该订单");
            else {
                JSONObject account=supplier(state,who);
                require(who.getString("subject").equals(order.getString("supplierId")) && order.getBooleanValue("paid"),403,"无权处理该订单");
                require(account.getBooleanValue(next.equals("delivering") || next.equals("completed") ? "deliver" : "process"),403,"当前账号暂无此操作权限");
            }
            if((payment && order.getBooleanValue("paid")) || current.equals(next)) return object("id",id);
            Map<String,String> flow=new HashMap<>(); flow.put("unpaid","pending"); flow.put("pending","preparing"); flow.put("preparing","delivering"); flow.put("delivering","completed");
            require(next.equals(flow.get(current)),409,"订单状态已变化，请刷新后重试");
            if(payment) {
                JSONObject assigned=find(state.getJSONArray("accounts"),order.getString("supplierId"));
                if(assigned==null || !assigned.getBooleanValue("enabled") || !assigned.getBooleanValue("process") || !assigned.getBooleanValue("deliver")) {
                    assigned=null;
                    for(Object raw:state.getJSONArray("accounts")) { JSONObject a=(JSONObject)raw; if(a.getBooleanValue("enabled")&&a.getBooleanValue("process")&&a.getBooleanValue("deliver")){assigned=a;break;} }
                    require(assigned!=null,409,"暂时没有可接单食堂，请联系 KTV");
                    order.put("supplierId",assigned.getString("id"));
                }
            }
            if(next.equals("delivering")) {
                JSONArray checked=body.getJSONArray("checkedIds");
                require(checked!=null,400,"请核对餐品数量");
                for(Object raw:order.getJSONArray("items")) require(checked.contains(((JSONObject)raw).getString("id")),400,"请核对全部餐品数量");
            }
            order.put("status",next); order.put("paid",true); order.put("version",order.getIntValue("version")+1);
            String label=next.equals("pending")?"订单已支付":next.equals("preparing")?"食堂接单":next.equals("delivering")?"开始配送":"配送方确认送达";
            event(order,label); if(payment) { order.put("paymentMode","demo");order.put("paidAt",LocalDateTime.now().toString()); }
            audit(who,"order.transition",id,object("from",current,"to",next));
            return object("id",id);
        });
    }
    void audit(JSONObject who,String action,String target,JSONObject detail) {
        org.springframework.web.context.request.RequestAttributes attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        Object requestId=attributes==null?null:attributes.getAttribute("ktvRequestId",org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        detail=object("requestId",requestId==null?UUID.randomUUID().toString():requestId,"data",detail);
        jdbc.update("INSERT INTO ktv_audit(actor,action,target,detail,created_at) VALUES(?,?,?,?,NOW())",who.getString("role")+":"+who.getString("subject"),action,target,detail.toJSONString());
    }
    public Object audits(JSONObject who,int page) {
        role(who,"admin"); require(page>=1 && page<=100000,400,"页码无效");
        return jdbc.queryForList("SELECT id,actor,action,target,detail,created_at FROM ktv_audit ORDER BY id DESC LIMIT 50 OFFSET ?",(page-1)*50);
    }
    public JSONObject order(JSONObject who,String id) {
        require(id!=null && id.matches("[0-9]{1,18}"),404,"订单不存在");
        List<String> rows=jdbc.queryForList("SELECT payload FROM ktv_order WHERE id=?",String.class,id);require(!rows.isEmpty(),404,"订单不存在");
        JSONObject found=JSON.parseObject(rows.get(0));String role=who.getString("role");
        require("admin".equals(role) || ("customer".equals(role) && who.getString("subject").equals(found.getString("customerId"))) || ("supplier".equals(role) && found.getBooleanValue("paid") && who.getString("subject").equals(found.getString("supplierId"))),404,"订单不存在");
        found.remove("requestKey");found.remove("requestHash");formatOrderTime(found);
        if("supplier".equals(role)) {
            supplier(read(),who);JSONArray items=new JSONArray();for(Object raw:found.getJSONArray("items")) {JSONObject item=(JSONObject)raw;items.add(object("id",item.get("id"),"name",item.get("name"),"quantity",item.get("quantity"),"image",item.get("image"),"customImage",item.get("customImage")));}
            return object("id",found.get("id"),"room",found.get("room"),"status",found.get("status"),"time",found.get("time"),"items",items,"events",found.get("events"),"version",found.get("version"));
        }
        return found;
    }
    public JSONObject cancel(JSONObject who,String id) {
        return change(id,state -> {
            JSONObject o=find(state.getJSONArray("orders"),id); require(o!=null,404,"订单不存在");
            require("admin".equals(who.getString("role")) || ("customer".equals(who.getString("role")) && who.getString("subject").equals(o.getString("customerId"))),403,"无权取消订单");
            if("cancelled".equals(o.getString("status"))) return object("id",id);
            require("unpaid".equals(o.getString("status")),409,"订单已支付，请联系管理员处理退款");
            o.put("status","cancelled");o.put("version",o.getIntValue("version")+1);event(o,"订单已取消");audit(who,"order.cancel",id,new JSONObject());return object("id",id);
        });
    }
    public JSONObject reassign(JSONObject who,String id,JSONObject body) {
        role(who,"admin");String supplierId=text(body,"supplierId",40),reason=text(body,"reason",200);
        return change(id,state -> {
            JSONObject o=find(state.getJSONArray("orders"),id);version(o,body);
            require(Arrays.asList("pending","preparing","delivering").contains(o.getString("status")),409,"当前订单不能转派");
            JSONObject a=find(state.getJSONArray("accounts"),supplierId);
            require(a!=null && a.getBooleanValue("enabled") && a.getBooleanValue("process") && a.getBooleanValue("deliver"),409,"目标食堂无完整接单权限");
            String previous=o.getString("supplierId");require(!supplierId.equals(previous),409,"订单已属于该食堂");
            o.put("supplierId",supplierId);o.put("version",o.getIntValue("version")+1);
            event(o,"管理员转派订单");audit(who,"order.reassign",id,object("from",previous,"to",supplierId,"reason",reason));return object("id",id);
        });
    }
    public JSONObject rotatePassword(JSONObject who,JSONObject body) {
        role(who,"admin");String old=body.getString("oldPassword"),next=body.getString("password");
        require(PasswordPolicy.valid(next) && old!=null && old.getBytes(StandardCharsets.UTF_8).length<=72,400,"新密码至少12个字符，UTF-8编码不得超过72字节");
        return change(state -> {
            require(("admin".equals(who.getString("subject")) || "ktv-admin".equals(who.getString("subject"))) && passwords.matches(old,state.getString("adminHash")),403,"原密码错误");
            state.put("adminHash",passwords.encode(next));jdbc.update("DELETE FROM ktv_session WHERE role='admin'");audit(who,"admin.password.rotate","admin",new JSONObject());return object("ok",true);
        });
    }
    public JSONObject roomCode(JSONObject who,String id,boolean rotate) {
        role(who,"admin");
        return change(state -> {
            JSONObject room=find(state.getJSONArray("rooms"),id);require(room!=null && room.getBooleanValue("bound"),409,"请先启用包厢");
            if(rotate) jdbc.update("DELETE FROM ktv_qr WHERE room=?",id);
            String scene=UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT IGNORE INTO ktv_qr(scene,room) VALUES(?,?)",scene,id);
            scene=jdbc.queryForObject("SELECT scene FROM ktv_qr WHERE room=?",String.class,id);
            if(rotate) audit(who,"room.qr.rotate",id,new JSONObject());
            return object("scene",scene,"room",id);
        });
    }
    public JSONObject resolveRoom(String scene) {
        require(scene!=null && scene.matches("[a-f0-9]{32}"),400,"包厢码无效，请重新扫码");
        List<String> rooms=jdbc.queryForList("SELECT room FROM ktv_qr WHERE scene=?",String.class,scene);
        require(!rooms.isEmpty(),404,"包厢码已失效，请重新扫码");
        JSONObject room=find(read().getJSONArray("rooms"),rooms.get(0));require(room!=null && room.getBooleanValue("bound"),409,"当前包厢已停用");return object("room",room.getString("id"),"scene",scene);
    }
    public String paymentTrade(String id) {
        List<String> list=jdbc.queryForList("SELECT trade_no FROM ktv_payment WHERE order_id=?",String.class,id);return list.isEmpty()?null:list.get(0);
    }
    public JSONObject paymentIntent(JSONObject who,String id) {
        role(who,"customer");require(who.getString("subject").startsWith("wx-"),409,"请在微信小程序中登录后支付");
        return change(id,state -> {
            JSONObject o=find(state.getJSONArray("orders"),id);require(o!=null && who.getString("subject").equals(o.getString("customerId")),404,"订单不存在");
            require("unpaid".equals(o.getString("status")),409,"订单状态已变化，请刷新");
            jdbc.update("INSERT IGNORE INTO ktv_payment(order_id,trade_no,created_at) VALUES(?,?,NOW())",id,UUID.randomUUID().toString().replace("-",""));
            return object("tradeNo",paymentTrade(id),"total",o.getIntValue("totalCents"),"openid",who.getString("subject").substring(3));
        });
    }
    public void confirmPayment(String trade,String transaction,int total,String paidAt,String openid) {
        String target=jdbc.queryForObject("SELECT order_id FROM ktv_payment WHERE trade_no=?",String.class,trade);
        change(target,state -> {
            List<String> ids=jdbc.queryForList("SELECT order_id FROM ktv_payment WHERE trade_no=?",String.class,trade); require(!ids.isEmpty(),404,"支付订单不存在");
            String id=ids.get(0);JSONObject o=find(state.getJSONArray("orders"),id);
            require(o!=null && o.getLongValue("totalCents")==total && ("wx-"+openid).equals(o.getString("customerId")),409,"支付金额或付款人不匹配");
            if(o.getBooleanValue("paid")) {require(transaction.equals(o.getString("transactionId")),409,"交易号不一致");return null;}
            require("unpaid".equals(o.getString("status")),409,"订单状态异常，请人工核对支付");
            jdbc.update("UPDATE ktv_payment SET transaction_id=? WHERE order_id=?",transaction,id);
            o.put("status","pending");o.put("paid",true);o.put("paymentMode","wechat");o.put("transactionId",transaction);o.put("paidAt",paidAt);o.put("version",o.getIntValue("version")+1);
            event(o,"微信支付成功");audit(object("role","system","subject","wechat"),"payment.success",id,object("transactionId",transaction,"totalCents",total));return null;
        });
    }
    public JSONObject refundIntent(JSONObject who,String id,JSONObject body) {
        role(who,"admin");String reason=text(body,"reason",200);
        return change(id,state -> {
            JSONObject o=find(state.getJSONArray("orders"),id);require(o!=null && o.getBooleanValue("paid") && "wechat".equals(o.getString("paymentMode")),409,"仅真实已付款订单可申请微信退款");
            require(!"refunded".equals(o.getString("status")),409,"订单已退款");
            String refund=jdbc.queryForObject("SELECT refund_no FROM ktv_payment WHERE order_id=?",String.class,id);
            if(refund==null) {
                version(o,body);refund=UUID.randomUUID().toString().replace("-","");
                jdbc.update("UPDATE ktv_payment SET refund_no=?,refund_status='REQUESTED' WHERE order_id=?",refund,id);
                o.put("status","refunding");o.put("version",o.getIntValue("version")+1);event(o,"管理员申请全额退款");audit(who,"refund.request",id,object("reason",reason,"refundNo",refund));
            }
            return object("tradeNo",paymentTrade(id),"refundNo",refund,"total",o.getLongValue("totalCents"),"reason",reason);
        });
    }

    /** Customer request followed by an explicit administrator decision. */
    public JSONObject requestRefund(JSONObject who,String id,JSONObject body) {
        role(who,"customer"); String reason=text(body,"reason",200);
        return change(id,state -> {
            JSONObject o=find(state.getJSONArray("orders"),id);
            require(o!=null && who.getString("subject").equals(o.getString("customerId")),404,"订单不存在");
            if(body.containsKey("version")) version(o,body);
            require(o.getBooleanValue("paid"),409,"未付款订单不能申请退款");
            require(Arrays.asList("pending","preparing","delivering","completed","closed").contains(o.getString("status")),409,"当前订单不能申请退款");
            require(o.getString("refundStatus")==null || o.getString("refundStatus").isEmpty(),409,"退款申请已提交，请等待管理员处理");
            o.put("refundStatus","PENDING"); o.put("refundReason",reason); o.put("refundOriginalStatus",o.getString("status"));
            o.put("version",o.getIntValue("version")+1); event(o,"顾客提交退款申请");
            audit(who,"refund.request",id,object("reason",reason));
            return object("id",id,"refundStatus","PENDING");
        });
    }

    /**
     * Apply an admin refund decision. Demo payments are completed locally;
     * WeChat orders are marked PROCESSING and the controller submits the
     * provider request using the returned intent.
     */
    public JSONObject reviewRefund(JSONObject who,String id,JSONObject body) {
        role(who,"admin"); require(body!=null,400,"请求数据不能为空");
        boolean approved=body.getBooleanValue("approved"); String reason=text(body,"reason",200);
        return change(id,state -> {
            JSONObject o=find(state.getJSONArray("orders"),id); require(o!=null,404,"订单不存在");
            if(body.containsKey("version")) version(o,body);
            require("PENDING".equals(o.getString("refundStatus")),409,"当前没有待审批的退款申请");
            String original=o.getString("refundOriginalStatus"); if(original==null || original.isEmpty()) original="pending";
            if(!approved) {
                o.put("refundStatus","REJECTED");o.put("refundReviewReason",reason);o.put("status",original);
                o.put("version",o.getIntValue("version")+1);event(o,"管理员拒绝退款申请");audit(who,"refund.reject",id,object("reason",reason));
                return object("id",id,"refundStatus","REJECTED");
            }
            if("wechat".equals(o.getString("paymentMode"))) {
                String refund=jdbc.queryForObject("SELECT refund_no FROM ktv_payment WHERE order_id=?",String.class,id);
                if(refund==null) { refund=UUID.randomUUID().toString().replace("-",""); jdbc.update("UPDATE ktv_payment SET refund_no=?,refund_status='REQUESTED' WHERE order_id=?",refund,id); }
                o.put("refundStatus","PROCESSING");o.put("refundReviewReason",reason);o.put("status","refunding");
                o.put("version",o.getIntValue("version")+1);event(o,"管理员同意退款，等待微信处理");audit(who,"refund.approve",id,object("reason",reason,"refundNo",refund));
                return object("id",id,"refundStatus","PROCESSING","requiresProvider",true,"tradeNo",paymentTrade(id),"refundNo",refund,"total",o.getLongValue("totalCents"),"reason",reason);
            }
            o.put("refundStatus","SUCCESS");o.put("refundReviewReason",reason);o.put("status","refunded");
            o.put("refundedAt",LocalDateTime.now().toString());o.put("version",o.getIntValue("version")+1);event(o,"管理员同意退款");audit(who,"refund.success",id,object("reason",reason));
            return object("id",id,"refundStatus","SUCCESS");
        });
    }

    /** Bulk operations used by the PC console; rows that do not satisfy the
     * action are returned in skipped instead of aborting the entire request. */
    public JSONObject batchOrderAction(JSONObject who,JSONArray ids,String action,String reason) {
        role(who,"admin"); require(ids!=null && ids.size()>0 && ids.size()<=200,400,"请选择订单");
        require(Arrays.asList("close-unpaid","reject-refund").contains(action),400,"批量操作无效");
        String note=reason==null||reason.trim().isEmpty()?"管理员批量处理":reason.trim(); require(note.length()<=200,400,"处理说明不能超过200字");
        JSONArray skipped=new JSONArray(); int changed=0;
        for(Object raw:ids) {
            String id=String.valueOf(raw);
            try {
                final String current=id;
                JSONObject result=change(current,state -> {
                    JSONObject o=find(state.getJSONArray("orders"),current); if(o==null) return null;
                    if("close-unpaid".equals(action)) {
                        LocalDateTime created=parseDateTime(o.getString("createdAt"));
                        if(!"unpaid".equals(o.getString("status")) || created==null || created.isAfter(LocalDateTime.now().minusMinutes(30))) return null;
                        o.put("status","cancelled");o.put("version",o.getIntValue("version")+1);event(o,"管理员批量关闭超时未支付订单");audit(who,"order.batch.close",current,new JSONObject());return object("changed",true);
                    }
                    if(!"PENDING".equals(o.getString("refundStatus"))) return null;
                    o.put("refundStatus","REJECTED");o.put("refundReviewReason",note);String original=o.getString("refundOriginalStatus");o.put("status",original==null||original.isEmpty()?"pending":original);o.put("version",o.getIntValue("version")+1);event(o,"管理员批量拒绝退款申请");audit(who,"refund.batch.reject",current,object("reason",note));return object("changed",true);
                });
                if(result!=null && result.getBooleanValue("changed")) changed++; else skipped.add(id);
            } catch(ResponseStatusException error) { skipped.add(id); }
        }
        return object("changed",changed,"skipped",skipped);
    }
    public void confirmRefund(String refund,String providerStatus,long total,long refunded) {
        String target=jdbc.queryForObject("SELECT order_id FROM ktv_payment WHERE refund_no=?",String.class,refund);
        change(target,state -> {
            List<String> ids=jdbc.queryForList("SELECT order_id FROM ktv_payment WHERE refund_no=?",String.class,refund);require(!ids.isEmpty(),404,"退款记录不存在");
            JSONObject o=find(state.getJSONArray("orders"),ids.get(0));require(o!=null && o.getLongValue("totalCents")==total && refunded==total,409,"退款金额不匹配");
            if("refunded".equals(o.getString("status"))) return null;
            jdbc.update("UPDATE ktv_payment SET refund_status=? WHERE refund_no=?",providerStatus,refund);
            o.put("refundStatus",providerStatus);
            if("SUCCESS".equals(providerStatus)) {o.put("status","refunded");o.put("refundedAt",LocalDateTime.now().toString());o.put("version",o.getIntValue("version")+1);event(o,"微信全额退款成功");audit(object("role","system","subject","wechat"),"refund.success",o.getString("id"),object("refundNo",refund));}
            return null;
        });
    }
    private void version(JSONObject existing,JSONObject body) {
        require(existing!=null,404,"记录不存在");
        require(existing.getIntValue("version")==body.getIntValue("version"),409,"记录已被其他端修改，请刷新后重试");
    }
    public JSONObject product(JSONObject who,String id,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            JSONObject existing=find(state.getJSONArray("products"),id);
            JSONObject before=existing==null?null:copy(existing);
            if(existing!=null) version(existing,body); else require(id.matches("food-[a-zA-Z0-9-]{1,50}"),400,"餐品编号无效");
            String name=text(body,"name",60),category=text(body,"category",20); double price=body.getDoubleValue("price");
            require(validCategory(category) && categories(state).contains(category) && Double.isFinite(price) && price>0 && price<=9999 && Math.abs(price*100-Math.rint(price*100))<0.00001,400,"餐品价格或分类无效");
            String custom=body.getString("customImage");
            require(body.getString("description")==null || body.getString("description").length()<=300,400,"餐品描述不能超过300字");
            require(body.getString("image")==null || Arrays.asList("chicken","beef","bites","noodle").contains(body.getString("image")),400,"餐品图片编号无效");
            require(custom==null || custom.isEmpty() || custom.matches("/ktv-api/assets/[a-f0-9-]+\\.(png|jpg)"),400,"请先上传餐品图片");
            if(custom!=null && !custom.isEmpty()) require(java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(uploadPath).toAbsolutePath().normalize().resolve(custom.substring(custom.lastIndexOf('/')+1))),400,"餐品图片不存在，请重新上传");
            JSONObject p=object("id",id,"name",name,"category",category,"price",price,"image",body.getString("image"),"customImage",custom,"description",body.getString("description"),"onSale",body.getBooleanValue("onSale"),"version",existing==null?1:existing.getIntValue("version")+1);
            if(existing==null) state.getJSONArray("products").add(p); else { existing.clear();existing.putAll(p); }
            audit(who,"product.save",id,object("before",before,"after",p));
            return object("id",id);
        });
    }
    public JSONObject category(JSONObject who,JSONObject body) {
        role(who,"admin");
        return createCategory(who,body);
    }
    public JSONObject createCategory(JSONObject who,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            String name=text(body,"name",20);
            require(validCategory(name),400,"分类名称为 1–20 个中文、字母、数字、空格或短横线");
            JSONArray list=categories(state);
            require(!list.contains(name),409,"分类已存在");
            list.add(name); audit(who,"category.create",name,new JSONObject());
            return object("name",name);
        });
    }
    public JSONObject renameCategory(JSONObject who,String oldName,JSONObject body) {
        role(who,"admin");
        final String originalName=text(object("name",oldName),"name",20);
        final String newName=text(body,"name",20);
        return change(state -> {
            require(validCategory(originalName) && validCategory(newName),400,"分类名称无效");
            require(!Arrays.asList("主食","小吃").contains(originalName),409,"默认分类不能重命名");
            JSONArray list=categories(state); require(list.contains(originalName),404,"分类不存在");
            require(!list.contains(newName) || originalName.equals(newName),409,"分类已存在");
            if(!originalName.equals(newName)) {
                for(int i=0;i<list.size();i++) if(originalName.equals(list.getString(i))) {list.set(i,newName);break;}
                for(Object raw:state.getJSONArray("products")) { JSONObject product=(JSONObject)raw; if(originalName.equals(product.getString("category"))) product.put("category",newName); }
                audit(who,"category.rename",originalName,object("name",newName));
            }
            return object("name",newName);
        });
    }
    public JSONObject deleteCategory(JSONObject who,String name) {
        role(who,"admin");
        final String categoryName=text(object("name",name),"name",20);
        return change(state -> {
            require(!Arrays.asList("主食","小吃").contains(categoryName),409,"默认分类不能删除");
            JSONArray list=categories(state); require(list.contains(categoryName),404,"分类不存在");
            for(Object raw:state.getJSONArray("products")) require(!categoryName.equals(((JSONObject)raw).getString("category")),409,"分类下仍有餐品，请先调整餐品分类");
            list.remove(categoryName); audit(who,"category.delete",categoryName,new JSONObject()); return object("name",categoryName);
        });
    }
    public JSONObject deleteProduct(JSONObject who,String id) {
        role(who,"admin");
        return change(state -> {
            JSONObject product=find(state.getJSONArray("products"),id); require(product!=null,404,"餐品不存在");
            require(!product.getBooleanValue("onSale"),409,"请先下架餐品再删除");
            long references=jdbc.queryForObject("SELECT COUNT(*) FROM ktv_order WHERE status IN ('unpaid','pending','preparing','delivering','refunding') AND payload LIKE ?",Long.class,"%\\\"id\\\":\\\""+id+"\\\"%");
            require(references==0,409,"餐品仍被进行中订单使用，请先完成或取消订单");
            state.getJSONArray("products").remove(product); audit(who,"product.delete",id,object("name",product.getString("name"))); return object("id",id);
        });
    }
    public JSONObject deleteRoom(JSONObject who,String id) {
        role(who,"admin");
        String roomId = id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
        return change(state -> {
            JSONObject room=find(state.getJSONArray("rooms"),roomId); require(room!=null,404,"包厢不存在");
            require(!room.getBooleanValue("bound"),409,"请先停用包厢再删除"); require(activeOrders(jdbc,"room",roomId)==0,409,"包厢仍有进行中订单，请先完成或取消订单");
            state.getJSONArray("rooms").remove(room); jdbc.update("DELETE FROM ktv_qr WHERE room=?",roomId); audit(who,"room.delete",roomId,new JSONObject()); return object("id",roomId);
        });
    }
    public JSONObject deleteAccount(JSONObject who,String id) {
        role(who,"admin");
        return change(state -> {
            JSONObject account=find(state.getJSONArray("accounts"),id); require(account!=null,404,"外包账号不存在");
            require(activeOrders(jdbc,"supplier_id",id)==0,409,"账号仍有进行中订单，请先完成或转派订单");
            state.getJSONArray("accounts").remove(account); jdbc.update("DELETE FROM ktv_session WHERE role='supplier' AND subject=?",id); audit(who,"account.delete",id,object("name",account.getString("name"))); return object("id",id);
        });
    }
    public JSONObject room(JSONObject who,String id,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            String pathId=id==null?"":id.trim().toUpperCase(Locale.ROOT);
            String name=text(body,"id",12).toUpperCase(Locale.ROOT);
            require(pathId.matches("[A-Z0-9\\u4e00-\\u9fa5-]{1,12}"),400,"包厢编号无效");
            JSONObject existing=find(state.getJSONArray("rooms"),pathId);
            JSONObject before=existing==null?null:copy(existing);
            require(name.matches("[A-Z0-9\u4e00-\u9fa5-]{1,12}"),400,"包厢编号无效");
            if(existing!=null) version(existing,body);
            require(pathId.equals(name) || find(state.getJSONArray("rooms"),name)==null,409,"包厢编号已存在");
            JSONObject room=object("id",name,"bound",body.getBooleanValue("bound"),"version",existing==null?1:existing.getIntValue("version")+1);
            if(existing==null) state.getJSONArray("rooms").add(room); else {existing.clear();existing.putAll(room);}
            if(!pathId.equals(name)) jdbc.update("DELETE FROM ktv_qr WHERE room=?",pathId);
            audit(who,"room.save",name,object("before",before,"after",room));
            return object("id",name);
        });
    }
    public JSONObject account(JSONObject who,String id,JSONObject body) {
        role(who,"admin");
        return change(state -> {
            JSONObject existing=id==null?null:find(state.getJSONArray("accounts"),id);
            if(id!=null) version(existing,body);
            String name=text(body,"name",60);
            for(Object raw:state.getJSONArray("accounts")) {
                JSONObject a=(JSONObject)raw;
                String currentName=a.getString("name");
                require(a==existing || currentName==null || !name.equalsIgnoreCase(currentName),409,"账号名称已存在");
            }
            String password=body.getString("password");
            if(existing==null || (password!=null&&!password.isEmpty())) require(PasswordPolicy.valid(password),400,"密码至少12个字符，UTF-8编码不得超过72字节");
            if(existing!=null && !body.getBooleanValue("enabled"))
                require(jdbc.queryForObject("SELECT COUNT(*) FROM ktv_order WHERE supplier_id=? AND status IN ('pending','preparing','delivering')",Integer.class,id)==0,409,"该食堂仍有进行中订单，请先完成或转派后再停用权限");
            String accountId=id;
            if(accountId==null) {
                int next=0;
                for(Object raw:state.getJSONArray("accounts")) {
                    String current=((JSONObject)raw).getString("id");
                    if(current!=null && current.matches("A[0-9]+")) try { next=Math.max(next,Integer.parseInt(current.substring(1))); } catch(NumberFormatException ignored) { }
                }
                next++; while(find(state.getJSONArray("accounts"),"A"+String.format("%02d",next))!=null) next++;
                accountId="A"+String.format("%02d",next);
            }
            JSONObject a=object("id",accountId,"name",name,"enabled",body.getBooleanValue("enabled"),"process",body.getBooleanValue("process"),"deliver",body.getBooleanValue("deliver"),"version",existing==null?1:existing.getIntValue("version")+1,"passwordHash",password!=null&&!password.isEmpty()?passwords.encode(password):existing.getString("passwordHash"));
            if(existing==null) state.getJSONArray("accounts").add(a); else {existing.clear();existing.putAll(a);}
            if(password!=null && !password.isEmpty()) jdbc.update("DELETE FROM ktv_session WHERE role='supplier' AND subject=?",accountId);
            audit(who,"account.save",accountId,object("enabled",a.get("enabled"),"process",a.get("process"),"deliver",a.get("deliver"),"passwordChanged",password!=null&&!password.isEmpty()));
            return object("id",accountId);
        });
    }
}



