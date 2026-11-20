package org.floodguard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiGuards extends OncePerRequestFilter {
    private record Bucket(long hour,int count) {}
    private final ConcurrentHashMap<String,Bucket> buckets=new ConcurrentHashMap<>();
    @Value("${floodguard.twilio-auth-token}") String twilioToken;
    @Value("${floodguard.public-base-url:}") String publicBase;

    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();
        if(path.startsWith("/api/v1/twilio/")) {
            if(twilioToken.isBlank()||publicBase.isBlank()||!validTwilio(req)) { res.sendError(403,"Invalid Twilio callback");return; }
        }
        if("GET".equals(req.getMethod())&&path.startsWith("/api/v1/")&&!path.startsWith("/api/v1/admin/")&&!path.startsWith("/api/v1/officer/")) {
            long hour=Instant.now().getEpochSecond()/3600;
            String ip=req.getRemoteAddr();
            Bucket next=buckets.compute(ip,(k,b)->b==null||b.hour!=hour?new Bucket(hour,1):new Bucket(hour,b.count+1));
            if(next.count>100){res.setHeader("Retry-After",String.valueOf(3600-Instant.now().getEpochSecond()%3600));res.sendError(429,"Public API rate limit exceeded");return;}
            res.setHeader("X-RateLimit-Limit","100");
        }
        chain.doFilter(req,res);
    }

    private boolean validTwilio(HttpServletRequest req) {
        try {
            String signature=req.getHeader("X-Twilio-Signature");
            if(signature==null)return false;
            StringBuilder value=new StringBuilder(publicBase+req.getRequestURI());
            TreeMap<String,String[]> sorted=new TreeMap<>(req.getParameterMap());
            sorted.forEach((key,values)->{for(String v:values)value.append(key).append(v);});
            Mac mac=Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(twilioToken.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
            String expected=Base64.getEncoder().encodeToString(mac.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8));
        }catch(Exception ex){return false;}
    }
}
