package com.ruoyi.web.controller.ktv;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** Public routing is intentional: each business endpoint authenticates its opaque KTV session. */
@Anonymous
@RestController
@RequestMapping("/ktv-api")
public class KtvController {
    private final KtvService service;
    private final KtvAccess access;
    private final KtvWechat wechat;
    @Value("${ktv.upload-path:./data/ktv-uploads}") private String uploadPath;
    public KtvController(KtvService service,KtvAccess access,KtvWechat wechat) { this.service=service;this.access=access;this.wechat=wechat; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    private JSONObject who(HttpServletRequest request) { return access.who(request); }
    @PostMapping("/sessions/customer") public Object customer(@RequestBody(required=false) JSONObject body,HttpServletRequest request) {service.limit("customer:"+request.getRemoteAddr(),30,60);return service.customerSession(body); }
    @PostMapping("/sessions/staff") public Object staff(@RequestBody JSONObject body,HttpServletRequest request) { return service.staffSession(body,request.getRemoteAddr()); }
    @DeleteMapping("/session") public Object logout(HttpServletRequest request) { service.logout(request.getHeader("X-KTV-Token")); return KtvService.object("ok",true); }
    @GetMapping("/state") public Object state(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) String orderId,@RequestParam(defaultValue="") String filter,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String room,@RequestParam(defaultValue="") String payment,HttpServletRequest request) { JSONObject result=service.snapshot(who(request),page,orderId,filter,search,room,payment);result.put("wechatPayment",wechat.paymentReady());return result; }
    @PostMapping("/orders") public Object order(@RequestBody JSONObject body,HttpServletRequest request) {JSONObject who=who(request);service.limit("order:"+who.getString("subject"),20,60);return service.createOrder(who,body); }
    @PostMapping("/orders/{id}/pay-demo") public Object pay(@PathVariable String id,HttpServletRequest request) { return service.advance(who(request),id,new JSONObject(),true); }
    @PostMapping("/orders/{id}/transition") public Object advance(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) { return service.advance(who(request),id,body,false); }
    @PutMapping("/products/{id}") public Object product(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) { return service.product(who(request),id,body); }
    @DeleteMapping("/products/{id}") public Object deleteProduct(@PathVariable String id,HttpServletRequest request) { return service.deleteProduct(who(request),id); }
    @PostMapping("/categories") public Object createCategory(@RequestBody JSONObject body,HttpServletRequest request) { return service.createCategory(who(request),body); }
    @PutMapping("/categories/{name}") public Object renameCategory(@PathVariable String name,@RequestBody JSONObject body,HttpServletRequest request) { return service.renameCategory(who(request),name,body); }
    @DeleteMapping("/categories/{name}") public Object deleteCategory(@PathVariable String name,HttpServletRequest request) { return service.deleteCategory(who(request),name); }
    @PutMapping("/rooms/{id}") public Object room(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) { return service.room(who(request),id,body); }
    @DeleteMapping("/rooms/{id}") public Object deleteRoom(@PathVariable String id,HttpServletRequest request) { return service.deleteRoom(who(request),id); }
    @PostMapping("/accounts") public Object createAccount(@RequestBody JSONObject body,HttpServletRequest request) { return service.account(who(request),null,body); }
    @PutMapping("/accounts/{id}") public Object account(@PathVariable String id,@RequestBody JSONObject body,HttpServletRequest request) { return service.account(who(request),id,body); }
    @DeleteMapping("/accounts/{id}") public Object deleteAccount(@PathVariable String id,HttpServletRequest request) { return service.deleteAccount(who(request),id); }
    @PostMapping("/images") public Object upload(@RequestParam("file") MultipartFile file,HttpServletRequest request) throws IOException {
        KtvService.require("admin".equals(who(request).getString("role")),403,"仅管理员可上传图片");
        KtvService.require(file.getSize()>0 && file.getSize()<=2*1024*1024,400,"图片不能超过 2 MB");
        BufferedImage image;
        try(javax.imageio.stream.ImageInputStream input=ImageIO.createImageInputStream(file.getInputStream())) {
            java.util.Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input);
            KtvService.require(readers.hasNext(),400,"请选择 PNG 或 JPG 图片");javax.imageio.ImageReader reader=readers.next();
            try {reader.setInput(input);String format=reader.getFormatName();KtvService.require((format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg")) && reader.getWidth(0)<=4096 && reader.getHeight(0)<=4096,400,"请选择不超过 4096 像素的 PNG 或 JPG 图片");image=reader.read(0);} finally {reader.dispose();}
        }
        Path directory=Paths.get(uploadPath).toAbsolutePath().normalize(); Files.createDirectories(directory);
        String name=UUID.randomUUID()+".png"; ImageIO.write(image,"png",directory.resolve(name).toFile());
        return KtvService.object("url","/ktv-api/assets/"+name);
    }
    @GetMapping("/assets/{name}") public ResponseEntity<byte[]> asset(@PathVariable String name) throws IOException {
        KtvService.require(name.matches("[a-f0-9-]+\\.(?:png|jpe?g)"),404,"图片不存在");
        Path file=Paths.get(uploadPath).toAbsolutePath().normalize().resolve(name);
        KtvService.require(Files.isRegularFile(file),404,"图片不存在");
        MediaType type=name.endsWith(".png")?MediaType.IMAGE_PNG:MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30))).body(Files.readAllBytes(file));
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Object> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatus()).body(KtvService.object("message",error.getReason()));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Object> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(KtvService.object("message","请求数据格式无效"));
    }
}
