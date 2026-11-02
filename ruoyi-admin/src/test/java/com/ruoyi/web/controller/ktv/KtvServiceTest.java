package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.*;
import com.ruoyi.common.utils.SecurityUtils;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.*;
import static com.ruoyi.web.controller.ktv.KtvService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KtvServiceTest {
    private KtvService service;private JdbcTemplate jdbc;private JSONObject admin,customer,supplier;private String scene;
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);service=new KtvService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        ReflectionTestUtils.setField(service,"adminPassword","Test-admin-2026!");ReflectionTestUtils.setField(service,"supplierPassword","Test-supplier-2026!");ReflectionTestUtils.setField(service,"demoPayment",true);service.initialize();
        admin=object("role","admin","subject","ktv-admin");customer=object("role","customer","subject","customer-test");supplier=object("role","supplier","subject","A01");scene=service.roomCode(admin,"A08",false).getString("scene");
    }
    JSONObject draft(String key) {return object("room","A08","scene",scene,"requestKey",key,"items",JSONArray.of(object("id","chicken","quantity",2)));}
    String create() {return service.createOrder(customer,draft(UUID.randomUUID().toString())).getString("id");}
    @Test void priceOwnershipAndIdempotency() {
        JSONObject body=draft("same");body.put("totalCents",1);body.put("paid",true);String id=service.createOrder(customer,body).getString("id");assertEquals(id,service.createOrder(customer,body).getString("id"));
        JSONObject o=service.order(customer,id);assertEquals(6400,o.getIntValue("totalCents"));assertFalse(o.getBooleanValue("paid"));
        assertThrows(ResponseStatusException.class,()->service.order(object("role","customer","subject","other"),id));
        body.getJSONArray("items").getJSONObject(0).put("quantity",3);assertThrows(ResponseStatusException.class,()->service.createOrder(customer,body));
    }
    @Test void customerRecoveryKeepsIdentityAfterExpiry() {
        JSONObject first=service.customerSession(null);jdbc.update("DELETE FROM ktv_session");JSONObject recovered=service.customerSession(object("recovery",first.getString("recovery")));assertEquals(first.getString("subject"),recovered.getString("subject"));assertNotEquals(first.getString("token"),recovered.getString("token"));assertThrows(ResponseStatusException.class,()->service.customerSession(object("recovery",token())));
    }
    @Test void bcryptByteLimitAndWhitespaceAreConsistent() {
        String pw="  secure-password-2026  ";service.account(admin,null,object("name","New supplier","password",pw,"enabled",true,"process",true,"deliver",true));
        assertEquals("supplier",service.staffSession(object("username","A03","password",pw),"ip").getString("role"));
        assertThrows(ResponseStatusException.class,()->service.staffSession(object("username","A03","password",pw.trim()),"ip"));
        assertThrows(ResponseStatusException.class,()->service.account(admin,null,object("name","Too long","password",String.join("",Collections.nCopies(25,"密")),"enabled",true)));
        assertThrows(Exception.class,()->SecurityUtils.encryptPassword(String.join("",Collections.nCopies(25,"密"))));
    }
    @Test void cancelCannotCancelPaidOrders() {String id=create();service.cancel(customer,id);service.cancel(customer,id);assertEquals("cancelled",service.order(customer,id).getString("status"));assertThrows(ResponseStatusException.class,()->service.advance(customer,id,new JSONObject(),true));}
    @Test void deliveryRequiresExplicitChecksAndActiveAccountCannotBeDisabled() {
        String id=create();service.advance(customer,id,new JSONObject(),true);JSONObject account=service.snapshot(admin).getJSONArray("accounts").getJSONObject(0);account.put("enabled",false);assertThrows(ResponseStatusException.class,()->service.account(admin,"A01",account));
        service.advance(supplier,id,object("status","preparing"),false);assertThrows(ResponseStatusException.class,()->service.advance(supplier,id,object("status","delivering","checkedIds",new JSONArray()),false));
        service.advance(supplier,id,object("status","delivering","checkedIds",JSONArray.of("chicken")),false);service.advance(supplier,id,object("status","completed"),false);
        assertEquals("completed",service.order(customer,id).getString("status"));assertFalse(service.order(supplier,id).containsKey("totalCents"));assertFalse(service.order(supplier,id).getJSONArray("items").getJSONObject(0).containsKey("price"));
    }
    @Test void reassignChangesSupplierAndLogsReason() {
        String id=create();service.advance(customer,id,new JSONObject(),true);service.account(admin,null,object("name","Other kitchen","password","Test-other-2026!","enabled",true,"process",true,"deliver",true));JSONObject o=service.order(admin,id);service.reassign(admin,id,object("supplierId","A03","reason","厨房检修","version",o.getIntValue("version")));assertEquals("A03",service.order(admin,id).getString("supplierId"));assertThrows(ResponseStatusException.class,()->service.advance(supplier,id,object("status","preparing"),false));assertTrue(service.audits(admin,1).toString().contains("厨房检修"));
    }
    @Test void rotatedQrRejectsOldAndWrongRoom() {service.roomCode(admin,"A08",true);assertThrows(ResponseStatusException.class,()->service.createOrder(customer,draft("old")));JSONObject body=draft("wrong");body.put("scene",service.roomCode(admin,"A09",false).getString("scene"));assertThrows(ResponseStatusException.class,()->service.createOrder(customer,body));}
    @Test void passwordRotationRevokesAllAdminSessions() {JSONObject session=service.staffSession(object("username","ktv-admin","password","Test-admin-2026!"),"ip");service.rotatePassword(admin,object("oldPassword","Test-admin-2026!","password","Another-secure-password"));assertThrows(ResponseStatusException.class,()->service.identity(session.getString("token")));assertEquals("admin",service.staffSession(object("username","ktv-admin","password","Another-secure-password"),"ip").getString("role"));}
    @Test void realPaymentLedgerRejectsMismatchAndDeduplicatesCallbacks() {
        customer=object("role","customer","subject","wx-test-openid");String id=create();JSONObject intent=service.paymentIntent(customer,id);String trade=intent.getString("tradeNo");assertEquals(trade,service.paymentIntent(customer,id).getString("tradeNo"));
        assertThrows(ResponseStatusException.class,()->service.confirmPayment(trade,"tx-1",1,"now","test-openid"));assertThrows(ResponseStatusException.class,()->service.confirmPayment(trade,"tx-1",6400,"now","other"));
        service.confirmPayment(trade,"tx-1",6400,"2026-10-17T12:00:00+08:00","test-openid");service.confirmPayment(trade,"tx-1",6400,"now","test-openid");assertEquals(2,service.order(customer,id).getJSONArray("events").size());assertThrows(ResponseStatusException.class,()->service.confirmPayment(trade,"tx-2",6400,"now","test-openid"));
        JSONObject o=service.order(admin,id),refund=service.refundIntent(admin,id,object("reason","缺货","version",o.getIntValue("version")));assertEquals(refund.getString("refundNo"),service.refundIntent(admin,id,object("reason","重试","version",0)).getString("refundNo"));
        assertThrows(ResponseStatusException.class,()->service.confirmRefund(refund.getString("refundNo"),"SUCCESS",6400,1));service.confirmRefund(refund.getString("refundNo"),"SUCCESS",6400,6400);service.confirmRefund(refund.getString("refundNo"),"SUCCESS",6400,6400);assertEquals("refunded",service.order(customer,id).getString("status"));
    }
    @Test void concurrentSubmitCreatesOneOrder() throws Exception {ExecutorService pool=Executors.newFixedThreadPool(8);try {List<Callable<String>> tasks=new ArrayList<>();for(int i=0;i<8;i++)tasks.add(()->service.createOrder(customer,draft("concurrent")).getString("id"));Set<String> ids=new HashSet<>();for(Future<String> result:pool.invokeAll(tasks))ids.add(result.get());assertEquals(1,ids.size());assertEquals(1,service.snapshot(customer).getJSONArray("orders").size());}finally{pool.shutdownNow();}}
    @Test void limitsRejectFloodAndExcessUnpaidOrders() {service.limit("test",2,60);service.limit("test",2,60);assertThrows(ResponseStatusException.class,()->service.limit("test",2,60));for(int i=0;i<5;i++)create();assertThrows(ResponseStatusException.class,this::create);}
    @Test void malformedRequestsUse400AndDoNotLeakExceptions() throws Exception {
        KtvWechat wechat=mock(KtvWechat.class);KtvAccess access=new KtvAccess(service);MockMvc mvc=MockMvcBuilders.standaloneSetup(new KtvController(service,access,wechat)).setControllerAdvice(new KtvErrors()).build();
        mvc.perform(post("/ktv-api/orders").contentType("application/json").content("{oops")).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
        JSONObject login=service.staffSession(object("username","ktv-admin","password","Test-admin-2026!"),"ip");mvc.perform(multipart("/ktv-api/images").header("X-KTV-Token",login.getString("token"))).andExpect(status().isBadRequest());
    }
    @Test void migrationPreservesLegacyOrdersAndIsRepeatable() {
        String id=create();JSONObject original=service.order(admin,id);JSONObject legacy=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1",String.class));
        legacy.put("orders",JSONArray.of(JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_order WHERE id=?",String.class,id))));
        jdbc.update("UPDATE ktv_shared_state SET payload=? WHERE id=1",legacy.toJSONString());jdbc.update("DELETE FROM ktv_order");
        service.initialize();service.initialize();assertEquals(original,service.order(admin,id));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ktv_order",Integer.class));assertFalse(JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_shared_state WHERE id=1",String.class)).containsKey("orders"));
    }
    @Test void historyIsPagedAndOlderOrderCanStillBeOpened() {
        String first=create();service.cancel(customer,first);for(int i=0;i<101;i++){String id=create();service.cancel(customer,id);}
        JSONObject page=service.snapshot(customer,1,first);assertEquals(100,page.getJSONArray("orders").size());assertTrue(page.getBooleanValue("hasMore"));assertEquals(first,page.getJSONObject("orderDetail").getString("id"));
        JSONObject last=service.snapshot(customer,2,null);assertEquals(2,last.getJSONArray("orders").size());assertFalse(last.getBooleanValue("hasMore"));assertEquals(0,service.snapshot(object("role","customer","subject","other"),1,first).getJSONArray("orders").size());
    }
    @Test void maintenanceExpiresUnpaidOrdersWithoutTouchingPaidOrders() {
        String expired=create(),paid=create();service.advance(customer,paid,new JSONObject(),true);
        JSONObject old=JSON.parseObject(jdbc.queryForObject("SELECT payload FROM ktv_order WHERE id=?",String.class,expired));old.put("createdAt",java.time.LocalDateTime.now().minusMinutes(20).toString());jdbc.update("UPDATE ktv_order SET payload=? WHERE id=?",old.toJSONString(),expired);
        KtvMaintenance maintenance=new KtvMaintenance(service,mock(KtvWechat.class),jdbc);ReflectionTestUtils.setField(maintenance,"timeout",15);maintenance.reconcile();
        assertEquals("cancelled",service.order(customer,expired).getString("status"));assertEquals("pending",service.order(customer,paid).getString("status"));
    }
    @Test void filtersSearchAllHistoryAndKeepRoleIsolation() {
        String first=create();service.advance(customer,first,new JSONObject(),true);
        for(int i=0;i<101;i++){String id=create();service.cancel(customer,id);}
        JSONObject filtered=service.snapshot(admin,1,null,"pending","","A08","paid");
        assertEquals(first,filtered.getJSONArray("orders").getJSONObject(0).getString("id"));assertFalse(filtered.getBooleanValue("hasMore"));
        assertEquals(1,filtered.getJSONObject("orderCounts").getIntValue("pending"));
        assertEquals(first,service.snapshot(customer,1,null,"",first,"","").getJSONArray("orders").getJSONObject(0).getString("id"));
        assertEquals(100,service.snapshot(admin,1,null,"","a08","","").getJSONArray("orders").size());
        assertEquals(0,service.snapshot(object("role","customer","subject","other"),1,null,"",first,"","").getJSONArray("orders").size());
        JSONObject kitchen=service.snapshot(supplier,1,null,"completed","","","");assertEquals(0,kitchen.getJSONArray("orders").size());assertEquals(first,kitchen.getJSONArray("orderAlerts").getJSONObject(0).getString("id"));
        assertThrows(ResponseStatusException.class,()->service.snapshot(admin,1,null,"invalid","","",""));
    }
    @Test void roomBackfillResumesAndPreservesOrderData() {
        String id=create();JSONObject before=service.order(customer,id);jdbc.execute("ALTER TABLE ktv_order DROP COLUMN room");
        service.initialize();service.initialize();assertEquals("A08",jdbc.queryForObject("SELECT room FROM ktv_order WHERE id=?",String.class,id));assertEquals(before,service.order(customer,id));
    }
    @Test void productionRejectsDemoPayment() {ReflectionTestUtils.setField(service,"production",true);assertThrows(ResponseStatusException.class,service::initialize);}
    @Test void categoryCrudIsSharedAndProtectsDefaultsAndReferences() {
        assertTrue(service.snapshot(admin).getJSONArray("categories").contains("主食"));
        service.createCategory(admin,object("name","饮品"));
        String productId="food-drink-test";
        service.product(admin,productId,object("id",productId,"name","矿泉水","category","饮品","price",3.0,"image","chicken","description","","onSale",true,"version",0));
        service.renameCategory(admin,"饮品",object("name","酒水"));
        JSONObject renamed=find(service.snapshot(admin).getJSONArray("products"),productId);
        assertEquals("酒水",renamed.getString("category"));
        assertThrows(ResponseStatusException.class,()->service.deleteCategory(admin,"酒水"));
        JSONObject update=copy(renamed);update.put("category","主食");service.product(admin,productId,update);
        service.deleteCategory(admin,"酒水");
        assertThrows(ResponseStatusException.class,()->service.renameCategory(admin,"主食",object("name","主餐")));
        assertThrows(ResponseStatusException.class,()->service.deleteCategory(admin,"小吃"));
    }
    @Test void roomCreateNormalizesIdsAndRejectsDuplicates() {
        JSONObject created=service.room(admin," a100 ",object("id"," a100 ","bound",true,"version",0));
        assertEquals("A100",created.getString("id"));
        assertTrue(service.snapshot(admin).getJSONArray("rooms").toJSONString().contains("A100"));
        assertThrows(ResponseStatusException.class,()->service.room(admin,"A101",object("id","A100","bound",true,"version",0)));
        service.room(admin,"a100",object("id"," a100 ","bound",false,"version",1));
        assertFalse(find(service.snapshot(admin).getJSONArray("rooms"),"A100").getBooleanValue("bound"));
    }
    @Test void accountCreateAcceptsTrimmedAsciiNameAndPassword() {
        JSONObject created=service.account(admin,null,object("name"," admin123123123 ","password","admin123123123","enabled",true,"process",true,"deliver",true));
        JSONObject saved=find(service.snapshot(admin).getJSONArray("accounts"),created.getString("id"));
        assertNotNull(saved); assertEquals("admin123123123",saved.getString("name")); assertTrue(saved.getBooleanValue("enabled"));
    }
    @Test void accountIdsDoNotReuseAfterDeletionAndHistoricalOrdersRemain() {
        service.account(admin,null,object("name","临时账号","password","Test-temporary-2026!","enabled",false,"process",true,"deliver",true));
        JSONObject accounts=service.snapshot(admin);assertNotNull(find(accounts.getJSONArray("accounts"),"A03"));
        service.deleteAccount(admin,"A02");
        service.account(admin,null,object("name","新账号","password","Test-new-2026!","enabled",false,"process",true,"deliver",true));
        assertNotNull(find(service.snapshot(admin).getJSONArray("accounts"),"A04"));
        String id=create();service.cancel(customer,id);service.deleteAccount(admin,"A01");
        assertEquals("cancelled",service.order(customer,id).getString("status"));
    }
    @Test void deletesRequireSafeStateAndKeepCompletedOrderSnapshots() {
        String productId="food-delete-test";
        service.product(admin,productId,object("id",productId,"name","待删除","category","主食","price",1.0,"image","chicken","description","","onSale",true,"version",0));
        assertThrows(ResponseStatusException.class,()->service.deleteProduct(admin,productId));
        JSONObject product=find(service.snapshot(admin).getJSONArray("products"),productId);product.put("onSale",false);service.product(admin,productId,product);service.deleteProduct(admin,productId);
        String preserved=create();service.cancel(customer,preserved);assertEquals("cancelled",service.order(customer,preserved).getString("status"));
        JSONObject room=service.snapshot(admin).getJSONArray("rooms").getJSONObject(3); // B05 is seeded unbound
        String roomId=room.getString("id");service.deleteRoom(admin,roomId);assertNull(find(service.snapshot(admin).getJSONArray("rooms"),roomId));
        assertThrows(ResponseStatusException.class,()->service.deleteRoom(admin,"A08"));
    }
}
