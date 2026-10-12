import io.github.try2love.codexbridge.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Exercises the actual updater with synthetic responses, without external traffic. */
public class UpdateDownloadTests {
 static byte[] payload="signed-package-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
 static String redirect;static long length=payload.length;static int cookies,checks;
 static class Response extends HttpURLConnection {
  Response(URL url){super(url);}
  public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
  public int getResponseCode(){return redirect!=null&&url.getHost().equals("github.com")?302:200;}
  public String getHeaderField(String key){return key.equalsIgnoreCase("Location")?redirect:null;}
  public long getContentLengthLong(){return length;}
  public InputStream getInputStream(){return new ByteArrayInputStream(payload);}
  public void setRequestProperty(String key,String value){check(!key.equalsIgnoreCase("Cookie"),"release request must not carry cookies");}
 }
 public static void main(String[] args)throws Exception {
  URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new Response(url);}}:null);
  File dir=new File(args[0]);dir.mkdirs();StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(payload))digest.append(String.format("%02x",b));
  for(String missing:new String[]{null,"sha256:invalid"}){
   MobileRelease release=new MobileRelease("2.0.3","",payload.length,missing);
   try{task(release,dir);throw new AssertionError("missing or malformed digest accepted");}catch(IOException expected){checks++;}
  }
  MobileRelease valid=new MobileRelease("2.0.3","",payload.length,"sha256:"+digest);
  run(valid,dir,ArtifactDownload.State.COMPLETE);
  run(new MobileRelease("2.0.3","",payload.length,String.join("",Collections.nCopies(64,"0"))),dir,ArtifactDownload.State.FAILED);
  run(new MobileRelease("2.0.3","",payload.length+1,digest.toString()),dir,ArtifactDownload.State.FAILED);
  for(long size:new long[]{-1,0,512L*1024*1024+1})try{task(new MobileRelease("2.0.3","",size,digest.toString()),dir);throw new AssertionError("invalid release size");}catch(IOException expected){checks++;}
  redirect="https://release-assets.githubusercontent.com/fixture.apk";run(valid,dir,ArtifactDownload.State.COMPLETE);
  redirect="https://evil.example/fixture.apk";run(valid,dir,ArtifactDownload.State.FAILED);
  redirect="http://release-assets.githubusercontent.com/fixture.apk";run(valid,dir,ArtifactDownload.State.FAILED);
  redirect=null;length=512L*1024*1024+1;run(valid,dir,ArtifactDownload.State.FAILED);
  check(cookies==0,"gateway cookies never read");
  check(dir.list().length==0,"temporary packages cleaned");
  System.out.println(checks+" update download checks passed");
 }
 static ArtifactDownload.Task task(MobileRelease r,File dir)throws IOException{return new ArtifactDownload.Task(r.download,MobileRelease.REPO,dir,r.size,r.sha256,MobileRelease.asset(r.version));}
 static void run(MobileRelease r,File dir,ArtifactDownload.State expected)throws Exception{ArtifactDownload.Task t=task(r,dir);t.run(()->{cookies++;return "fixture=private";},null);check(t.state==expected,"state "+expected+", got "+t.state);t.cancel();}
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
