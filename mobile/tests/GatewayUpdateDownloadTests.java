import io.github.try2love.codexbridge.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

public class GatewayUpdateDownloadTests {
 static int checks,requests,redirects;static boolean corrupt,redirect,expired;
 static byte[] payload=new byte[1024*1024+27];
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  new Random(4).nextBytes(payload);StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(payload))digest.append(String.format("%02x",b));
  File dir=new File(args[0]);dir.mkdirs();MobileRelease release=new MobileRelease("2.0.3","",payload.length,digest.toString());String identifier=String.join("",Collections.nCopies(64,"a"));
  HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(javax.net.ssl.SSLContext.getDefault()));String base="https://127.0.0.1:"+server.getAddress().getPort()+"/d/"+String.join("",Collections.nCopies(32,"b"));
  server.createContext("/redirect",exchange->{redirects++;exchange.sendResponseHeaders(500,-1);exchange.close();});
  server.createContext("/d/",exchange->{try{
   requests++;check("fixture=session".equals(exchange.getRequestHeaders().getFirst("Cookie")),"gateway request authenticated");
   check(exchange.getRequestURI().getPath().equals(new URI(base).getPath()+"/api/mobile/update/packages/"+identifier),"relay device prefix retained");
   if(redirect){exchange.getResponseHeaders().set("Location","/redirect");exchange.sendResponseHeaders(302,-1);return;}
   if(expired){exchange.sendResponseHeaders(400,-1);return;}
   String[] range=exchange.getRequestHeaders().getFirst("Range").substring(6).split("-");int first=Integer.parseInt(range[0]),last=Math.min(payload.length-1,Integer.parseInt(range[1]));
   byte[] bytes=Arrays.copyOfRange(payload,first,last+1);if(corrupt)bytes[0]^=1;
   exchange.getResponseHeaders().set("ETag","\"fixture\"");exchange.getResponseHeaders().set("Content-Range","bytes "+first+"-"+last+"/"+payload.length);exchange.sendResponseHeaders(206,bytes.length);exchange.getResponseBody().write(bytes);
  }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});server.start();
  try{
   ArtifactDownload.Task task=ArtifactDownload.Task.gatewayUpdate(base,identifier,release,dir);task.run(()->"fixture=session",null);check(task.state==ArtifactDownload.State.COMPLETE,"gateway update completes");check(requests==2,"range chunks used");task.verifyCompletedUpdate();task.cancel();
   for(String id:new String[]{"../private","x","a?url=http://localhost"})try{ArtifactDownload.Task.gatewayUpdate(base,id,release,dir);throw new AssertionError("bad identifier");}catch(IOException expected){checks++;}
   check(!ArtifactDownload.accepts(base+"/api/mobile/update/packages/"+identifier,base),"update is not a generic attachment");
   try{new ArtifactDownload.Task(base+"/api/mobile/update/packages/"+identifier,base,dir);throw new AssertionError("unverified update accepted");}catch(IOException expected){checks++;}
   corrupt=true;task=ArtifactDownload.Task.gatewayUpdate(base,identifier,release,dir);task.run(()->"fixture=session",null);check(task.state==ArtifactDownload.State.FAILED&&task.restartRequired,"gateway package digest checked");task.cancel();corrupt=false;
   redirect=true;task=ArtifactDownload.Task.gatewayUpdate(base,identifier,release,dir);task.run(()->"fixture=session",null);check(task.state==ArtifactDownload.State.FAILED&&redirects==0,"gateway redirects rejected without forwarding credentials");task.cancel();redirect=false;
   expired=true;task=ArtifactDownload.Task.gatewayUpdate(base,identifier,release,dir);task.run(()->"fixture=session",null);check(task.state==ArtifactDownload.State.FAILED&&task.restartRequired,"expired cache requires new prepare");task.cancel();
   check(dir.list().length==0,"temporary downloads cleaned");System.out.println(checks+" gateway update download checks passed");
  }finally{server.stop(0);}
 }
}
